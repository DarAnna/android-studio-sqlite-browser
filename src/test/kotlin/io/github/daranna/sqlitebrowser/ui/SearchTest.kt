package io.github.daranna.sqlitebrowser.ui

import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient.Column
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {

    @Test
    fun `columns match by part of the name, ignoring case`() {
        val table = RowsTable(
            TableDataModel().apply {
                data = DebugDbClient.TableData(
                    listOf(Column("article_id", false), Column("title", false), Column("Article_Type", false)),
                    emptyList(),
                    editable = false,
                )
            },
        )

        assertEquals(listOf(0, 2), matchingColumns(table, "ARTICLE"))
        assertEquals(emptyList<Int>(), matchingColumns(table, ""))
    }

    @Test
    fun `a matching header puts the match in bold and escapes the rest`() {
        assertEquals("<html>a&lt;<b>Id</b>&amp;</html>", highlightedHtml("a<Id&", "id"))
        assertNull(highlightedHtml("title", "id"))
        assertNull(highlightedHtml("title", ""))
    }
}
