package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

class MoveMemberToolBehaviorTest : McpPlatformTestCase() {

    // Production calls arrive off the EDT; the K2/JS processors also expect that.
    override fun runInDispatchThread(): Boolean = false

    /** The tool saves what it changes, so disk is the observable result. */
    private fun text(path: String): String = Files.readString(Path.of(project.basePath!!, path))

    private fun assertContains(path: String, expected: String) =
        text(path).let { assertTrue("Expected '$expected' in $path:\n$it", it.contains(expected)) }

    private fun assertLacks(path: String, unexpected: String) =
        text(path).let { assertFalse("Unexpected '$unexpected' in $path:\n$it", it.contains(unexpected)) }

    private fun request(file: String, line: Int, column: Int, targetFile: String, dryRun: Boolean = false) = buildJsonObject {
        put("file", file)
        put("line", line)
        put("column", column)
        put("targetFile", targetFile)
        if (dryRun) put("dryRun", true)
    }

    private fun writeJavaFixture() {
        registerSourceRoot("mm-src")
        writeProjectFile("mm-src/a/Util.java", "package a;\n\npublic class Util {\n    public static int twice(int x) { return x * 2; }\n    public int inst() { return 1; }\n}\n")
        writeProjectFile("mm-src/b/Target.java", "package b;\n\npublic class Target {\n}\n")
        writeProjectFile("mm-src/c/Caller.java", "package c;\n\nimport a.Util;\n\npublic class Caller {\n    int run() { return Util.twice(2); }\n}\n")
    }

    fun testJavaStaticMethodMovesAndCallersFollow() = runBlocking {
        writeJavaFixture()

        val result = MoveMemberTool().execute(project, request("mm-src/a/Util.java", 4, 23, "mm-src/b/Target.java"))

        assertToolSucceeded("Java static move", result)
        assertContains("mm-src/b/Target.java", "static int twice(int x)")
        assertLacks("mm-src/a/Util.java", "twice")
        assertContains("mm-src/c/Caller.java", "Target.twice(2)")
        assertLacks("mm-src/c/Caller.java", "Util.twice")
        val updated = Json.parseToJsonElement(toolText(result)).jsonObject.getValue("updatedSymbol").jsonObject
        assertEquals("mm-src/b/Target.java", updated.getValue("file").jsonPrimitive.content)
    }

    fun testJavaDryRunPlansWithoutEditing() = runBlocking {
        writeJavaFixture()
        val before = text("mm-src/a/Util.java")

        val result = MoveMemberTool().execute(project, request("mm-src/a/Util.java", 4, 23, "mm-src/b/Target.java", dryRun = true))

        assertToolSucceeded("Java dry run", result)
        val preview = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(preview.toString(), preview.getValue("canApply").jsonPrimitive.boolean)
        assertEquals(1, preview.getValue("usageCount").jsonPrimitive.int)
        assertEquals("java_move_static_members", preview.getValue("plannedChange").jsonObject.getValue("backend").jsonPrimitive.content)
        assertEquals(before, text("mm-src/a/Util.java"))
        assertLacks("mm-src/b/Target.java", "twice")
    }

    fun testJavaInstanceMemberIsRejected() = runBlocking {
        writeJavaFixture()

        val result = MoveMemberTool().execute(project, request("mm-src/a/Util.java", 5, 16, "mm-src/b/Target.java"))

        assertToolFailed("Instance members have no Move Static Members backend", result)
        assertTrue(toolText(result), toolText(result).contains("Only static Java members"))
        assertContains("mm-src/a/Util.java", "inst()")
    }

    fun testTypeScriptFunctionMovesToNewFileAndImportsFollow() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("mm-ts")
        writeProjectFile("mm-ts/util.ts", "export function twice(x: number): number {\n    return x * 2;\n}\n\nexport const keep = 1;\n")
        writeProjectFile("mm-ts/main.ts", "import { twice, keep } from \"./util\";\n\nconsole.log(twice(keep));\n")

        val result = MoveMemberTool().execute(project, request("mm-ts/util.ts", 1, 17, "mm-ts/strings.ts"))

        assertToolSucceeded("TS module member move", result)
        assertContains("mm-ts/strings.ts", "function twice(x: number)")
        assertLacks("mm-ts/util.ts", "twice")
        assertContains("mm-ts/util.ts", "keep")
        val main = text("mm-ts/main.ts")
        assertTrue(main, Regex("""import \{\s*twice\s*\} from ["']\./strings["']""").containsMatchIn(main))
        assertTrue(main, Regex("""import \{\s*keep\s*\} from ["']\./util["']""").containsMatchIn(main))
    }

    fun testTypeScriptDryRunDoesNotCreateTheTargetFile() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("mm-ts-dry")
        writeProjectFile("mm-ts-dry/util.ts", "export function twice(x: number): number {\n    return x * 2;\n}\n")
        writeProjectFile("mm-ts-dry/main.ts", "import { twice } from \"./util\";\n\nconsole.log(twice(1));\n")

        val result = MoveMemberTool().execute(project, request("mm-ts-dry/util.ts", 1, 17, "mm-ts-dry/strings.ts", dryRun = true))

        assertToolSucceeded("TS dry run", result)
        val preview: JsonObject = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(preview.toString(), preview.getValue("canApply").jsonPrimitive.boolean)
        assertTrue(preview.getValue("plannedChange").jsonObject.getValue("createsFile").jsonPrimitive.boolean)
        assertProjectFileAbsent("mm-ts-dry/strings.ts")
        assertContains("mm-ts-dry/util.ts", "twice")
    }

    fun testLanguageWithoutBackendReturnsUnsupported() = runBlocking {
        writeProjectFile("mm-txt/notes.txt", "greeting hello\n")

        val result = MoveMemberTool().execute(project, request("mm-txt/notes.txt", 1, 2, "mm-txt/other.txt"))

        assertToolFailed("Plain text has no move-member backend", result)
        assertTrue(toolText(result), toolText(result).contains("has no move backend for Plain text"))
    }
}
