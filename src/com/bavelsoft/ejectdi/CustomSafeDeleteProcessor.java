package com.bavelsoft.ejectdi;

import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.listeners.RefactoringEventData;
import com.intellij.refactoring.safeDelete.NonCodeUsageSearchInfo;
import com.intellij.refactoring.safeDelete.SafeDeleteProcessorDelegate;
import com.intellij.refactoring.safeDelete.SafeDeleteUsageViewDescriptor;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteCustomUsageInfo;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceSimpleDeleteUsageInfo;
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceUsageInfo;
import com.intellij.refactoring.util.NonCodeSearchDescriptionLocation;
import com.intellij.refactoring.util.RefactoringUIUtil;
import com.intellij.refactoring.util.TextOccurrencesUtil;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageInfoFactory;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Created by dmgcodevil on 10/6/2017.
 */
public class CustomSafeDeleteProcessor extends BaseRefactoringProcessor {
    private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.safeDelete.CustomSafeDeleteProcessor");
    private final PsiElement[] myElements;
    private boolean mySearchInCommentsAndStrings;
    private boolean mySearchNonJava;
    private boolean myPreviewNonCodeUsages = true;

    private CustomSafeDeleteProcessor(Project project, @Nullable Runnable prepareSuccessfulCallback,
                                      PsiElement[] elementsToDelete, boolean isSearchInComments, boolean isSearchNonJava) {
        super(project, prepareSuccessfulCallback);
        myElements = elementsToDelete;
        mySearchInCommentsAndStrings = isSearchInComments;
        mySearchNonJava = isSearchNonJava;
    }

    @Override
    @NotNull
    protected UsageViewDescriptor createUsageViewDescriptor(@NotNull UsageInfo[] usages) {
        return new SafeDeleteUsageViewDescriptor(myElements);
    }

    private static boolean isInside(PsiElement place, PsiElement[] ancestors) {
        return isInside(place, Arrays.asList(ancestors));
    }

    private static boolean isInside(PsiElement place, Collection<? extends PsiElement> ancestors) {
        for (PsiElement element : ancestors) {
            if (isInside(place, element)) return true;
        }
        return false;
    }

    public static boolean isInside(PsiElement place, PsiElement ancestor) {
        if (ancestor instanceof PsiDirectoryContainer) {
            final PsiDirectory[] directories = ((PsiDirectoryContainer) ancestor).getDirectories(place.getResolveScope());
            for (PsiDirectory directory : directories) {
                if (isInside(place, directory)) return true;
            }
        }

        if (ancestor instanceof PsiFile) {
            for (PsiFile file : ((PsiFile) ancestor).getViewProvider().getAllFiles()) {
                if (PsiTreeUtil.isAncestor(file, place, false)) return true;
            }
        }

        boolean isAncestor = PsiTreeUtil.isAncestor(ancestor, place, false);
        if (!isAncestor && ancestor instanceof PsiNameIdentifierOwner) {
            final PsiElement nameIdentifier = ((PsiNameIdentifierOwner) ancestor).getNameIdentifier();
            if (nameIdentifier != null && !PsiTreeUtil.isAncestor(ancestor, nameIdentifier, true)) {
                isAncestor = PsiTreeUtil.isAncestor(nameIdentifier.getParent(), place, false);
            }
        }

        if (!isAncestor) {
            final InjectedLanguageManager injectedLanguageManager = InjectedLanguageManager.getInstance(place.getProject());
            PsiLanguageInjectionHost host = injectedLanguageManager.getInjectionHost(place);
            while (host != null) {
                if (PsiTreeUtil.isAncestor(ancestor, host, false)) {
                    isAncestor = true;
                    break;
                }
                host = injectedLanguageManager.getInjectionHost(host);
            }
        }
        return isAncestor;
    }

