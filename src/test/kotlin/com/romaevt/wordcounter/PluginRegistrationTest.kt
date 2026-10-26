package com.romaevt.wordcounter

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginRegistrationTest : BasePlatformTestCase() {
    fun testPluginIsLoaded() {
        val id = PluginId.getId("com.romaevt.wordcounter")
        val plugin = PluginManagerCore.getPlugin(id)

        assertNotNull("The development IDE must load Word Counter", plugin)
        assertTrue("Word Counter must be loaded", PluginManagerCore.isLoaded(id))
        assertEquals("Word Counter", plugin!!.name)
        assertNull("Compatibility must not have an upper build limit", plugin.untilBuild)
    }
}
