package com.bavelsoft.ejectdi;

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
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor;
import com.intellij.refactoring.makeStatic.Settings;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageInfo2UsageAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

public class ReplaceDIWithStaticAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        System.out.println("actionPerformed");
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        if (psiFile == null) {
            System.out.println("yet only files are supported");
            return;
        }
        if (!(psiFile.getFileType() instanceof JavaFileType)) {
            System.out.println("only java source classes are supported");
            return;
        }
        Project project = e.getProject();
        if (project == null) {
            System.out.println("error: project is null");
            return;
        }
        Optional<PsiClass> psiClassOpt = Arrays.stream(psiFile.getChildren())
                .filter(psiElement -> psiElement instanceof PsiClass)
                .map(psiElement -> (PsiClass) psiElement)
                .filter(psiClass -> !psiClass.isEnum() && !psiClass.isAnnotationType() && !psiClass.isInterface())// we are interested only in classes
                .findFirst();

        if (!psiClassOpt.isPresent()) {
            System.out.println("only classes supported, not enums, annotations or interfaces");
            return;
        }
        PsiClass psiClass = psiClassOpt.get();
        if (psiClass.getAllInnerClasses().length != 0) {
            System.out.println("WARN: classes that contain any inner classes aren't supported");
            return;
        }
        if (psiClass.getConstructors().length > 1) {
            System.out.println("WARN: classes with multiple constructors aren't supported");
            return;
        }

        if (psiClass.getConstructors().length == 1 && psiClass.getConstructors()[0].getParameterList().getParametersCount() > 0) {
            System.out.println("WARN: constructor has parameters");
            return;
        }


        boolean statelessElement = Arrays.stream(psiClass.getAllFields()).filter(psiField -> !psiField.getModifierList().hasExplicitModifier("static")).count() == 0;

        if (statelessElement) {
            System.out.println("class is stateless. starting to refactoring...");
            System.out.println("converting methods to static");
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
            findUsagesOfStatelessClassAndRemoveInstanceUsages(e, psiClass);

            psiClass.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitAnnotation(PsiAnnotation annotation) {
                    // todo use parameter for this ?
                    if (annotation.getQualifiedName().endsWith("Singleton")) {
                        WriteCommandAction.runWriteCommandAction(e.getProject(), () -> annotation.delete());
                    }
                    super.visitAnnotation(annotation);
                }
            });

            WriteCommandAction.runWriteCommandAction(e.getProject(), () -> psiClass.getModifierList().setModifierProperty(PsiModifier.FINAL, true));

            // make default constructor private
            if (psiClass.getConstructors().length == 1) {
                PsiMethod constructor = psiClass.getConstructors()[0];
                WriteCommandAction.runWriteCommandAction(e.getProject(), () -> constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true));
            } else {
                WriteCommandAction.runWriteCommandAction(project, () -> {
                    PsiMethod constructor = JavaPsiFacade.getElementFactory(project).createConstructor(psiClass.getNameIdentifier().getText());
                    constructor.getModifierList().setModifierProperty(PsiModifier.PRIVATE, true);
                    psiClass.add(constructor);
                });
            }

        } else {
            System.out.println("statefull.exit");
            return;
        }
    }

    private void findUsagesOfStatelessClassAndRemoveInstanceUsages(AnActionEvent e, PsiClass psiClass) {
        final CountDownLatch latch = new CountDownLatch(1);
        List<Usage> usages = new ArrayList<>();
        FindUsagesOptions findUsagesOptions = new JavaClassFindUsagesOptions(e.getProject());
        JavaFindUsagesHandler javaFindUsagesHandler = new JavaFindUsagesHandler(psiClass, JavaFindUsagesHandlerFactory.getInstance(e.getProject()));
        FindUsagesManager.startProcessUsages(javaFindUsagesHandler, new PsiElement[]{psiClass}, new PsiElement[0], usage -> {
            System.out.println("usage: " + usage.toString());
            usages.add(usage);
            return true;
        }, findUsagesOptions, () -> {
            System.out.println("find usages is done");
            latch.countDown();
        });

        try {
            latch.await();
            delete(e, psiClass, usages);
        } catch (InterruptedException e1) {
            e1.printStackTrace();
        }
    }

    public void delete(AnActionEvent e, PsiClass psiClass, List<Usage> usages) {
        Set<PsiElement> forDelete = usages.stream().filter(usage -> {
            if (!(usage instanceof UsageInfo2UsageAdapter)) {
                System.out.println(String.format("usage is not supported: %s", usage));
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
                System.out.println(String.format("WARN: context is empty for usage:%s, element: %s", usage, psiElement));
                parent = psiElement.getParent().getParent();
            }
            // consider static method call on 'psiClass' should be ignored
            if (parent instanceof PsiMethodCallExpression) {
                return null;
            }
            return parent;

        }).filter(Objects::nonNull).collect(Collectors.toSet());


        CustomSafeDeleteProcessor safeDeleteProcessor = CustomSafeDeleteProcessor.createInstance(e.getProject(), () -> {
                    System.out.println("Deleted !");
                }, forDelete.toArray(new PsiElement[forDelete.size()]), false,
                false, true);
        safeDeleteProcessor.run();

    }

}