    @Override
    @NotNull
    protected UsageInfo[] findUsages() {
        List<UsageInfo> usages = Collections.synchronizedList(new ArrayList<UsageInfo>());
        for (PsiElement element : myElements) {
            boolean handled = false;
            for (SafeDeleteProcessorDelegate delegate : Extensions.getExtensions(SafeDeleteProcessorDelegate.EP_NAME)) {
                if (delegate.handlesElement(element)) {
                    final NonCodeUsageSearchInfo filter = delegate.findUsages(element, myElements, usages);
                    if (filter != null) {
                        for (PsiElement nonCodeUsageElement : filter.getElementsToSearch()) {
                            addNonCodeUsages(nonCodeUsageElement, usages, filter.getInsideDeletedCondition(), mySearchNonJava,
                                    mySearchInCommentsAndStrings);
                        }
                    }
                    handled = true;
                    break;
                }
            }
            if (!handled && element instanceof PsiNamedElement) {
                findGenericElementUsages(element, usages, myElements);
                addNonCodeUsages(element, usages, getDefaultInsideDeletedCondition(myElements), mySearchNonJava, mySearchInCommentsAndStrings);
            }
        }
        final UsageInfo[] result = usages.toArray(new UsageInfo[usages.size()]);
        return UsageViewUtil.removeDuplicatedUsages(result);
    }

    public static Condition<PsiElement> getDefaultInsideDeletedCondition(final PsiElement[] elements) {
        return usage -> !(usage instanceof PsiFile) && isInside(usage, elements);
    }

    public static void findGenericElementUsages(final PsiElement element, final List<UsageInfo> usages, final PsiElement[] allElementsToDelete) {
        ReferencesSearch.search(element).forEach(reference -> {
            final PsiElement refElement = reference.getElement();
            if (!isInside(refElement, allElementsToDelete)) {
                usages.add(new SafeDeleteReferenceSimpleDeleteUsageInfo(refElement, element, false));
            }
            return true;
        });
    }

    @Override
    protected boolean preprocessUsages(@NotNull Ref<UsageInfo[]> refUsages) {
        UsageInfo[] usages = refUsages.get();

        UsageInfo[] preprocessedUsages = usages;
        for (SafeDeleteProcessorDelegate delegate : Extensions.getExtensions(SafeDeleteProcessorDelegate.EP_NAME)) {
            preprocessedUsages = delegate.preprocessUsages(myProject, preprocessedUsages);
            if (preprocessedUsages == null) return false;
        }
        final UsageInfo[] filteredUsages = UsageViewUtil.removeDuplicatedUsages(preprocessedUsages);
        prepareSuccessful(); // dialog is always dismissed
        if (filteredUsages == null) {
            return false;
        }
        refUsages.set(filteredUsages);
        return true;
    }


    @Override
    protected void refreshElements(@NotNull PsiElement[] elements) {
        LOG.assertTrue(elements.length == myElements.length);
        System.arraycopy(elements, 0, myElements, 0, elements.length);
    }

    @Override
    protected boolean isPreviewUsages(@NotNull UsageInfo[] usages) {
        if (myPreviewNonCodeUsages && UsageViewUtil.reportNonRegularUsages(usages, myProject)) {
            return true;
        }

        return super.isPreviewUsages(filterToBeDeleted(usages));
    }

    private static UsageInfo[] filterToBeDeleted(UsageInfo[] infos) {
        ArrayList<UsageInfo> list = new ArrayList<>();
        for (UsageInfo info : infos) {
            if (!(info instanceof SafeDeleteReferenceUsageInfo) || ((SafeDeleteReferenceUsageInfo) info).isSafeDelete()) {
                list.add(info);
            }
        }
        return list.toArray(new UsageInfo[list.size()]);
    }

    @Nullable
    @Override
    protected RefactoringEventData getBeforeData() {
        final RefactoringEventData beforeData = new RefactoringEventData();
        beforeData.addElements(myElements);
        return beforeData;
    }

    @Nullable
    @Override
    protected String getRefactoringId() {
        return "refactoring.safeDelete";
    }

    @Override
    protected void performRefactoring(@NotNull UsageInfo[] usages) {
        try {
            for (UsageInfo usage : usages) {
                if (usage instanceof SafeDeleteCustomUsageInfo) {
                    ((SafeDeleteCustomUsageInfo) usage).performRefactoring();
                }
            }

            for (PsiElement element : myElements) {
                for (SafeDeleteProcessorDelegate delegate : Extensions.getExtensions(SafeDeleteProcessorDelegate.EP_NAME)) {
                    if (delegate.handlesElement(element)) {
                        delegate.prepareForDeletion(element);
                    }
                }

                element.delete();
            }
        } catch (IncorrectOperationException e) {
            RefactoringUIUtil.processIncorrectOperation(myProject, e);
        }
    }

