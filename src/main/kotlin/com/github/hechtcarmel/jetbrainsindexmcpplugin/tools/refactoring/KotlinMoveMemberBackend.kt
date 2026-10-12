package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.util.containers.MultiMap

/**
 * Kotlin backend of `ide_move_member`: the K2 "Move declarations" refactoring.
 *
 * The Kotlin plugin is not on the compile classpath, and its move refactoring lives in an
 * internal content module (`intellij.kotlin.refactorings.move`) whose classes our plugin's
 * class loader cannot see. The module registers `K2MoveHandler` as a move handler, so its
 * class loader is reached through that extension. Absent in K1 mode, which is unsupported.
 */
internal object KotlinMoveMemberBackend {
    private const val MOVE_PACKAGE = "org.jetbrains.kotlin.idea.k2.refactoring.move"

    fun plan(
        project: Project,
        element: PsiElement,
        targetDirectory: PsiDirectory,
        missingDirs: List<String>,
        targetFileName: String,
        targetFile: PsiFile?
    ): Result<MoveMemberPlan> {
        val name = (element as? PsiNamedElement)?.name
        val ktNamedDeclaration = Class.forName("org.jetbrains.kotlin.psi.KtNamedDeclaration")
        val ktFile = Class.forName("org.jetbrains.kotlin.psi.KtFile")
        if (name == null || !ktNamedDeclaration.isInstance(element) || !ktFile.isInstance(element.parent)) {
            return fail("Only top-level Kotlin declarations (functions, properties, classes, objects, type aliases) can be moved.")
        }
        if (!targetFileName.endsWith(".kt")) return fail("Kotlin declarations move into a .kt file; got '$targetFileName'.")
        if (targetFile != null && !ktFile.isInstance(targetFile)) return fail("'$targetFileName' is not a Kotlin file.")
        if (targetFile == element.containingFile) return fail("'$name' is already declared in '$targetFileName'.")
        missingDirs.firstOrNull { dir -> !dir.first().isJavaIdentifierStart() || !dir.all(Char::isJavaIdentifierPart) }?.let {
            return fail("New directory '$it' would become a Kotlin package segment, but it is not a valid identifier.")
        }

        val loader = MoveHandlerDelegate.EP_NAME.extensionList
            .firstOrNull { it.javaClass.name == "$MOVE_PACKAGE.K2MoveHandler" }
            ?.javaClass?.classLoader
            ?: return fail("Kotlin's K2 move refactoring is unavailable in this IDE (K1 mode is not supported).")

        val fqNameClass = Class.forName("org.jetbrains.kotlin.name.FqName", true, loader)
        // A new directory below targetDirectory becomes a subpackage of targetDirectory's package.
        val basePackage = ProjectRootManager.getInstance(project).fileIndex.getPackageNameByDirectory(targetDirectory.virtualFile).orEmpty()
        val packageFqName = targetFile?.javaClass?.getMethod("getPackageFqName")?.invoke(targetFile)
            ?: fqNameClass.getConstructor(String::class.java).newInstance(
                (listOf(basePackage).filter { it.isNotEmpty() } + missingDirs).joinToString(".")
            )

        val companion = Class.forName("$MOVE_PACKAGE.descriptor.K2MoveOperationDescriptor", true, loader)
            .getField("Companion").get(null)
        val bool = java.lang.Boolean.TYPE
        val descriptor = companion.javaClass.getMethod(
            "Declarations", Project::class.java, Collection::class.java, PsiDirectory::class.java, String::class.java,
            fqNameClass, bool, bool, bool, bool, bool, bool, MoveCallback::class.java
        ).let { invokeUnwrapped(it, companion, project, listOf(element), targetDirectory, targetFileName, packageFqName,
            /* searchForText */ false, /* searchReferences */ true, /* searchInComments */ false,
            /* mppDeclarations */ false,
            // true makes K2 create the package's missing directories under targetDirectory when it applies.
            /* dirStructureMatchesPkg */ missingDirs.isNotEmpty(), /* isMoveToExplicitPackage */ false, null) }!!
        val processor = descriptor.javaClass.getMethod("refactoringProcessor").invoke(descriptor) as BaseRefactoringProcessor

        return Result.success(MoveMemberPlan("kotlin_k2_move_declarations", element, processor) { usages ->
            // Conflicts land in a private field: findUsages adds usage conflicts, preprocessUsages
            // adds visibility/name-clash ones, then hands them to showConflicts, which is silent in
            // a headless IDE and throws in unit-test mode.
            val preprocess = BaseRefactoringProcessor::class.java.getDeclaredMethod("preprocessUsages", Ref::class.java)
                .apply { isAccessible = true }
            try {
                invokeUnwrapped(preprocess, processor, Ref(usages))
            } catch (_: BaseRefactoringProcessor.ConflictsInTestsException) {
            }
            val field = processor.javaClass.getDeclaredField("conflicts").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            ConflictMessages.sanitizeAll((field.get(processor) as MultiMap<PsiElement, String>).values())
        })
    }

    private fun fail(message: String): Result<MoveMemberPlan> = Result.failure(IllegalArgumentException(message))
}
