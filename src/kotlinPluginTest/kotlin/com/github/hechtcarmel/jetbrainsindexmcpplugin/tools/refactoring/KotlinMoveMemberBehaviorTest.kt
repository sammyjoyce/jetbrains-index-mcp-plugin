package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Real K2 move-declarations refactoring behind `ide_move_member`. */
class KotlinMoveMemberBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    // Function and caller avoid stdlib types: the light test module has no Kotlin stdlib, so a
    // `kotlin.Int` reference would be reported as a "will not be accessible" move conflict.
    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in runtime must load the Kotlin plugin", PluginDetectors.kotlin.isAvailable)
        registerSourceRoot("kmm-src")
        writeProjectFile("kmm-src/a/Util.kt", "package a\n\nfun ping() {}\n\nval keep = 1\n")
        writeProjectFile("kmm-src/b/Placeholder.kt", "package b\n\nclass Placeholder\n")
        writeProjectFile("kmm-src/c/Caller.kt", "package c\n\nimport a.ping\nimport a.keep\n\nfun run() {\n    ping()\n    val k = keep\n}\n")
    }

    private fun text(path: String): String = Files.readString(Path.of(project.basePath!!, path))

    private fun request(dryRun: Boolean = false) = buildJsonObject {
        put("file", "kmm-src/a/Util.kt")
        put("line", 3)
        put("column", 5)
        put("targetFile", "kmm-src/b/Strings.kt")
        if (dryRun) put("dryRun", true)
    }

    fun testTopLevelFunctionMovesToNewFileInTargetPackageAndImportsFollow() = runBlocking {
        val result = MoveMemberTool().execute(project, request())

        assertToolSucceeded("Kotlin top-level move", result)
        val moved = text("kmm-src/b/Strings.kt")
        assertTrue(moved, moved.contains("package b"))
        assertTrue(moved, moved.contains("fun ping()"))
        assertFalse(text("kmm-src/a/Util.kt"), text("kmm-src/a/Util.kt").contains("ping"))
        val caller = text("kmm-src/c/Caller.kt")
        assertTrue(caller, caller.contains("import b.ping"))
        assertFalse(caller, caller.contains("import a.ping"))
        assertTrue(caller, caller.contains("import a.keep"))
    }

    fun testDryRunReportsUsagesWithoutCreatingTheFile() = runBlocking {
        val result = MoveMemberTool().execute(project, request(dryRun = true))

        assertToolSucceeded("Kotlin dry run", result)
        val preview = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(preview.toString(), preview.getValue("canApply").jsonPrimitive.boolean)
        assertTrue(preview.toString(), preview.getValue("usageCount").jsonPrimitive.int >= 1)
        assertFalse(Files.exists(Path.of(project.basePath!!, "kmm-src/b/Strings.kt")))
        assertTrue(text("kmm-src/a/Util.kt").contains("fun ping"))
    }
}
