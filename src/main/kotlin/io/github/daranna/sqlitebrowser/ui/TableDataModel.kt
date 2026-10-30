package io.github.daranna.sqlitebrowser.ui

import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import javax.swing.table.AbstractTableModel

/**
 * Feeds a page of rows to the table. Edits don't change [data]: they go to [onEdit], which saves them in the app
 * and reloads the page.
 */
class TableDataModel : AbstractTableModel() {

    var data: DebugDbClient.TableData = DebugDbClient.TableData(emptyList(), emptyList(), false)
        set(value) {
            field = value
            fireTableStructureChanged()
        }

    /** The single primary key column that picks a row for edits, or null when the table is read-only. Set before [data]. */
    var keyColumn: Int? = null

    /** Called with the row, the column and the typed text, or null for NULL, when the user changes a cell. */
    var onEdit: (row: Int, column: Int, value: String?) -> Unit = { _, _, _ -> }

    /** The column the rows are sorted by on the server, shown with an arrow in its header by [HeaderRenderer]. */
    var sortColumn: Int? = null
    var sortDescending = false

    /** Sorts by [column]: the first time ascending, the second descending, the third back to the rows' own order. */
    fun toggleSort(column: Int) {
        when {
            sortColumn != column -> {
                sortColumn = column
                sortDescending = false
            }

            !sortDescending -> sortDescending = true

            else -> sortColumn = null
        }
    }

    override fun getRowCount() = data.rows.size

    override fun getColumnCount() = data.columns.size

    /** The bare name: [HeaderRenderer] marks the key and the sort with icons. */
    override fun getColumnName(column: Int) = data.columns[column].name

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? = data.rows[rowIndex].getOrNull(columnIndex)?.value

    /** The key can't be edited: debug-db uses it to find the row, so it never sets it. */
    override fun isCellEditable(rowIndex: Int, columnIndex: Int) = keyColumn.let { it != null && it != columnIndex }

    /** [aValue] is the editor's text. Leaving a NULL cell empty keeps it NULL. */
    override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
        if (!isCellEditable(rowIndex, columnIndex)) return
        val old = getValueAt(rowIndex, columnIndex)
        val text = aValue?.toString()
        if (text == (old?.toString() ?: "")) return
        onEdit(rowIndex, columnIndex, text)
    }
}
