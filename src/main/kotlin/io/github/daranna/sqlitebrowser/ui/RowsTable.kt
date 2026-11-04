package io.github.daranna.sqlitebrowser.ui

import com.intellij.ide.CopyProvider
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.table.JBTable
import java.awt.datatransfer.StringSelection

/**
 * The rows of a table or query tab. Cells are selected one at a time or in blocks, like in a spreadsheet, so the IDE's
 * Copy copies the cells the user picked (see [copiedCells]). Rows are still what Delete Rows acts on: those of the
 * selected cells.
 */
internal open class RowsTable(model: TableDataModel) :
    JBTable(model),
    UiDataProvider {

    init {
        cellSelectionEnabled = true
        autoResizeMode = AUTO_RESIZE_OFF
    }

    override fun uiDataSnapshot(sink: DataSink) {
        sink[PlatformDataKeys.COPY_PROVIDER] = object : CopyProvider {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun performCopy(dataContext: DataContext) = copy(copiedCells(this@RowsTable))

            override fun isCopyEnabled(dataContext: DataContext) = !isEditing && selectedRowCount > 0 && selectedColumnCount > 0

            override fun isCopyVisible(dataContext: DataContext) = true
        }
    }

    /** Copy and Copy Rows, for the right-click menu of a cell. */
    fun addCopyItems(menu: JBPopupMenu) {
        menu.add(
            JBMenuItem("Copy").apply {
                // Only shown: the IDE's Copy action does the copying, through uiDataSnapshot.
                accelerator =
                    (
                        ActionManager.getInstance().getAction(
                            IdeActions.ACTION_COPY,
                        )?.shortcutSet?.shortcuts?.firstOrNull() as? KeyboardShortcut
                        )?.firstKeyStroke
                addActionListener { copy(copiedCells(this@RowsTable)) }
            },
        )
        menu.add(
            JBMenuItem(if (selectedRowCount > 1) "Copy Rows" else "Copy Row").apply {
                toolTipText = "With the column names, ready to paste into a spreadsheet"
                addActionListener { copy(copiedRows(this@RowsTable)) }
            },
        )
    }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))
}

/**
 * The selected cells of [table] as text: one cell is its value as is, several are one line per row with tab-separated
 * columns, as spreadsheets paste them. The values are the model's, so long text comes whole, not as the cell shows it,
 * and NULL is empty.
 */
internal fun copiedCells(table: JBTable): String {
    val rows = table.selectedRows
    val columns = table.selectedColumns
    if (rows.size == 1 && columns.size == 1) return valueAt(table, rows[0], columns[0])
    return rows.joinToString("\n") { row -> columns.joinToString("\t") { tsvField(valueAt(table, row, it)) } }
}

/** The whole rows of the selected cells of [table], under a line of column names, as [copiedCells] writes several. */
internal fun copiedRows(table: JBTable): String {
    val columns = 0 until table.columnCount
    val header = columns.joinToString("\t") { tsvField(table.getColumnName(it)) }
    return (listOf(header) + table.selectedRows.map { row -> columns.joinToString("\t") { tsvField(valueAt(table, row, it)) } })
        .joinToString("\n")
}

private fun valueAt(table: JBTable, row: Int, column: Int) =
    table.model.getValueAt(table.convertRowIndexToModel(row), table.convertColumnIndexToModel(column))?.toString() ?: ""

/** Quotes a value that would otherwise split into more cells or lines, the way spreadsheets read it back. */
private fun tsvField(value: String) =
    if (value.any { it == '\t' || it == '\n' || it == '\r' || it == '"' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
