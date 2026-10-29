package io.github.daranna.sqlitebrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SqliteBrowserPanelTest {

    @Test
    fun `short name is the package's last part`() {
        assertEquals("sample", SqliteBrowserPanel.shortName("io.github.daranna.sqlitebrowser.sample"))
    }

    @Test
    fun `short name keeps the part before a build variant`() {
        assertEquals("shop.debug", SqliteBrowserPanel.shortName("com.example.shop.debug"))
    }

    @Test
    fun `short name of an unknown uid stays whole`() {
        assertEquals("uid 10123", SqliteBrowserPanel.shortName("uid 10123"))
    }
}
