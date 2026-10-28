package io.github.daranna.sqlitebrowser.ui

import com.intellij.util.xmlb.XmlSerializer
import io.github.daranna.sqlitebrowser.ui.SavedSession.Tab
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSessionTest {

    private val tables = mapOf(
        "shop.db" to listOf("article", "customer"),
        "notes.db" to listOf("note"),
    )

    @Test
    fun `reopens tabs whose database and table the app still has, in their order`() {
        val saved = listOf(Tab("notes.db", "note"), Tab("shop.db", "article"), Tab("shop.db", "gone"), Tab("old.db", "x"))

        val reopened = SavedSession.reopenable(saved, listOf("shop.db", "notes.db")) { tables.getValue(it) }

        assertEquals(listOf(Tab("notes.db", "note"), Tab("shop.db", "article")), reopened)
    }

    @Test
    fun `asks for tables only of listed databases, once each`() {
        val asked = mutableListOf<String>()
        val saved = listOf(Tab("shop.db", "article"), Tab("shop.db", "customer"), Tab("old.db", "x"))

        SavedSession.reopenable(saved, listOf("shop.db")) {
            asked += it
            tables.getValue(it)
        }

        assertEquals(listOf("shop.db"), asked)
    }

    @Test
    fun `the session survives the XML round trip`() {
        val session = SavedSession.Session().apply {
            serial = "emulator-5554"
            port = 8081
            app = "io.github.daranna.sqlitebrowser.sample"
            tabs = mutableListOf(Tab("shop.db", "article"), Tab("notes.db", "note"))
            selectedTab = Tab("notes.db", "note")
        }

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(session), SavedSession.Session::class.java)

        assertEquals("emulator-5554", restored.serial)
        assertEquals(8081, restored.port)
        assertEquals("io.github.daranna.sqlitebrowser.sample", restored.app)
        assertEquals(session.tabs, restored.tabs)
        assertEquals(Tab("notes.db", "note"), restored.selectedTab)
    }
}
