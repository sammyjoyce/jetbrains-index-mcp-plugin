package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.move.moveMembers.MoveMembersOptions
import com.intellij.refactoring.move.moveMembers.MoveMembersProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.VisibilityUtil
import com.intellij.util.containers.MultiMap

/**
 * Java backend of `ide_move_member`: the platform's Move Static Members refactoring.
 *
 * Kept out of [MoveMemberTool] so Java PSI types are only loaded once a Java target was
 * selected; the tool itself is registered in every IDE.
 */
internal object JavaMoveMemberBackend {

    fun plan(project: Project, element: PsiElement, targetFile: PsiFile?): Result<MoveMemberPlan> {
        val member = element as? PsiMember
        val name = (member as? PsiNamedElement)?.name
        if (member == null || name == null) {
            return fail("Java target is not a field, method or class.")
        }
        val sourceClass = member.containingClass
            ?: return fail("'$name' is a top-level Java class; move its file with ide_move_file instead.")
        if (!member.hasModifierProperty(PsiModifier.STATIC)) {
            return fail("Only static Java members can be moved; '$name' is an instance member of '${sourceClass.name}'.")
        }
        val javaFile = targetFile as? PsiJavaFile
            ?: return fail("Java members move into an existing class: targetFile must be an existing .java file.")
        val targetClass = primaryClass(javaFile)
            ?: return fail("'${javaFile.name}' has no top-level class to receive '$name'.")
        if (targetClass == sourceClass) return fail("'$name' is already declared in '${targetClass.name}'.")
        val targetClassName = targetClass.qualifiedName
            ?: return fail("Class '${targetClass.name}' has no qualified name.")

        val processor = HeadlessMoveMembersProcessor(project, member, targetClassName)
        return Result.success(MoveMemberPlan("java_move_static_members", member, processor, conflicts = processor::collectConflicts))
    }

    private fun primaryClass(file: PsiJavaFile): PsiClass? =
        file.classes.firstOrNull { it.name == file.virtualFile?.nameWithoutExtension } ?: file.classes.singleOrNull()

    private fun fail(message: String): Result<MoveMemberPlan> = Result.failure(IllegalArgumentException(message))

    /** Captures conflicts instead of showing the conflicts dialog. */
    private class HeadlessMoveMembersProcessor(
        project: Project,
        member: PsiMember,
        targetClassName: String
    ) : MoveMembersProcessor(project, object : MoveMembersOptions {
        override fun getSelectedMembers(): Array<PsiMember> = arrayOf(member)
        override fun getTargetClassName(): String = targetClassName
        // The interactive dialog's default: widen visibility only where a usage needs it.
        override fun getMemberVisibility(): String = VisibilityUtil.ESCALATE_VISIBILITY
        override fun makeEnumConstant(): Boolean = false
    }) {
        private val captured = mutableListOf<String>()

        fun collectConflicts(usages: Array<UsageInfo>): List<String> {
            captured.clear()
            ReadAction.run<RuntimeException> { preprocessUsages(Ref(usages)) }
            return captured.toList()
        }

        override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
            captured.addAll(ConflictMessages.sanitizeAll(conflicts.values()))
            return true
        }
    }
}
