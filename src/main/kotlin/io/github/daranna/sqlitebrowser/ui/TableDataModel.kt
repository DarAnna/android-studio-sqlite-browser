package io.github.daranna.sqlitebrowser.ui

import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import javax.swing.table.AbstractTableModel

/** Read-only for now. Editing comes next: override isCellEditable and setValueAt. */
class TableDataModel : AbstractTableModel() {

    var data: DebugDbClient.TableData = DebugDbClient.TableData(emptyList(), emptyList(), false)
        set(value) {
            field = value
            fireTableStructureChanged()
        }

    /** The column the rows are sorted by on the server, shown with an arrow in its header. */
    var sortColumn: Int? = null
    var sortDescending = false

    override fun getRowCount() = data.rows.size

    override fun getColumnCount() = data.columns.size

    override fun getColumnName(column: Int): String {
        val col = data.columns[column]
        val key = if (col.isPrimaryKey && data.editable) " 🔑" else ""
        val arrow = if (column == sortColumn) (if (sortDescending) " ▼" else " ▲") else ""
        return col.name + key + arrow
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? = data.rows[rowIndex].getOrNull(columnIndex)?.value
}
