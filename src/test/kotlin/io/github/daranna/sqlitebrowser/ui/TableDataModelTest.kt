package io.github.daranna.sqlitebrowser.ui

import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient.Cell
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient.Column
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TableDataModelTest {

    private val edits = mutableListOf<Triple<Int, Int, String?>>()
    private val model = TableDataModel().apply {
        keyColumn = 0
        data = DebugDbClient.TableData(
            listOf(Column("article_id", true), Column("name", false), Column("note", false)),
            listOf(listOf(Cell("integer", 1), Cell("text", "Apple"), Cell("text", null))),
            editable = true,
        )
        onEdit = { row, column, value -> edits += Triple(row, column, value) }
    }

    @Test
    fun `the key column and read-only tables can't be edited`() {
        assertFalse(model.isCellEditable(0, 0))
        assertTrue(model.isCellEditable(0, 1))

        model.keyColumn = null
        assertFalse(model.isCellEditable(0, 1))
    }

    @Test
    fun `a changed cell goes to onEdit`() {
        model.setValueAt("Pear", 0, 1)

        assertEquals(listOf(Triple(0, 1, "Pear")), edits)
    }

    @Test
    fun `unchanged text and an empty NULL cell are no edit`() {
        model.setValueAt("Apple", 0, 1)
        model.setValueAt("", 0, 2)

        assertTrue(edits.isEmpty())
    }

    @Test
    fun `headers are the bare column names`() {
        model.toggleSort(0)

        assertEquals("article_id", model.getColumnName(0))
        assertEquals("name", model.getColumnName(1))
    }

    @Test
    fun `sorting a column goes ascending, descending, then off`() {
        model.toggleSort(1)
        assertEquals(1 to false, model.sortColumn to model.sortDescending)

        model.toggleSort(1)
        assertEquals(1 to true, model.sortColumn to model.sortDescending)

        model.toggleSort(1)
        assertEquals(null, model.sortColumn)

        model.toggleSort(1)
        model.toggleSort(2)
        assertEquals(2 to false, model.sortColumn to model.sortDescending)
    }
}
