package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.lang.ecmascript6.psi.ES6FromClause
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.refactoring.ES6ModuleMemberInfo
import com.intellij.lang.ecmascript6.refactoring.ES6MoveModuleMembersProcessor
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.ecma6.TypeScriptCompileTimeType
import com.intellij.lang.javascript.psi.ecmal4.JSQualifiedNamedElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.usageView.UsageInfo
import com.intellij.util.containers.MultiMap
import java.io.File
import java.nio.file.Path

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
        val fixups = { usages: Array<UsageInfo> -> importFixups(member, source, targetPath, usages) }
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
     * Snapshots the files the move touches, then returns the edit that repairs what
     * ES6MoveModuleMembersProcessor leaves behind in them:
     * - a moved type alias or interface is imported as a plain specifier (`import { X }`), which is TS1484
     *   under `verbatimModuleSyntax` whenever the IDE does not read that setting from tsconfig. Every plain
     *   import of a type-only member gets `type`, which is valid in every TypeScript configuration;
     * - a rewritten import uses the IDE's preferred path style, not the importer's: `./x` becomes `@/a/x`
     *   or the reverse. The importer's original style (relative, or the same alias) is restored;
     * - removing the old import leaves a doubled blank line in the import header;
     * - appending to the target drops its final newline.
     */
    private fun importFixups(member: JSQualifiedNamedElement, source: JSFile, targetPath: String, usages: Array<UsageInfo>): () -> Unit {
        val project = member.project
        val name = member.name!!
        val sourcePath = source.virtualFile.path
        val documents = PsiDocumentManager.getInstance(project)
        val files = (usages.mapNotNull { it.element?.containingFile?.virtualFile } + source.virtualFile +
            listOfNotNull(LocalFileSystem.getInstance().findFileByPath(targetPath))).toSet()
        val before = files.associateWith { file ->
            val psi = PsiManager.getInstance(project).findFile(file)
            Snapshot(
                text = psi?.let(documents::getDocument)?.text.orEmpty(),
                header = psi?.let(::importHeader).orEmpty(),
                specifier = psi?.let { importsOf(it, name, sourcePath).firstOrNull()?.fromClause?.let(::specifierText) }
            )
        }
        val sourceEndsWithNewline = before[source.virtualFile]?.text?.endsWith("\n") ?: true
        return {
            val target = LocalFileSystem.getInstance().findFileByPath(targetPath)
            for (file in (files + listOfNotNull(target)).toSet()) {
                val psi = PsiManager.getInstance(project).findFile(file) ?: continue
                val document = documents.getDocument(psi) ?: continue
                val snapshot = before[file]
                val edits = mutableListOf<Pair<TextRange, String>>()
                for (declaration in importsOf(psi, name, targetPath)) {
                    if (member is TypeScriptCompileTimeType) {
                        declaration.importSpecifiers.filter { it.referenceName == name && !isTypeOnly(it) }
                            .forEach { edits += TextRange.from(it.textRange.startOffset, 0) to "type " }
                    }
                    // Only a declaration that imports nothing else is the processor's own; a merged one keeps its path.
                    val original = snapshot?.specifier ?: continue
                    if (declaration.importSpecifiers.size + declaration.importedBindings.size != 1) continue
                    val fromClause = declaration.fromClause ?: continue
                    val current = specifierText(fromClause) ?: continue
                    val wanted = specifierInStyleOf(original, sourcePath, targetPath, file.parent.path) ?: continue
                    if (wanted == current) continue
                    val offset = fromClause.text.indexOf(current)
                    if (offset >= 0) edits += TextRange.from(fromClause.textRange.startOffset + offset, current.length) to wanted
                }
                edits.sortedByDescending { it.first.startOffset }.forEach { (range, text) -> document.replaceString(range.startOffset, range.endOffset, text) }
                documents.commitDocument(document)

                // Collapse blank-line runs the move left in the import header, unless the file already had them.
                if (snapshot == null || !snapshot.header.contains("\n\n\n")) {
                    val header = importHeader(psi)
                    val collapsed = header.replace(Regex("\n{3,}"), "\n\n")
                    if (collapsed != header) document.replaceString(0, header.length, collapsed)
                }
                val endsWithNewline = snapshot?.text?.endsWith("\n") ?: sourceEndsWithNewline
                if (endsWithNewline && document.textLength > 0 && !document.charsSequence.endsWith("\n")) {
                    document.insertString(document.textLength, "\n")
                }
                documents.commitDocument(document)
            }
        }
    }

    private class Snapshot(val text: String, val header: String, val specifier: String?)

    /** Import declarations in [file] that import [name] from the file at [modulePath]. */
    private fun importsOf(file: PsiFile, name: String, modulePath: String): List<ES6ImportDeclaration> =
        SyntaxTraverser.psiTraverser(file).filter(ES6ImportDeclaration::class.java)
            .filter { declaration -> declaration.importSpecifiers.any { it.referenceName == name } }
            .filter { declaration ->
                declaration.fromClause?.resolveReferencedElements().orEmpty()
                    .any { (it as? PsiFile ?: it.containingFile)?.virtualFile?.path == modulePath }
            }
            .toList()

    /** File text from the start through the whitespace after the last top-level import. */
    private fun importHeader(file: PsiFile): String {
        val last = file.children.lastOrNull { it is ES6ImportDeclaration } ?: return ""
        var end = last.textRange.endOffset
        val text = file.text
        while (end < text.length && text[end].isWhitespace()) end++
        return text.substring(0, end)
    }

    private fun specifierText(fromClause: ES6FromClause): String? =
        fromClause.referenceText?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")

    /**
     * The specifier for [targetPath] written the way [original] referred to [sourcePath]: relative stays
     * relative, and an alias keeps its prefix (inferred from the path segments [original] shares with
     * [sourcePath], so no tsconfig parsing is needed). Extension use follows [original]. Null when the
     * alias does not cover the target.
     */
    internal fun specifierInStyleOf(original: String, sourcePath: String, targetPath: String, importerDir: String): String? {
        val keepExtension = File(original).extension.isNotEmpty() && File(original).extension == File(sourcePath).extension
        fun spelled(path: String) = if (keepExtension) path else path.substring(0, path.length - File(path).extension.length).removeSuffix(".")
        if (original.startsWith(".")) {
            val relative = Path.of(importerDir).relativize(Path.of(targetPath)).toString().replace(File.separatorChar, '/')
            return spelled(if (relative.startsWith("../")) relative else "./$relative")
        }
        val specSegments = spelled(original).split('/')
        val sourceSegments = spelled(sourcePath).split('/')
        val shared = specSegments.reversed().zip(sourceSegments.reversed()).takeWhile { (a, b) -> a == b }.size
        if (shared == 0 || shared == specSegments.size) return null
        val alias = specSegments.dropLast(shared).joinToString("/")
        val base = sourceSegments.dropLast(shared).joinToString("/") + "/"
        if (!targetPath.startsWith(base)) return null
        return "$alias/${spelled(targetPath.removePrefix(base))}"
    }

    private fun isTypeOnly(spec: ES6ImportSpecifier): Boolean =
        spec.specifierKind == ES6ImportExportSpecifier.ImportExportSpecifierKind.IMPORT_TYPE ||
            spec.declaration?.importExportPrefixKind == ES6ImportExportDeclaration.ImportExportPrefixKind.IMPORT_TYPE

    private fun fail(message: String): Result<MoveMemberPlan> = Result.failure(IllegalArgumentException(message))
}
