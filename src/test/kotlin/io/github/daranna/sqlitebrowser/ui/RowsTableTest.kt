package io.github.daranna.sqlitebrowser.ui

import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient.Cell
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient.Column
import org.junit.Assert.assertEquals
import org.junit.Test

class RowsTableTest {

    private val table = RowsTable(
        TableDataModel().apply {
            data = DebugDbClient.TableData(
                listOf(Column("id", true), Column("name", false), Column("note", false)),
                listOf(
                    listOf(Cell("integer", 1), Cell("text", "Apple"), Cell("text", "line one\nline \"two\"")),
                    listOf(Cell("integer", 2), Cell("text", "Pear"), Cell("text", null)),
                ),
                editable = false,
            )
        },
    )

    @Test
    fun `one cell copies its value as is`() {
        table.changeSelection(0, 2, false, false)

        assertEquals("line one\nline \"two\"", copiedCells(table))
    }

    @Test
    fun `NULL copies as empty`() {
        table.changeSelection(1, 2, false, false)

        assertEquals("", copiedCells(table))
    }

    @Test
    fun `several cells copy as tab-separated lines, quoted where a value would split`() {
        table.changeSelection(0, 1, false, false)
        table.changeSelection(1, 2, false, true)

        assertEquals("Apple\t\"line one\nline \"\"two\"\"\"\nPear\t", copiedCells(table))
    }

    @Test
    fun `copy rows copies whole rows under the column names`() {
        table.changeSelection(1, 1, false, false)

        assertEquals("id\tname\tnote\n2\tPear\t", copiedRows(table))
    }
}
