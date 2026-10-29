package io.github.daranna.sqlitebrowser.ui

import com.intellij.util.xmlb.XmlSerializer
import io.github.daranna.sqlitebrowser.ui.SavedQueries.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedQueriesTest {

    private val queries = SavedQueries.Queries()

    @Test
    fun `the last query is kept per app and database, the newest wins`() {
        queries.setLast("app", "shop.db", "SELECT 1")
        queries.setLast("app", "shop.db", "SELECT 2")
        queries.setLast("app", "notes.db", "SELECT 3")

        assertEquals("SELECT 2", queries.lastQuery("app", "shop.db"))
        assertEquals("SELECT 3", queries.lastQuery("app", "notes.db"))
        assertNull(queries.lastQuery("other.app", "shop.db"))
    }

    @Test
    fun `favorites of an app, by database and then name`() {
        queries.addFavorite("app", "shop.db", "b", "SELECT 1")
        queries.addFavorite("app", "notes.db", "z", "SELECT 2")
        queries.addFavorite("app", "shop.db", "A", "SELECT 3")
        queries.addFavorite("other.app", "shop.db", "c", "SELECT 4")

        assertEquals(listOf("z", "A", "b"), queries.favoritesOf("app").map { it.name })
    }

    @Test
    fun `a favorite replaces the one of the same name on the same database only`() {
        queries.addFavorite("app", "shop.db", "all", "SELECT 1")
        queries.addFavorite("app", "notes.db", "all", "SELECT 2")
        queries.addFavorite("app", "shop.db", "all", "SELECT 3")

        assertEquals(
            listOf(Query("app", "notes.db", "SELECT 2", "all"), Query("app", "shop.db", "SELECT 3", "all")),
            queries.favoritesOf("app"),
        )
    }

    @Test
    fun `rename replaces another favorite of the new name, delete removes only the one given`() {
        queries.addFavorite("app", "shop.db", "a", "SELECT 1")
        queries.addFavorite("app", "shop.db", "b", "SELECT 2")
        queries.addFavorite("app", "shop.db", "c", "SELECT 2")

        queries.rename(queries.favoritesOf("app").first { it.name == "a" }, "b")
        assertEquals(listOf("b" to "SELECT 1", "c" to "SELECT 2"), queries.favoritesOf("app").map { it.name to it.sql })

        queries.delete(queries.favoritesOf("app").first { it.name == "c" })
        assertEquals(listOf("b"), queries.favoritesOf("app").map { it.name })
    }

    @Test
    fun `queries survive the XML round trip`() {
        queries.setLast("app", "shop.db", "SELECT *\n  FROM article -- \"x\" & <y>")
        queries.addFavorite("app", "shop.db", "Articles & <more>", "DELETE FROM article;\nSELECT 1")

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(queries), SavedQueries.Queries::class.java)

        assertEquals(queries.last, restored.last)
        assertEquals(queries.favorites, restored.favorites)
    }
}
