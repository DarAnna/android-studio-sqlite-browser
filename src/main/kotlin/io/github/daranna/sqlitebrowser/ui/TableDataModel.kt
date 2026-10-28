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

    override fun getRowCount() = data.rows.size

    override fun getColumnCount() = data.columns.size

    override fun getColumnName(column: Int): String {
        val col = data.columns[column]
        return if (col.isPrimaryKey && data.editable) "${col.name} 🔑" else col.name
    }

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? =
        data.rows[rowIndex].getOrNull(columnIndex)?.value
}
