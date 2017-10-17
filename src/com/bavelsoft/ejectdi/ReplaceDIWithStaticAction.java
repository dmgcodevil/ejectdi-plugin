package com.bavelsoft.ejectdi;

import com.google.common.collect.TreeTraverser;
import com.intellij.find.findUsages.FindUsagesManager;
import com.intellij.find.findUsages.FindUsagesOptions;
import com.intellij.find.findUsages.JavaClassFindUsagesOptions;
import com.intellij.find.findUsages.JavaFindUsagesHandler;
import com.intellij.find.findUsages.JavaFindUsagesHandlerFactory;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiImportList;
import com.intellij.psi.PsiManager;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor;
import com.intellij.refactoring.makeStatic.Settings;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class ReplaceDIWithStaticAction extends AnAction {

    private static final Logger LOG = Logger.getInstance(ReplaceDIWithStaticAction.class);

    @Override
    public void actionPerformed(AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) {
            LOG.error("project is null");
            return;
        }
        PsiElement selectedPSIElement = e.getData(LangDataKeys.PSI_ELEMENT);
        if (selectedPSIElement == null) {
            LOG.error("selected psi element is null");
            return;
        }
        List<PsiFile> psiFiles = new ArrayList<>();

        if (selectedPSIElement instanceof PsiFile) {
            psiFiles.add((PsiFile) selectedPSIElement);
        } else if (selectedPSIElement instanceof PsiClass) {
            psiFiles.add(((PsiClass) selectedPSIElement).getContainingFile());
        } else if (selectedPSIElement instanceof PsiDirectory) {
            PsiDirectory psiDirectory = (PsiDirectory) selectedPSIElement;
            TreeTraverser<PsiFileSystemItem> traverser = new TreeTraverser<PsiFileSystemItem>() {
                @Override
                public Iterable<PsiFileSystemItem> children(PsiFileSystemItem psiFileSystemItem) {
                    if (psiFileSystemItem.isDirectory()) {
                        return Arrays.stream(psiFileSystemItem.getChildren())
                                .filter(element -> element instanceof PsiFileSystemItem)
                                .map(element -> (PsiFileSystemItem) element).collect(Collectors.toList());
                    }
                    return Collections.emptyList();
                }
            };
            for (PsiFileSystemItem t : traverser.breadthFirstTraversal(psiDirectory)) {
                PsiFile psiFile = PsiManager.getInstance(project).findFile(t.getVirtualFile());
                if (psiFile != null) {
                    psiFiles.add(psiFile);
                }
            }

            LOG.debug(String.format("DEBUG: folder '%s' contains %d files", psiDirectory.getName(), psiFiles.size()));
        }

        List<PsiFile> javaPsiFiles = psiFiles.stream()
                .filter(psiFile -> psiFile.getFileType() instanceof JavaFileType).collect(Collectors.toList());

        List<Pair<PsiFile, PsiClass>> psiClasses = javaPsiFiles.stream().map(psiFile -> Pair.create(psiFile, Arrays.stream(psiFile.getChildren())
                .filter(psiElement -> psiElement instanceof PsiClass)
                .map(psiElement -> (PsiClass) psiElement)
                .filter(psiClass -> !psiClass.isEnum() && !psiClass.isAnnotationType() && !psiClass.isInterface())// we are interested only in classes
                .findFirst())).filter(p -> p.second.isPresent()).map(pair -> Pair.create(pair.first, pair.second.get()))
                .collect(Collectors.toList());


        for (Pair<PsiFile, PsiClass> psiFileAndClass : psiClasses) {
            refactorJavaClass(project, psiFileAndClass.first, psiFileAndClass.second);
        }
    }

    private void refactorJavaClass(@Nonnull Project project, @Nonnull PsiFile psiFile, @Nonnull PsiClass psiClass) {

        if (psiClass.getAllInnerClasses().length != 0) {
            LOG.warn(String.format("%s: classes that contain any inner classes aren't supported.", psiClass.getQualifiedName()));
            return;
        }
        if (psiClass.getConstructors().length > 1) {
            LOG.warn(String.format("%s: classes with multiple constructors aren't supported.", psiClass.getQualifiedName()));
            return;
        }

        if (psiClass.getConstructors().length == 1 && psiClass.getConstructors()[0].getParameterList().getParametersCount() > 0) {
            LOG.warn(String.format("%s: constructor has parameters", psiClass.getQualifiedName()));
            return;
        }


        boolean statelessElement = Arrays.stream(psiClass.getAllFields())
                .filter(psiField -> !psiField.getModifierList().hasModifierProperty(PsiModifier.STATIC)).count() == 0;

        if (statelessElement) {
            LOG.info(String.format("%s is stateless. converting to static...", psiClass));
            List<PsiMethod> methods = Arrays.stream(psiClass.getAllMethods())
                    .filter(psiMethod -> !psiMethod.isConstructor())
                    .filter(psiMethod -> !psiMethod.getModifierList().hasModifierProperty(PsiModifier.STATIC) &&
                            // skip inherited methods from Object class.
                            !Object.class.getCanonicalName().equals(psiMethod.getContainingClass().getQualifiedName())
                    )
                    .collect(Collectors.toList());
            final Settings settings = new Settings(
                    true,
                    null, null,
                    false
            );
            for (PsiMethod method : methods) {
                MakeMethodStaticProcessor makeMethodStaticProcessor = new CustomMakeMethodStaticProcessor(project, method, settings);
                makeMethodStaticProcessor.setPreviewUsages(false);
                makeMethodStaticProcessor.setPrepareSuccessfulSwingThreadCallback(null);
                makeMethodStaticProcessor.run();
            }
            findUsagesOfStatelessClassAndRemoveInstanceUsages(project, psiClass);

            psiClass.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitAnnotation(PsiAnnotation annotation) {
                    // todo use parameter for this ?
                    if (annotation.getQualifiedName().endsWith("Singleton")) {
                        WriteCommandAction.runWriteCommandAction(project, () -> annotation.delete());
                    }
                    super.visitAnnotation(annotation);
                }
            });

            WriteCommandAction.runWriteCommandAction(project, () -> psiClass.getModifierList().setModifierProperty(PsiModifier.FINAL, true));

            // make default constructor private
            if (psiClass.getConstructors().length == 1) {
                PsiMethod constructor = psiClass.getConstructors()[0];
                WriteCommandAction.runWriteCommandAction(project, () -> constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true));
            } else {
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    PsiMethod constructor = JavaPsiFacade.getElementFactory(project).createConstructor(psiClass.getNameIdentifier().getText());
                    constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);
                    psiClass.add(constructor);
                });
            }

        } else {
            LOG.info(String.format("class %s is stateful. exit", psiClass.getQualifiedName()));
        }
    }

    private void findUsagesOfStatelessClassAndRemoveInstanceUsages(Project project, PsiClass psiClass) {
        final CountDownLatch latch = new CountDownLatch(1);
        List<Usage> usages = new ArrayList<>();
        FindUsagesOptions findUsagesOptions = new JavaClassFindUsagesOptions(project);
        JavaFindUsagesHandler javaFindUsagesHandler = new JavaFindUsagesHandler(psiClass, JavaFindUsagesHandlerFactory.getInstance(project));
        String qualifiedName = psiClass.getQualifiedName();
        FindUsagesManager.startProcessUsages(javaFindUsagesHandler, new PsiElement[]{psiClass}, new PsiElement[0], usage -> {
            LOG.debug(String.format("class: %s, usage: %s", qualifiedName, usage.toString()));
            usages.add(usage);
            return true;
        }, findUsagesOptions, () -> {
            LOG.debug(String.format("found all usages of class: %s", qualifiedName));
            latch.countDown();
        });

        try {
            latch.await();
            delete(project, psiClass, usages);
        } catch (InterruptedException e1) {
            LOG.error(e1.getMessage());
            e1.printStackTrace();
        }
    }

    public void delete(Project project, PsiClass psiClass, List<Usage> usages) {
        Set<PsiElement> forDelete = usages.stream().filter(usage -> {
            if (!(usage instanceof UsageInfo2UsageAdapter)) {
                LOG.warn(String.format("%s usage is not supported: %s", psiClass.getQualifiedName(), usage));
                return false;
            }
            return true;
        }).map(usage -> {
            PsiElement psiElement = ((UsageInfo2UsageAdapter) usage).getElement();
            PsiElement parent;
            // we need to remove: class fields, constructor injections, instance declarations;
            if (psiElement.getContext() != null) {
                parent = psiElement.getContext().getParent();
            } else {
                LOG.warn(String.format("context is empty for usage:%s, element: %s", usage, psiElement));
                parent = psiElement.getParent().getParent();
            }
            // consider static method call on 'psiClass' should be ignored
            // don't remove imports
            if (parent instanceof PsiMethodCallExpression || parent instanceof PsiImportList) {
                return null;
            }
            return parent;

        }).filter(Objects::nonNull).collect(Collectors.toSet());


        CustomSafeDeleteProcessor safeDeleteProcessor = CustomSafeDeleteProcessor.createInstance(project, () -> {
                }, forDelete.toArray(new PsiElement[forDelete.size()]), false,
                false, true);
        safeDeleteProcessor.run();

    }

}