    private String calcCommandName() {
        return RefactoringBundle.message("safe.delete.command", RefactoringUIUtil.calculatePsiElementDescriptionList(myElements));
    }

    private String myCachedCommandName = null;

    @Override
    protected String getCommandName() {
        if (myCachedCommandName == null) {
            myCachedCommandName = calcCommandName();
        }
        return myCachedCommandName;
    }


    public static void addNonCodeUsages(final PsiElement element,
                                        List<UsageInfo> usages,
                                        @Nullable final Condition<PsiElement> insideElements,
                                        boolean searchNonJava,
                                        boolean searchInCommentsAndStrings) {
        UsageInfoFactory nonCodeUsageFactory = new UsageInfoFactory() {
            @Override
            public UsageInfo createUsageInfo(@NotNull PsiElement usage, int startOffset, int endOffset) {
                if (insideElements != null && insideElements.value(usage)) {
                    return null;
                }
                return new SafeDeleteReferenceSimpleDeleteUsageInfo(usage, element, startOffset, endOffset, true, false);
            }
        };
        if (searchInCommentsAndStrings) {
            String stringToSearch = ElementDescriptionUtil.getElementDescription(element, NonCodeSearchDescriptionLocation.STRINGS_AND_COMMENTS);
            TextOccurrencesUtil.addUsagesInStringsAndComments(element, stringToSearch, usages, nonCodeUsageFactory);
        }
        if (searchNonJava) {
            String stringToSearch = ElementDescriptionUtil.getElementDescription(element, NonCodeSearchDescriptionLocation.NON_JAVA);
            TextOccurrencesUtil.addTextOccurences(element, stringToSearch, GlobalSearchScope.projectScope(element.getProject()), usages, nonCodeUsageFactory);
        }
    }

    @Override
    protected boolean isToBeChanged(@NotNull UsageInfo usageInfo) {
        if (usageInfo instanceof SafeDeleteReferenceUsageInfo) {
            return ((SafeDeleteReferenceUsageInfo) usageInfo).isSafeDelete() && super.isToBeChanged(usageInfo);
        }
        return super.isToBeChanged(usageInfo);
    }

    public static CustomSafeDeleteProcessor createInstance(Project project, @Nullable Runnable prepareSuccessfulCallback,
                                                           PsiElement[] elementsToDelete, boolean isSearchInComments, boolean isSearchNonJava) {
        return new CustomSafeDeleteProcessor(project, prepareSuccessfulCallback, elementsToDelete, isSearchInComments, isSearchNonJava);
    }

    public static CustomSafeDeleteProcessor createInstance(Project project, @Nullable Runnable prepareSuccessfulCallBack,
                                                           PsiElement[] elementsToDelete, boolean isSearchInComments, boolean isSearchNonJava,
                                                           boolean askForAccessors) {
        ArrayList<PsiElement> elements = new ArrayList<>(Arrays.asList(elementsToDelete));
        HashSet<PsiElement> elementsToDeleteSet = new HashSet<>(Arrays.asList(elementsToDelete));

        for (PsiElement psiElement : elementsToDelete) {
            for (SafeDeleteProcessorDelegate delegate : Extensions.getExtensions(SafeDeleteProcessorDelegate.EP_NAME)) {
                if (delegate.handlesElement(psiElement)) {
                    Collection<PsiElement> addedElements = delegate.getAdditionalElementsToDelete(psiElement, elementsToDeleteSet, askForAccessors);
                    if (addedElements != null) {
                        elements.addAll(addedElements);
                    }
                    break;
                }
            }
        }

        return new CustomSafeDeleteProcessor(project, prepareSuccessfulCallBack,
                PsiUtilCore.toPsiElementArray(elements),
                isSearchInComments, isSearchNonJava);
    }

    @Override
    protected boolean skipNonCodeUsages() {
        return true;
    }
}
