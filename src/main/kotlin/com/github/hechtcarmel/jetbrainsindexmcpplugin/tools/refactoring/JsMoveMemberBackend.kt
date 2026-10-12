package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.lang.ecmascript6.refactoring.ES6ModuleMemberInfo
import com.intellij.lang.ecmascript6.refactoring.ES6MoveModuleMembersProcessor
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap

/**
 * JavaScript/TypeScript backend of `ide_move_member`: WebStorm's "Move module members"
 * refactoring, which rewrites imports/exports in every importer.
 *
 * Kept out of [MoveMemberTool] so JavaScript PSI types are only loaded for a JS/TS target.
 */
internal object JsMoveMemberBackend {

    fun plan(element: PsiElement, targetPath: String): Result<MoveMemberPlan> {
        val member = element as? JSQualifiedNamedElement
        val name = member?.name
            ?: return fail("JavaScript/TypeScript target is not a named module member.")
        val source = member.containingFile as? JSFile
            ?: return fail("'$name' is not declared in a JavaScript/TypeScript file.")
        if (ES6ModuleMemberInfo.getTopLevelDeclaration(member).parent != source) {
            return fail("Only top-level module members can be moved; '$name' is nested.")
        }
        if (source.virtualFile?.path == targetPath) return fail("'$name' is already declared in the target file.")

        // escalateVisibility=true is the dialog's default: export the member where an importer needs it.
        val processor = ES6MoveModuleMembersProcessor(listOf(member), targetPath, source, true, null)
        return Result.success(MoveMemberPlan("es6_move_module_members", member, processor) { usages ->
            // The processor computes conflicts privately and only exposes them through its dialog.
            val getConflicts = ES6MoveModuleMembersProcessor::class.java
                .getDeclaredMethod("getConflicts", Array<UsageInfo>::class.java)
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val conflicts = ReadAction.compute<Any?, RuntimeException> { invokeUnwrapped(getConflicts, processor, usages) } as MultiMap<PsiElement, String>
            ConflictMessages.sanitizeAll(conflicts.values())
        })
    }

    private fun fail(message: String): Result<MoveMemberPlan> = Result.failure(IllegalArgumentException(message))
}
