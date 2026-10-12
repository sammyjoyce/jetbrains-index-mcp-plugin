package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.usageView.UsageInfo
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A language backend's ready-to-run move: the IDE processor plus a non-mutating conflict probe.
 * [conflicts] runs on the EDT, like the processor's own `preprocessUsages`, after its usage search.
 * [fixups] reads the usages on the EDT before the move and returns an edit to run in a write command after it.
 */
internal class MoveMemberPlan(
    val backend: String,
    val element: PsiElement,
    val processor: BaseRefactoringProcessor,
    val fixups: (Array<UsageInfo>) -> () -> Unit = { {} },
    val conflicts: (Array<UsageInfo>) -> List<String>
)

/** Reflective call that rethrows the target's own exception instead of the reflection wrapper. */
internal fun invokeUnwrapped(method: Method, receiver: Any?, vararg args: Any?): Any? = try {
    method.invoke(receiver, *args)
} catch (e: InvocationTargetException) {
    throw e.cause ?: e
}

/**
 * Moves a top-level or static declaration into another file with the IDE's own Move refactoring,
 * so every reference and import is rewritten. One backend per language:
 * Java [JavaMoveMemberBackend], Kotlin [KotlinMoveMemberBackend], JS/TS [JsMoveMemberBackend].
 */
class MoveMemberTool : AbstractRefactoringTool() {

    override val name = ToolNames.MOVE_MEMBER

