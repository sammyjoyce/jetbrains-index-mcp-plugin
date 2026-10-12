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

    fun testTypeScriptTypeAliasIsImportedTypeOnlyEverywhereAfterMove() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("mm-ts-typeonly")
        writeProjectFile("mm-ts-typeonly/screen.ts", "export type Visit = { id: string };\n\nexport const screen: Visit = { id: \"s\" };\n")
        writeProjectFile("mm-ts-typeonly/model.ts", "export function rel(): number {\n    return 1;\n}\n")
        writeProjectFile(
            "mm-ts-typeonly/card.tsx",
            "import type { Visit } from \"./screen\";\nimport { rel } from \"./model\";\n\nexport const v: Visit = { id: String(rel()) };\n"
        )

        val result = MoveMemberTool().execute(project, request("mm-ts-typeonly/screen.ts", 1, 13, "mm-ts-typeonly/model.ts"))

        assertToolSucceeded("TS type move into a file the importer already value-imports", result)
        assertContains("mm-ts-typeonly/model.ts", "type Visit")
        // A plain import of a type breaks verbatimModuleSyntax projects (TS1484) whenever the IDE misses that setting.
        val card = text("mm-ts-typeonly/card.tsx")
        assertTrue(card, Regex("""import \{[^}]*\btype Visit\b[^}]*\} from ["']\./model["']""").containsMatchIn(card))
        assertTrue(card, Regex("""\brel\b""").containsMatchIn(card))
        // The source file still uses the type, so the processor imports it back; that import must be type-only too.
        val screen = text("mm-ts-typeonly/screen.ts")
        assertTrue(screen, Regex("""import (type \{\s*Visit\s*\}|\{[^}]*\btype Visit\b[^}]*\}) from ["']\./model["']""").containsMatchIn(screen))
    }

    // The atom regression: tsconfig paths make the IDE prefer `@/...`, but the importer used `./...`; the removed
    // import left a doubled blank line; appending dropped the target's final newline; the type import lost `type`.
    fun testTypeScriptTypeMoveKeepsImporterPathStyleBlankLinesAndFinalNewline() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("mm-ts-style")
        writeProjectFile(
            "mm-ts-style/tsconfig.json",
            "{ \"compilerOptions\": { \"verbatimModuleSyntax\": true, \"baseUrl\": \".\", \"paths\": { \"@/*\": [\"./src/*\"] } } }\n"
        )
        writeProjectFile("mm-ts-style/src/lib/fmt.ts", "export function fmt(x: string): string {\n  return x;\n}\n")
        writeProjectFile(
            "mm-ts-style/src/today/screen.ts",
            "export type Visit = { id: string };\n\nexport const screen: Visit = { id: \"s\" };\n"
        )
        writeProjectFile("mm-ts-style/src/today/model.ts", "export function rel(): number {\n  return 1;\n}\n")
        writeProjectFile(
            "mm-ts-style/src/today/agenda.ts",
            "import { fmt } from \"@/lib/fmt\";\n\nimport type { Visit } from \"./screen\";\n\nexport const v: Visit = { id: fmt(\"a\") };\n"
        )

        val result = MoveMemberTool().execute(project, request("mm-ts-style/src/today/screen.ts", 1, 13, "mm-ts-style/src/today/model.ts"))

        assertToolSucceeded("TS type move", result)
        val agenda = text("mm-ts-style/src/today/agenda.ts")
        assertTrue(agenda, Regex("""import type \{\s*Visit\s*\} from "\./model";""").containsMatchIn(agenda))
        assertFalse("Doubled blank line left in the import header:\n$agenda", agenda.contains("\n\n\n"))
        assertTrue("Importer lost its final newline", agenda.endsWith("\n"))
        val model = text("mm-ts-style/src/today/model.ts")
        assertTrue(model, model.contains("type Visit"))
        assertTrue("Target lost its final newline:\n$model", model.endsWith("\n"))
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

    fun testTypeScriptMoveCreatesMissingDirectoriesAndRelativeImportsFollow() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("mm-ts-dir")
        writeProjectFile("mm-ts-dir/util.ts", "export function twice(x: number): number {\n    return x * 2;\n}\n")
        writeProjectFile("mm-ts-dir/main.ts", "import { twice } from \"./util\";\n\nconsole.log(twice(1));\n")

        val preview = MoveMemberTool().execute(project, request("mm-ts-dir/util.ts", 1, 17, "mm-ts-dir/lib/math/twice.ts", dryRun = true))
        assertToolSucceeded("TS dry run into a new directory", preview)
        assertEquals("lib/math", Json.parseToJsonElement(toolText(preview)).jsonObject
            .getValue("plannedChange").jsonObject.getValue("createsDirectory").jsonPrimitive.content)
        assertProjectFileAbsent("mm-ts-dir/lib")

        val result = MoveMemberTool().execute(project, request("mm-ts-dir/util.ts", 1, 17, "mm-ts-dir/lib/math/twice.ts"))

        assertToolSucceeded("TS move into a new directory", result)
        assertContains("mm-ts-dir/lib/math/twice.ts", "function twice(x: number)")
        assertLacks("mm-ts-dir/util.ts", "twice")
        val main = text("mm-ts-dir/main.ts")
        assertTrue(main, Regex("""import \{\s*twice\s*\} from ["']\./lib/math/twice["']""").containsMatchIn(main))
    }

    fun testJavaMoveIntoMissingDirectoryIsRejectedWithoutCreatingIt() = runBlocking {
        writeJavaFixture()

        val result = MoveMemberTool().execute(project, request("mm-src/a/Util.java", 4, 23, "mm-src/z/New.java"))

        assertToolFailed("Java needs an existing target class", result)
        assertTrue(toolText(result), toolText(result).contains("existing .java file"))
        assertProjectFileAbsent("mm-src/z")
    }

    fun testLanguageWithoutBackendReturnsUnsupported() = runBlocking {
        writeProjectFile("mm-txt/notes.txt", "greeting hello\n")

        val result = MoveMemberTool().execute(project, request("mm-txt/notes.txt", 1, 2, "mm-txt/other.txt"))

        assertToolFailed("Plain text has no move-member backend", result)
        assertTrue(toolText(result), toolText(result).contains("has no move backend for Plain text"))
    }
}
