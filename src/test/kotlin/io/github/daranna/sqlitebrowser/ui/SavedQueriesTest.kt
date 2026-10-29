package io.github.daranna.sqlitebrowser.ui

import com.intellij.util.xmlb.XmlSerializer
import io.github.daranna.sqlitebrowser.ui.SavedQueries.Query
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SavedQueriesTest {

    private val queries = SavedQueries.Queries()

    @Test
    fun `recent queries are kept per app and database, newest first, the last one is the top`() {
        queries.addRecent("app", "shop.db", "SELECT 1")
        queries.addRecent("app", "shop.db", "SELECT 2")
        queries.addRecent("app", "notes.db", "SELECT 3")

        assertEquals(listOf("SELECT 2", "SELECT 1"), queries.recentOf("app", "shop.db").map { it.sql })
        assertEquals("SELECT 2", queries.lastQuery("app", "shop.db"))
        assertEquals("SELECT 3", queries.lastQuery("app", "notes.db"))
        assertNull(queries.lastQuery("other.app", "shop.db"))
    }

    @Test
    fun `running a recent query again moves it to the top instead of adding it twice`() {
        queries.addRecent("app", "shop.db", "SELECT 1")
        queries.addRecent("app", "shop.db", "SELECT 2")
        queries.addRecent("app", "shop.db", "  SELECT 1\n")

        assertEquals(listOf("  SELECT 1\n", "SELECT 2"), queries.recentOf("app", "shop.db").map { it.sql })
    }

    @Test
    fun `only the newest recent queries of a database are kept`() {
        queries.addRecent("app", "notes.db", "SELECT 0")
        repeat(SavedQueries.RECENT_LIMIT + 5) { queries.addRecent("app", "shop.db", "SELECT $it") }

        val shop = queries.recentOf("app", "shop.db")
        assertEquals(SavedQueries.RECENT_LIMIT, shop.size)
        assertEquals("SELECT ${SavedQueries.RECENT_LIMIT + 4}", shop.first().sql)
        assertEquals(1, queries.recentOf("app", "notes.db").size)
    }

    @Test
    fun `a favorite is named after its first line, made unique on its database`() {
        queries.addFavorite("app", "shop.db", "SELECT *\n  FROM article")
        queries.addFavorite("app", "shop.db", "SELECT *\nFROM pair")
        queries.addFavorite("app", "shop.db", "  SELECT    *  \nFROM big_table")
        queries.addFavorite("app", "notes.db", "SELECT *\nFROM note")

        assertEquals(listOf("SELECT *", "SELECT * (2)", "SELECT * (3)"), queries.favoritesOf("app", "shop.db").map { it.name })
        assertEquals(listOf("SELECT *"), queries.favoritesOf("app", "notes.db").map { it.name })
    }

    @Test
    fun `the same SQL is one favorite`() {
        val first = queries.addFavorite("app", "shop.db", "SELECT 1")

        assertSame(first, queries.addFavorite("app", "shop.db", "SELECT 1\n"))
        assertSame(first, queries.favoriteOf("app", "shop.db", " SELECT 1"))
        assertNull(queries.favoriteOf("app", "notes.db", "SELECT 1"))
    }

    @Test
    fun `favorites of a database, by name`() {
        queries.addFavorite("app", "shop.db", "b")
        queries.addFavorite("app", "notes.db", "z")
        queries.addFavorite("app", "shop.db", "A")
        queries.addFavorite("other.app", "shop.db", "c")

        assertEquals(listOf("A", "b"), queries.favoritesOf("app", "shop.db").map { it.name })
    }

    @Test
    fun `rename replaces another favorite of the new name, delete removes only the one given`() {
        queries.addFavorite("app", "shop.db", "SELECT 1")
        queries.addFavorite("app", "shop.db", "SELECT 2")
        queries.addFavorite("app", "shop.db", "SELECT 3")

        queries.rename(queries.favoriteOf("app", "shop.db", "SELECT 1")!!, "SELECT 2")
        assertEquals(
            listOf("SELECT 2" to "SELECT 1", "SELECT 3" to "SELECT 3"),
            queries.favoritesOf("app", "shop.db").map { it.name to it.sql },
        )

        queries.delete(queries.favoriteOf("app", "shop.db", "SELECT 3")!!)
        assertEquals(listOf("SELECT 1"), queries.favoritesOf("app", "shop.db").map { it.sql })
    }

    @Test
    fun `queries survive the XML round trip`() {
        queries.addRecent("app", "shop.db", "SELECT *\n  FROM article -- \"x\" & <y>", time = 1234)
        queries.addFavorite("app", "shop.db", "DELETE FROM article;\nSELECT 1")
        queries.rename(queries.favorites.single(), "Articles & <more>")

        val restored = XmlSerializer.deserialize(XmlSerializer.serialize(queries), SavedQueries.Queries::class.java)

        assertEquals(queries.recent, restored.recent)
        assertEquals(listOf(Query("app", "shop.db", "DELETE FROM article;\nSELECT 1", "Articles & <more>")), restored.favorites)
    }
}
