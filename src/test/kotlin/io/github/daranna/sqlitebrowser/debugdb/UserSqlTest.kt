package io.github.daranna.sqlitebrowser.debugdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserSqlTest {

    private fun rewritten(sql: String) = UserSql.parse(sql).statements

    @Test
    fun `table names are glued to their keyword in quotes, so debug-db finds no table to rewrite`() {
        assertEquals(
            listOf("""SELECT a.article_id, b.id FROM"article" a JOIN"big_table" b ON b.id = a.article_id"""),
            rewritten("SELECT a.article_id, b.id FROM article a JOIN big_table b ON b.id = a.article_id"),
        )
        assertEquals(listOf("""insert into"article"(name) VALUES ('x')"""), rewritten("insert into article(name) VALUES ('x')"))
        assertEquals(listOf("""UPDATE"article" SET note = 'y'"""), rewritten("UPDATE article SET note = 'y'"))
        assertEquals(listOf("""DROP TABLE"t""""), rewritten("DROP TABLE t"))
    }

    @Test
    fun `a quoted name is glued as it is`() {
        assertEquals(listOf("""SELECT * FROM"my table", [other]"""), rewritten("""SELECT * FROM "my table", [other]"""))
        assertEquals(listOf("SELECT * FROM[x] JOIN`y`"), rewritten("SELECT * FROM [x] JOIN `y`"))
    }

    @Test
    fun `subqueries and keywords after FROM, UPDATE and TABLE stay as they are`() {
        assertEquals(listOf("""SELECT * FROM (SELECT * FROM"t")"""), rewritten("SELECT * FROM (SELECT * FROM t)"))
        assertEquals(listOf("""UPDATE OR IGNORE t SET a = 1"""), rewritten("UPDATE OR IGNORE t SET a = 1"))
        assertEquals(listOf("""CREATE TABLE IF NOT EXISTS t (a)"""), rewritten("CREATE TABLE IF NOT EXISTS t (a)"))
    }

    @Test
    fun `keywords inside strings, quoted names and longer words are not touched`() {
        assertEquals(
            listOf("""SELECT 'from x', "from", from_date FROM"t""""),
            rewritten("""SELECT 'from x', "from", from_date FROM t"""),
        )
    }

    @Test
    fun `comments go and whitespace becomes one space, since debug-db reads the first word up to a space`() {
        assertEquals(
            listOf("""SELECT * FROM"t" WHERE a = 'two  spaces'"""),
            rewritten("-- note\nSELECT\n\t*\n  FROM /* the table */ t\nWHERE a = 'two  spaces'"),
        )
    }

    @Test
    fun `statements split at semicolons, but not at one inside a string`() {
        assertEquals(
            listOf("""UPDATE"t" SET a = 'x'||char(59)||'y'""", """SELECT * FROM"t""""),
            rewritten("UPDATE t SET a = 'x;y';\n\nSELECT * FROM t;  ;"),
        )
        assertEquals(listOf("SELECT 'it''s'"), rewritten("SELECT 'it''s'"))
    }

    @Test
    fun `a leading lowercase delete becomes DELETE, which debug-db's parser leaves alone`() {
        assertEquals(listOf("""DELETE from"t" WHERE 0"""), rewritten("delete from t WHERE 0"))
    }

    @Test
    fun `a last SELECT is paged after the statements before it`() {
        val sql = UserSql.parse("UPDATE t SET a = 1; SELECT * FROM t")

        assertEquals(listOf("""UPDATE"t" SET a = 1"""), sql.leading)
        assertEquals("""SELECT * FROM"t"""", sql.select)
    }

    @Test
    fun `WITH is paged unless it leads to a write`() {
        assertEquals("""WITH x AS (SELECT 1) SELECT * FROM"x"""", UserSql.parse("WITH x AS (SELECT 1) SELECT * FROM x").select)
        assertNull(UserSql.parse("WITH x AS (SELECT 1) DELETE FROM t").select)
    }

    @Test
    fun `PRAGMA and writes are run once, not paged`() {
        val sql = UserSql.parse("SELECT 1; PRAGMA table_info(t)")

        assertNull(sql.select)
        assertEquals(listOf("SELECT 1", "PRAGMA table_info(t)"), sql.leading)
    }

    @Test
    fun `blank SQL has no statements`() {
        assertEquals(emptyList<String>(), rewritten("  -- nothing\n ; "))
    }
}
