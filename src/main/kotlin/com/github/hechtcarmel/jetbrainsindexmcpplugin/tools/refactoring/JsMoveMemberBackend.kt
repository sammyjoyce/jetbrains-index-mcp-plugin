package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.refactoring.ES6ModuleMemberInfo
import com.intellij.lang.ecmascript6.refactoring.ES6MoveModuleMembersProcessor
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.ecma6.TypeScriptCompileTimeType
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
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
        val fixups = { usages: Array<UsageInfo> -> typeOnlyImports(member, source, targetPath, usages) }
        return Result.success(MoveMemberPlan("es6_move_module_members", member, processor, fixups) { usages ->
            // The processor computes conflicts privately and only exposes them through its dialog.
            val getConflicts = ES6MoveModuleMembersProcessor::class.java
                .getDeclaredMethod("getConflicts", Array<UsageInfo>::class.java)
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val conflicts = ReadAction.compute<Any?, RuntimeException> { invokeUnwrapped(getConflicts, processor, usages) } as MultiMap<PsiElement, String>
            ConflictMessages.sanitizeAll(conflicts.values())
        })
    }

    /**
     * The processor imports a moved type alias or interface as a plain specifier (`import { X }`), both when it
     * merges into an existing value import of the target and when it re-imports into the source file. Under
     * `verbatimModuleSyntax` that is TS1484 whenever the IDE does not read the setting from tsconfig, so every
     * plain import of a type-only member gets `type`, which is valid in every TypeScript configuration.
     */
    private fun typeOnlyImports(member: JSQualifiedNamedElement, source: JSFile, targetPath: String, usages: Array<UsageInfo>): () -> Unit {
        if (member !is TypeScriptCompileTimeType) return {}
        val project = member.project
        val name = member.name!!
        val files = (usages.mapNotNull { it.element?.containingFile?.virtualFile } + source.virtualFile).toSet()
        return {
            val documents = PsiDocumentManager.getInstance(project)
            for (file in files) {
                val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                val document = documents.getDocument(psi) ?: continue
                SyntaxTraverser.psiTraverser(psi).filter(ES6ImportSpecifier::class.java)
                    .filter { it.referenceName == name && !isTypeOnly(it) }
                    .filter { spec -> spec.multiResolve(false).any { it.element?.containingFile?.virtualFile?.path == targetPath } }
                    .map { it.textRange.startOffset }
                    .sortedDescending()
                    .forEach { document.insertString(it, "type ") }
                documents.commitDocument(document)
            }
        }
    }

    private fun isTypeOnly(spec: ES6ImportSpecifier): Boolean =
        spec.specifierKind == ES6ImportExportSpecifier.ImportExportSpecifierKind.IMPORT_TYPE ||
            spec.declaration?.importExportPrefixKind == ES6ImportExportDeclaration.ImportExportPrefixKind.IMPORT_TYPE

    private fun fail(message: String): Result<MoveMemberPlan> = Result.failure(IllegalArgumentException(message))
}