    override val description = """
        Move a top-level or static declaration to another file with the IDE's Move refactoring; references and imports are rewritten.
        Java: static members into an existing class file. Kotlin: top-level declarations into a .kt file. JS/TS: top-level module members. Kotlin/JS/TS create targetFile and missing directories. Other languages are unsupported.
        Target via symbolId, language+symbol, file+line+column, or nested target. dryRun=true previews usages/conflicts/canApply without editing; apply reports conflicts as warnings.

        Example: {"file":"src/util.ts","line":3,"column":17,"targetFile":"src/strings.ts"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .languageAndSymbol(required = false)
        .file(required = false, description = "File containing the declaration; use with line and column.")
        .lineAndColumn(required = false)
        .stringProperty(
            TARGET_FILE,
            "Destination file relative to project root. Kotlin/JS/TS create it and missing directories. REQUIRED.",
            required = true
        )
        .booleanProperty(ParamNames.DRY_RUN, "Preview without modifying files. Default: false.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val startedAtNanos = System.nanoTime()
        val dryRun = arguments[ParamNames.DRY_RUN]?.jsonPrimitive?.booleanOrNull == true
        val targetPath = requiredStringArg(arguments, TARGET_FILE).getOrElse {
            return createErrorResult(it.message ?: "Missing required parameter: $TARGET_FILE")
        }
        requireSmartMode(project)
        if (!dryRun) syncProjectForRefactoring(project)

        // Missing destination directories are created by the IDE refactoring itself, inside its
        // command, so a dry run or an aborted move leaves no empty directories behind.
        val (targetDir, missingDirs) = resolveDestinationDirectory(project, targetPath).getOrElse {
            return createErrorResult(it.message ?: "Invalid $TARGET_FILE: $targetPath")
        }
        val targetName = File(targetPath).name
        val existingTarget = if (missingDirs.isEmpty()) targetDir.findChild(targetName) else null
        if (!dryRun) existingTarget?.let { ensureWritable(it)?.let { error -> return error } }
        val symbolId = optionalStringArg(arguments, ParamNames.SYMBOL_ID)

        val planned = suspendingReadAction { plan(project, arguments, symbolId, targetDir, missingDirs, targetName, existingTarget) }
        val plan = planned.getOrElse { return createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL) }

        val (sourceFile, memberName, memberClass) = suspendingReadAction {
            Triple(plan.element.containingFile.virtualFile, (plan.element as PsiNamedElement).name!!, plan.element.javaClass)
        }
        if (!dryRun) ensureWritable(sourceFile)?.let { return it }

        val usages = RefactoringScopeGuard.computeUsagesOffEdtStrict(project) {
            @Suppress("UNCHECKED_CAST")
            invokeUnwrapped(FIND_USAGES, plan.processor) as Array<UsageInfo>
        }
        val conflicts = edtAction { plan.conflicts(usages) }
        val readOnly = suspendingReadAction { RefactoringScopeGuard.readOnlyFilesIn(project, usages) }
        val sourcePath = suspendingReadAction { getRelativePath(project, sourceFile) }
        val targetRelative = suspendingReadAction {
            existingTarget?.let { getRelativePath(project, it) }
                ?: (listOf(ProjectUtils.getToolFilePath(project, targetDir)).filterNot { it.isEmpty() || it == "." } + missingDirs + targetName)
                    .joinToString("/")
        }

        if (dryRun) {
            val (target, affected) = suspendingReadAction {
                resolvedSymbolInfo(project, plan.element, symbolId) to
                    (usages.mapNotNull { it.virtualFile?.let { f -> getRelativePath(project, f) } } + sourcePath + targetRelative)
            }
            return createJsonResult(
                refactoringPreview(
                    canApply = conflicts.isEmpty() && readOnly.isEmpty(),
                    target = target,
                    plannedChange = buildJsonObject {
                        put("operation", "move_member")
                        put("backend", plan.backend)
                        put("from", sourcePath)
                        put("to", targetRelative)
                        put("createsFile", existingTarget == null)
                        if (missingDirs.isNotEmpty()) put("createsDirectory", (missingDirs).joinToString("/"))
                    },
                    affectedFiles = affected,
                    usageCount = usages.size,
                    conflictCount = conflicts.size,
                    warnings = conflicts + listOfNotNull(readOnly.takeIf { it.isNotEmpty() }?.let(RefactoringScopeGuard::blockedMessage)),
                    startedAtNanos = startedAtNanos
                )
            )
        }
        if (readOnly.isNotEmpty()) return createErrorResult(RefactoringScopeGuard.blockedMessage(readOnly))

        var error: String? = null
        var notSaved: List<String> = emptyList()
        val affected = linkedSetOf(sourcePath)
        edtAction {
            val fileDocumentManager = FileDocumentManager.getInstance()
            val unsavedBefore = fileDocumentManager.unsavedDocuments.toSet()
            try {
                val fixup = plan.fixups(usages)
                // Kotlin's retargetUsages reads the thread's progress indicator without a null check,
                // and headless write actions install none (same quirk as MoveFileTool).
                ProgressManager.getInstance().runProcess({ plan.processor.run() }, EmptyProgressIndicator())
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                WriteCommandAction.writeCommandAction(project).withName("Move member: fix imports").run<Throwable>(fixup)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                fileDocumentManager.unsavedDocuments.filterNot { it in unsavedBefore }
                    .mapNotNullTo(affected) { doc -> fileDocumentManager.getFile(doc)?.let { getRelativePath(project, it) } }
                notSaved = saveChangedDocuments(project, unsavedBefore)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
        }
        error?.let { return createErrorResult("Move failed: $it", ToolNames.DIAGNOSTICS) }
        if (notSaved.isNotEmpty()) return createErrorResult(changesNotSavedMessage(notSaved))

        val movedFile = targetDir.findFileByRelativePath((missingDirs + targetName).joinToString("/"))
        val moved = movedFile?.let { file ->
            suspendingReadAction {
                PsiManager.getInstance(project).findFile(file)?.let { findDeclaration(it, memberName, memberClass) }
                    ?.let { resolvedSymbolInfo(project, it) }
            }
        } ?: return createErrorResult(
            "Move of '$memberName' did not apply: '$targetRelative' does not contain it afterwards. " +
                "Check ${ToolNames.DIAGNOSTICS} on '$sourcePath'.",
            ToolNames.DIAGNOSTICS
        )
        affected.add(targetRelative)

        return createJsonResult(
            RefactoringResult(
                success = true,
                affectedFiles = affected.toList(),
                changesCount = affected.size,
                message = "Moved '$memberName' from '$sourcePath' to '$targetRelative' (${plan.backend})",
                warnings = conflicts.takeIf { it.isNotEmpty() },
                updatedSymbol = moved,
                invalidatedSymbolId = symbolId
            )
        )
    }

    private fun plan(
        project: Project,
        arguments: JsonObject,
        symbolId: String?,
        targetDir: VirtualFile,
        missingDirs: List<String>,
        targetName: String,
        existingTarget: VirtualFile?
    ): Result<MoveMemberPlan> {
        val resolved = resolveElementFromArguments(project, arguments, allowSymbolId = true).getOrElse { return Result.failure(it) }
        val element = (if (arguments[ParamNames.LINE] != null) PsiUtils.resolveTargetElement(resolved) else resolved)
            ?.let(PsiUtils::resolveNavigationTarget)
            ?.takeIf { it is PsiNamedElement && it !is PsiFile && it.containingFile?.virtualFile != null }
        // A reference resolves into the declaration's language, so dispatch on the declaration when there is one.
        val language = (element ?: resolved).language
        if (language.id != "JAVA" && language.id != "kotlin" && !language.isKindOf("JavaScript")) {
            return Result.failure(
                IllegalArgumentException(
                    "${ToolNames.MOVE_MEMBER} has no move backend for ${language.displayName}. " +
                        "Supported: Java (static members), Kotlin (top-level declarations), JavaScript/TypeScript (top-level module members)."
                )
            )
        }
        if (element == null) {
            return Result.failure(
                IllegalArgumentException(symbolId?.let(ErrorMessages::symbolIdExpired) ?: "Target does not identify a movable declaration")
            )
        }
        val psiManager = PsiManager.getInstance(project)
        val targetPsiFile = existingTarget?.let(psiManager::findFile)
        return when (language.id) {
            "JAVA" -> JavaMoveMemberBackend.plan(project, element, targetPsiFile)
            "kotlin" -> KotlinMoveMemberBackend.plan(
                project, element, psiManager.findDirectory(targetDir)!!, missingDirs, targetName, targetPsiFile
            )
            else -> JsMoveMemberBackend.plan(element, (listOf(targetDir.path) + missingDirs + targetName).joinToString("/"))
        }
    }

    /**
     * Splits [targetPath]'s directory into its nearest existing project directory and the
     * directory names below it that do not exist yet.
     */
    private fun resolveDestinationDirectory(project: Project, targetPath: String): Result<Pair<VirtualFile, List<String>>> {
        val missing = ArrayDeque<String>()
        var dir: File? = File(targetPath).parentFile
        while (dir != null) {
            val existing = resolveFile(project, dir.path)
            if (existing != null) {
                if (!existing.isDirectory) return Result.failure(IllegalArgumentException("'${dir.path}' in $TARGET_FILE is a file, not a directory."))
                return checkedMissing(existing, missing)
            }
            missing.addFirst(dir.name)
            dir = dir.parentFile
        }
        if (File(targetPath).isAbsolute) {
            return Result.failure(IllegalArgumentException("$TARGET_FILE '$targetPath' is outside the project."))
        }
        val base = project.basePath?.let(LocalFileSystem.getInstance()::findFileByPath)
            ?: return Result.failure(IllegalArgumentException("Project has no base directory."))
        return checkedMissing(base, missing)
    }

    private fun checkedMissing(dir: VirtualFile, missing: List<String>): Result<Pair<VirtualFile, List<String>>> =
        if (missing.any { it == "." || it == ".." || it.isEmpty() }) {
            Result.failure(IllegalArgumentException("$TARGET_FILE must not navigate with '.' or '..' through directories that do not exist."))
        } else {
            Result.success(dir to missing)
        }

    /** The moved declaration is a new PSI element; find it by name and PSI type in the target file. */
    private fun findDeclaration(file: PsiFile, name: String, type: Class<*>): PsiElement? =
        SyntaxTraverser.psiTraverser(file).firstOrNull { type.isInstance(it) && (it as PsiNamedElement).name == name }

    private companion object {
        const val TARGET_FILE = "targetFile"
        val FIND_USAGES: Method = BaseRefactoringProcessor::class.java.getDeclaredMethod("findUsages").apply { isAccessible = true }
    }
}
