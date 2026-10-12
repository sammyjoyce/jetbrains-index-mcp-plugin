package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import junit.framework.TestCase

/** How a rewritten import keeps the importer's own way of naming the moved member's old module. */
class JsMoveMemberImportStyleUnitTest : TestCase() {
    private val source = "/repo/apps/web/src/features/today/today-screen.tsx"
    private val target = "/repo/apps/web/src/features/today/today-model.ts"

    fun testRelativeImporterGetsARelativePath() {
        assertEquals("./today-model", JsMoveMemberBackend.specifierInStyleOf("./today-screen", source, target, "/repo/apps/web/src/features/today"))
        assertEquals("../today/today-model", JsMoveMemberBackend.specifierInStyleOf("../today/today-screen", source, target, "/repo/apps/web/src/features/bookings"))
    }

    fun testAliasImporterKeepsItsAliasPrefix() {
        assertEquals("@/features/today/today-model", JsMoveMemberBackend.specifierInStyleOf("@/features/today/today-screen", source, target, "/repo/apps/web/src/lib"))
        assertEquals("~/today/today-model", JsMoveMemberBackend.specifierInStyleOf("~/today/today-screen", source, target, "/repo/apps/web/src/lib"))
    }

    fun testExplicitExtensionIsKept() {
        assertEquals("./today-model.ts", JsMoveMemberBackend.specifierInStyleOf("./today-screen.tsx", source, target, "/repo/apps/web/src/features/today"))
    }

    fun testAliasThatDoesNotCoverTheTargetIsLeftToTheIde() {
        assertNull(JsMoveMemberBackend.specifierInStyleOf("@/today-screen", source, "/repo/packages/core/model.ts", "/repo/apps/web/src/lib"))
    }
}
