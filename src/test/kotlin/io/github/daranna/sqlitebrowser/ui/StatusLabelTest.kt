package io.github.daranna.sqlitebrowser.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusLabelTest {

    @Test
    fun `long status doesn't set a minimum width`() {
        val label = StatusLabel().apply { text = "No single-column primary key, so rows can only be added" }
        assertEquals(0, label.minimumSize.width)
    }
}
