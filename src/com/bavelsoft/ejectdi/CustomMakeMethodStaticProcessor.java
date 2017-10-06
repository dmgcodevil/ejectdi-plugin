package com.bavelsoft.ejectdi;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.makeStatic.MakeMethodStaticProcessor;
import com.intellij.refactoring.makeStatic.Settings;
import com.intellij.usageView.UsageInfo;

import java.util.Set;

/**
 * Created by dmgcodevil on 10/6/2017.
 */
public class CustomMakeMethodStaticProcessor extends MakeMethodStaticProcessor {

    public CustomMakeMethodStaticProcessor(Project project, PsiMethod method, Settings settings) {
        super(project, method, settings);
    }

    @Override
    protected boolean findAdditionalMembers(Set<UsageInfo> toMakeStatic) {
        return true;
    }
}
