package io.github.daranna.sqlitebrowser.debugdb

import com.google.gson.Gson
import com.google.gson.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class DebugDbClientTest {

    private val server = FakeDebugDbServer()
    private val client = DebugDbClient(server.port)

    @After
    fun tearDown() = server.close()

    @Test
    fun `databases parses name and encrypted flag`() {
        server.responses["getDbList"] =
            """{"rows":[["shop.db","false","true"],["secret.db","true","true"],["APP_SHARED_PREFERENCES","false","false"]],
               |"columns":[],"isSuccessful":true,"error":null,"dbVersion":0}
            """.trimMargin()

        val databases = client.databases()

        assertEquals(
            listOf(
                DebugDbClient.Database("shop.db", false),
                DebugDbClient.Database("secret.db", true),
                DebugDbClient.Database("APP_SHARED_PREFERENCES", false),
            ),
            databases,
        )
    }

    @Test
    fun `databases hides SQLite side files`() {
        server.responses["getDbList"] =
            """{"rows":[["shop.db-shm","false","true"],["shop.db","false","true"],["shop.db-wal","false","true"],
               |["settings.db","false","true"],["settings.db-journal","false","true"]],
               |"columns":[],"isSuccessful":true,"error":null,"dbVersion":0}
            """.trimMargin()

        assertEquals(listOf("shop.db", "settings.db"), client.databases().map { it.name })
    }

    @Test
    fun `tables hides android_metadata`() {
        server.responses["getTableList"] =
            """{"rows":["android_metadata","article"],"columns":[],"isSuccessful":true,"error":null,"dbVersion":1}"""

        assertEquals(listOf("article"), client.tables("shop.db"))
    }

    @Test
    fun `tables opens the database by name`() {
        server.responses["getTableList"] =
            """{"rows":["article","big_table"],"columns":[],"isSuccessful":true,"error":null,"dbVersion":3}"""

        assertEquals(listOf("article", "big_table"), client.tables("shop.db"))
        assertEquals("getTableList?database=shop.db", server.requests.single())
    }

    @Test
    fun `tablePage parses columns, keys, nulls and numbers`() {
        server.responses["query"] = ARTICLE_PAGE

        val data = client.tablePage("article", offset = 0, limit = 500)

        assertEquals(
            listOf(
                DebugDbClient.Column("article_id", true),
                DebugDbClient.Column("name", false),
                DebugDbClient.Column("price", false),
                DebugDbClient.Column("note", false),
            ),
            data.columns,
        )
        assertTrue(data.editable)
        assertEquals(2, data.rows.size)
        val first = data.rows[0]
        assertEquals(1L, (first[0].value as Number).toLong())
        assertEquals("Apple", first[1].value)
        assertEquals(0.5, (first[2].value as Number).toDouble(), 0.0)
        assertNull(first[3].value)
    }

    @Test
    fun `tablePage names the table once and sorts by column number`() {
        server.responses["query"] = ARTICLE_PAGE

        client.tablePage("article", offset = 1000, limit = 500, orderBy = 0, descending = true)

        val sql = server.lastQuerySql()
        assertEquals("SELECT * FROM article ORDER BY 1 DESC LIMIT 500 OFFSET 1000", sql)
        // debug-db wraps every occurrence of the table name in [...]; a second one would corrupt the SQL.
        assertEquals(1, Regex("article").findAll(sql).count())
    }

    @Test
    fun `tablePage without sort has no ORDER BY`() {
        server.responses["query"] = ARTICLE_PAGE

        client.tablePage("article", offset = 0, limit = 50)

        assertEquals("SELECT * FROM article LIMIT 50 OFFSET 0", server.lastQuerySql())
    }

    @Test
    fun `query sends no raw spaces, since debug-db cuts the route at the first space`() {
        server.responses["query"] = ARTICLE_PAGE

        client.tablePage("article", offset = 0, limit = 50)

        assertFalse(server.requests.last().contains(' '))
        assertFalse(server.requests.last().contains('+'))
    }

    @Test
    fun `rowCount reads the single value`() {
        server.responses["query"] =
            """{"tableInfos":[],"isSuccessful":true,"rows":[[{"dataType":"integer","value":150000}]],
               |"errorMessage":null,"isEditable":false,"isSelectQuery":true}
            """.trimMargin()

        assertEquals(150_000L, client.rowCount("big_table"))
        assertEquals("SELECT COUNT(*) FROM big_table", server.lastQuerySql())
    }

    @Test(expected = DebugDbException::class)
    fun `failed query throws with the server's message`() {
        server.responses["query"] =
            """{"tableInfos":null,"isSuccessful":false,"rows":null,
               |"errorMessage":"no such table: nope","isEditable":false,"isSelectQuery":true}
            """.trimMargin()

        try {
            client.tablePage("nope", offset = 0, limit = 50)
        } catch (e: DebugDbException) {
            assertEquals("no such table: nope", e.message)
            throw e
        }
    }

    @Test
    fun `updateRow encodes its JSON twice so debug-db can decode it twice`() {
        server.responses["updateTableData"] = """{"isSuccessful":true}"""
        val row = listOf(
            DebugDbClient.RowValue("article_id", true, "integer", "1"),
            DebugDbClient.RowValue("name", false, "text", "Apples & pears = 100%"),
        )

        assertTrue(client.updateRow("article", row))

        // debug-db: URLDecoder.decode(route), then Uri.getQueryParameter, which decodes again.
        val onceDecoded = URLDecoder.decode(server.requests.single(), Charsets.UTF_8)
        val param = URI("http://x/$onceDecoded").rawQuery.split("&")
            .first { it.startsWith("updatedData=") }
            .substringAfter("=")
        val json = Gson().fromJson(URLDecoder.decode(param, Charsets.UTF_8), JsonArray::class.java)
        assertEquals("Apples & pears = 100%", json[1].asJsonObject["value"].asString)
        assertTrue(json[0].asJsonObject["isPrimary"].asBoolean)
    }

    @Test
    fun `edit calls report failure`() {
        server.responses["deleteTableData"] = """{"isSuccessful":false}"""

        assertFalse(client.deleteRow("article", listOf(DebugDbClient.RowValue("article_id", true, "integer", "1"))))
    }

    private companion object {
        val ARTICLE_PAGE =
            """{"tableInfos":[{"title":"article_id","isPrimary":true},{"title":"name","isPrimary":false},
               |{"title":"price","isPrimary":false},{"title":"note","isPrimary":false}],
               |"isSuccessful":true,
               |"rows":[[{"dataType":"integer","value":1},{"dataType":"text","value":"Apple"},
               |{"dataType":"real","value":0.5},{"dataType":"text","value":null}],
               |[{"dataType":"integer","value":2},{"dataType":"text","value":"Pear"},
               |{"dataType":"real","value":0.75},{"dataType":"text","value":"ripe"}]],
               |"errorMessage":null,"isEditable":true,"isSelectQuery":true}
            """.trimMargin()
    }
}
