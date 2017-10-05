package com.bavelsoft.ejectdi;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.psi.*;

import java.util.Arrays;
import java.util.Optional;

public class ConvertToStaticAction extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        System.out.println("actionPerformed");
        PsiFile psiFile = e.getData(LangDataKeys.PSI_FILE);
        if(psiFile.isDirectory()) {
            System.out.println("directory not supported yet");
            return;
        }
        if(!(psiFile.getFileType() instanceof JavaFileType)) {
            System.out.println("only java source classes are supported");
            return;
        }
        Optional<PsiElement> psiClassOpt = Arrays.stream(psiFile.getChildren()).filter(psiElement -> psiElement instanceof PsiClass).findFirst();

        if(!psiClassOpt.isPresent()) {
            System.out.println("only classes supported, not enums or interfaces");
        }
        PsiClass psiClass = (PsiClass)psiClassOpt.get();

        boolean statelessElement =  psiClass.getAllFields().length == 0;
        if (statelessElement) {
            System.out.println("stateless");
        } else {
            System.out.println("statefull.exit");
            return;
        }
//        psiFile.accept(new PsiRecursiveElementWalkingVisitor() {
//            @Override
//            public void visitElement(PsiElement element) {
//                System.out.println(element);
//                super.visitElement(element);
//            }
//        });
        //System.out.println("my action");
        // TODO: insert action logic here
    }


}
