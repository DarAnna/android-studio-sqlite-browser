package io.github.daranna.sqlitebrowser.debugdb

import com.google.gson.Gson
import com.google.gson.JsonArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `filters start with SELECT, name the table once and refer to columns by alias`() {
        server.responses["query"] = ARTICLE_PAGE

        client.tablePage("article", 0, 500, orderBy = 2, filters = listOf("", "app", ">=1", ""))

        val sql = server.lastQuerySql()
        assertEquals(
            "SELECT * FROM (SELECT 0 AS c0, 0 AS c1, 0 AS c2, 0 AS c3 WHERE 0 UNION ALL SELECT * FROM article) " +
                "WHERE CAST(c1 AS TEXT) LIKE '%app%' ESCAPE '\\' AND c2 >= 1 ORDER BY 3 ASC LIMIT 500 OFFSET 0",
            sql,
        )
        assertEquals(1, Regex("article").findAll(sql).count())
        // debug-db runs anything not starting with select through execSQL, which returns no rows.
        assertTrue(sql.startsWith("SELECT "))
    }

    @Test
    fun `blank filters leave the plain query`() {
        server.responses["query"] = ARTICLE_PAGE

        client.tablePage("article", 0, 50, filters = listOf("", "  ", "", ""))

        assertEquals("SELECT * FROM article LIMIT 50 OFFSET 0", server.lastQuerySql())
    }

    @Test
    fun `filter text holding the table name is cut so debug-db cannot rewrite it`() {
        server.responses["query"] = ARTICLE_PAGE

        client.tablePage("article", 0, 50, filters = listOf("", "my article's", "", ""))

        val sql = server.lastQuerySql()
        assertEquals(1, Regex("article").findAll(sql).count())
        assertTrue(sql.contains("'%my a'||'rticle''s%'"))
    }

    @Test
    fun `filter operators compare, numbers unquoted and text quoted`() {
        assertEquals("c0 > 5", filterCondition("c0", "> 5", "t"))
        assertEquals("c0 IN (5, '5')", filterCondition("c0", "=5", "t"))
        assertEquals("(c0 IS NULL OR c0 NOT IN (5, '5'))", filterCondition("c0", "<>5", "t"))
        assertEquals("c0 IS NOT 'x'", filterCondition("c0", "!=x", "t"))
        assertEquals("c0 = 'a''b'", filterCondition("c0", "=a'b", "t"))
        assertEquals("c0 <= -1.5", filterCondition("c0", "<=-1.5", "t"))
        assertNull(filterCondition("c0", ">", "t"))
    }

    @Test
    fun `LIKE wildcards typed by the user match literally`() {
        assertEquals("CAST(c0 AS TEXT) LIKE '%50\\%\\_x%' ESCAPE '\\'", filterCondition("c0", "50%_x", "t"))
    }

    @Test
    fun `semicolons stay out of the SQL, since debug-db splits statements there`() {
        assertEquals("c0 = 'a'||char(59)||'b'", filterCondition("c0", "=a;b", "t"))
    }

    @Test(expected = DebugDbException::class)
    fun `filtering fails clearly when the table name is also in the generated SQL`() {
        // "c0" is also the alias of the first column.
        client.tablePage("c0", 0, 50, filters = listOf("a"))
    }

    @Test
    fun `rowCount applies the filters`() {
        server.responses["query"] =
            """{"tableInfos":[],"isSuccessful":true,"rows":[[{"dataType":"integer","value":3}]],
               |"errorMessage":null,"isEditable":false,"isSelectQuery":true}
            """.trimMargin()

        assertEquals(3L, client.rowCount("article", listOf("x", "")))
        assertEquals(
            "SELECT COUNT(*) FROM (SELECT 0 AS c0, 0 AS c1 WHERE 0 UNION ALL SELECT * FROM article) " +
                "WHERE CAST(c0 AS TEXT) LIKE '%x%' ESCAPE '\\'",
            server.lastQuerySql(),
        )
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

    @Test
    fun `selectPage wraps the user's SELECT and sorts by column number`() {
        server.responses["query"] = ARTICLE_PAGE
        val select = UserSql.parse("SELECT article_id, name FROM article").select!!

        client.selectPage(select, offset = 500, limit = 500, orderBy = 1, descending = true)

        assertEquals(
            """SELECT * FROM (SELECT article_id, name FROM"article") ORDER BY 2 DESC LIMIT 500 OFFSET 500""",
            server.lastQuerySql(),
        )
    }

    @Test
    fun `selectCount counts the user's SELECT`() {
        server.responses["query"] =
            """{"tableInfos":[],"isSuccessful":true,"rows":[[{"dataType":"integer","value":7}]],
               |"errorMessage":null,"isEditable":false,"isSelectQuery":true}
            """.trimMargin()

        assertEquals(7L, client.selectCount("""SELECT * FROM"article""""))
        assertEquals("""SELECT COUNT(*) FROM (SELECT * FROM"article")""", server.lastQuerySql())
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
    fun `withDatabase lists databases before reopening one`() {
        server.responses["getDbList"] = TWO_DATABASES
        server.responses["getTableList"] = """{"rows":["article"],"isSuccessful":true}"""
        server.responses["query"] = ARTICLE_PAGE

        client.withDatabase("shop.db") { tablePage("article", 0, 50) }

        assertEquals(
            listOf("getDbList", "getTableList?database=shop.db", "query"),
            server.requests.map { it.substringBefore("?query=") },
        )
    }

    @Test
    fun `withDatabase does not reopen the database that is already open`() {
        server.responses["getTableList"] = """{"rows":["article"],"isSuccessful":true}"""
        server.responses["query"] = ARTICLE_PAGE
        client.tables("shop.db")
        server.requests.clear()

        client.withDatabase("shop.db") { tablePage("article", 0, 50) }

        assertEquals(1, server.requests.size)
        assertTrue(server.requests.single().startsWith("query?"))
    }

    @Test
    fun `withDatabase switches to another database`() {
        server.responses["getDbList"] = TWO_DATABASES
        server.responses["getTableList"] = """{"rows":["article"],"isSuccessful":true}"""
        server.responses["query"] = ARTICLE_PAGE
        client.tables("shop.db")
        server.requests.clear()

        client.withDatabase("settings.db") { tablePage("pref", 0, 50) }

        assertEquals("getTableList?database=settings.db", server.requests[1])
    }

    @Test(expected = DebugDbException::class)
    fun `withDatabase never opens a database the server does not list`() {
        server.responses["getDbList"] = TWO_DATABASES

        try {
            client.withDatabase("gone.db") { tablePage("article", 0, 50) }
        } finally {
            // getTableList with an unknown name would kill debug-db's server thread.
            assertEquals(listOf("getDbList"), server.requests)
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

    @Test
    fun `primaryKey reads every key column from PRAGMA table_info, in key order`() {
        // A composite key declared as PRIMARY KEY (b, a): debug-db's own isPrimary would mark only b.
        server.responses["query"] =
            """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[
               |[{"dataType":"integer","value":0},{"dataType":"text","value":"a"},{"dataType":"text","value":"INTEGER"},
               |{"dataType":"integer","value":0},{"dataType":"text","value":null},{"dataType":"integer","value":2}],
               |[{"dataType":"integer","value":1},{"dataType":"text","value":"b"},{"dataType":"text","value":"TEXT"},
               |{"dataType":"integer","value":0},{"dataType":"text","value":null},{"dataType":"integer","value":1}],
               |[{"dataType":"integer","value":2},{"dataType":"text","value":"c"},{"dataType":"text","value":"TEXT"},
               |{"dataType":"integer","value":0},{"dataType":"text","value":null},{"dataType":"integer","value":0}]]}
            """.trimMargin()

        assertEquals(listOf("b", "a"), client.primaryKey("pair"))
        assertEquals("PRAGMA table_info(pair)", server.lastQuerySql())
    }

    @Test
    fun `columns reads name, declared type, NOT NULL and key position`() {
        // A column declared without a type has NULL there.
        server.responses["query"] =
            """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[
               |[{"dataType":"integer","value":0},{"dataType":"text","value":"id"},{"dataType":"text","value":"INTEGER"},
               |{"dataType":"integer","value":1},{"dataType":"text","value":null},{"dataType":"integer","value":1}],
               |[{"dataType":"integer","value":1},{"dataType":"text","value":"note"},{"dataType":"text","value":null},
               |{"dataType":"integer","value":0},{"dataType":"text","value":null},{"dataType":"integer","value":0}]]}
            """.trimMargin()

        assertEquals(
            listOf(DebugDbClient.ColumnInfo("id", "INTEGER", true, 1), DebugDbClient.ColumnInfo("note", "", false, 0)),
            client.columns("article"),
        )
        assertEquals("PRAGMA table_info(article)", server.lastQuerySql())
    }

    @Test
    fun `foreignKeys reads single-column keys from PRAGMA foreign_key_list`() {
        // Rows: id, seq, table, from, to, on_update, on_delete, match. Key 1 spans two columns, so it's left out.
        server.queries["PRAGMA foreign_key_list(purchase)"] =
            """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[
               |${fkRow(0, 0, "article", "article_id", "article_id")},
               |${fkRow(1, 0, "pair", "pa", "a")},${fkRow(1, 1, "pair", "pb", "b")},
               |${fkRow(2, 0, "customer", "buyer", null)}]}
            """.trimMargin()
        // customer is referenced without a column, so the key is customer's primary key.
        server.queries["PRAGMA table_info(customer)"] =
            """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[
               |[{"dataType":"integer","value":0},{"dataType":"text","value":"customer_id"},{"dataType":"text","value":"INTEGER"},
               |{"dataType":"integer","value":0},{"dataType":"text","value":null},{"dataType":"integer","value":1}]]}
            """.trimMargin()

        assertEquals(
            listOf(
                DebugDbClient.ForeignKey("article_id", "article", "article_id"),
                DebugDbClient.ForeignKey("buyer", "customer", "customer_id"),
            ),
            client.foreignKeys("purchase"),
        )
    }

    @Test
    fun `foreignKeys leaves out a key to a table without a single-column primary key`() {
        server.queries["PRAGMA foreign_key_list(purchase)"] =
            """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[${fkRow(0, 0, "keyless", "k", null)}]}"""
        server.queries["PRAGMA table_info(keyless)"] = """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[]}"""

        assertEquals(emptyList<DebugDbClient.ForeignKey>(), client.foreignKeys("purchase"))
    }

    private fun fkRow(id: Int, seq: Int, table: String, from: String, to: String?) =
        """[{"dataType":"integer","value":$id},{"dataType":"integer","value":$seq},{"dataType":"text","value":"$table"},
           |{"dataType":"text","value":"$from"},{"dataType":"text","value":${to?.let { "\"$it\"" }}},
           |{"dataType":"text","value":"NO ACTION"},{"dataType":"text","value":"NO ACTION"},{"dataType":"text","value":"NONE"}]
        """.trimMargin()

    @Test
    fun `updateRow and deleteRow refuse a row without a primary key, which would hit every row`() {
        val row = listOf(DebugDbClient.RowValue("name", false, "text", "x"))

        for (call in listOf<() -> Unit>({ client.updateRow("article", row) }, { client.deleteRow("article", row) })) {
            try {
                call()
                fail("expected DebugDbException")
            } catch (_: DebugDbException) {
            }
        }
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `RowValue of sends numbers by kind and NULL and text as text`() {
        server.responses["query"] = ARTICLE_PAGE
        val (id, _, price, note) = client.tablePage("article", 0, 50).rows[0].map { it.value }

        assertEquals(DebugDbClient.RowValue("article_id", true, "integer", "1"), DebugDbClient.RowValue.of("article_id", true, id))
        assertEquals(DebugDbClient.RowValue("price", false, "real", "0.5"), DebugDbClient.RowValue.of("price", false, price))
        // debug-db parses integer and real with Long.valueOf / Double.valueOf, which throw on NULL.
        assertEquals(DebugDbClient.RowValue("note", false, "text", "null"), DebugDbClient.RowValue.of("note", false, note))
        assertEquals(DebugDbClient.RowValue("price", false, "text", "7"), DebugDbClient.RowValue.of("price", false, "7"))
    }

    private companion object {
        val TWO_DATABASES =
            """{"rows":[["shop.db","false","true"],["settings.db","false","true"]],"isSuccessful":true}"""

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
