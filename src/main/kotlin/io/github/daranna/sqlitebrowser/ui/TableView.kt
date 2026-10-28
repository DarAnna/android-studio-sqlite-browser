package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient.RowValue
import io.github.daranna.sqlitebrowser.debugdb.DebugDbException
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.Timer

/**
 * One table of one database, a page at a time, with sorting, a filter per column and editing. The content of a tab.
 *
 * Every load reopens [database] on the server if another tab switched it, through [DebugDbClient.withDatabase].
 */
class TableView(val database: String, val tableName: String, client: DebugDbClient) :
    JPanel(BorderLayout()),
    Disposable {

    /** Replaced when the panel reconnects to the same app, since the new client is the one that knows the server's state. */
    var client: DebugDbClient = client
        set(value) {
            field = value
            primaryKey = null
            loadPage()
        }

    private val refreshButton = JButton("Refresh")
    private val addButton = JButton("Add Row")
    private val deleteButton = JButton("Delete")
    private val prevButton = JButton("◀")
    private val nextButton = JButton("▶")
    private val pageLabel = JBLabel()
    private val status = JBLabel()

    private val model = TableDataModel()
    private val table: JBTable = object : JBTable(model) {
        // The table moves its header into the scroll pane every time it is added to one, which takes it out of
        // columnHeader. Put it back, then put columnHeader in its place.
        override fun configureEnclosingScrollPane() {
            super.configureEnclosingScrollPane()
            val scrollPane = this.parent?.parent as? JScrollPane ?: return
            columnHeader.add(tableHeader, BorderLayout.NORTH)
            scrollPane.setColumnHeaderView(columnHeader)
        }
    }.apply {
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
        // Stops editing when the user clicks elsewhere, e.g. on Refresh, instead of keeping the editor open.
        putClientProperty("terminateEditOnFocusLost", true)
        setDefaultRenderer(Any::class.java, NullAwareRenderer())
    }
    private val filterRow = ColumnFilterRow(table, ::filtersEdited)
    private val columnHeader: JPanel = JPanel(BorderLayout()).apply {
        add(table.tableHeader, BorderLayout.NORTH)
        add(filterRow, BorderLayout.SOUTH)
    }

    // Paging and sorting of the table. Sorting happens in SQL, so it covers all rows, not just the page.
    private var offset = 0
    private var totalRows = 0L

    /** What the user typed per column, in column order; empty until the first page is shown. */
    private var filters: List<String> = emptyList()

    /** Waits for a pause in typing before asking the app for the filtered rows. */
    private val filterTimer = Timer(FILTER_DELAY_MS) {
        offset = 0
        loadPage()
    }.apply { isRepeats = false }

    /** Counts page loads, so a slow answer to an old filter doesn't replace the newer one. */
    private var loadSeq = 0

    /** The table's primary key columns, asked once per client when the table turns out editable; null until then. */
    private var primaryKey: List<String>? = null

    init {
        val bottom = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    add(addButton)
                    add(deleteButton)
                    add(refreshButton)
                    add(prevButton)
                    add(pageLabel)
                    add(nextButton)
                },
                BorderLayout.EAST,
            )
        }
        status.border = JBUI.Borders.empty(2, 6)

        add(JBScrollPane(table), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        refreshButton.addActionListener { loadPage() }
        addButton.addActionListener { addRow() }
        deleteButton.addActionListener { deleteSelectedRows() }
        model.onEdit = ::saveCell
        table.selectionModel.addListSelectionListener { updateEditButtons() }
        val deleteRows = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = deleteSelectedRows()
        }
        listOf(KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE).forEach {
            table.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(it, 0), "deleteRows")
        }
        table.actionMap.put("deleteRows", deleteRows)
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)

            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
        })
        prevButton.addActionListener {
            offset = maxOf(0, offset - PAGE_SIZE)
            loadPage()
        }
        nextButton.addActionListener {
            offset += PAGE_SIZE
            loadPage()
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = table.columnAtPoint(e.point)
                if (column >= 0) sortBy(table.convertColumnIndexToModel(column))
            }
        })
        updatePager()
        loadPage()
    }

    private fun filtersEdited() {
        filters = filterRow.texts
        filterTimer.restart()
    }

    /** First click sorts ascending, second descending, third goes back to table order. */
    private fun sortBy(column: Int) {
        when {
            model.sortColumn != column -> {
                model.sortColumn = column
                model.sortDescending = false
            }

            !model.sortDescending -> model.sortDescending = true

            else -> model.sortColumn = null
        }
        offset = 0
        loadPage()
    }

    private fun loadPage() {
        val client = client
        val offset = offset
        val sortColumn = model.sortColumn
        val descending = model.sortDescending
        val filters = filters
        val filtered = filters.any { it.isNotBlank() }
        val askKey = primaryKey == null
        val seq = ++loadSeq
        background(status, "Loading $tableName") {
            val (count, page, key) = client.withDatabase(database) {
                val count = rowCount(tableName, filters)
                val page = tablePage(tableName, offset, PAGE_SIZE, sortColumn, descending, filters)
                Triple(count, page, if (askKey && !filtered && page.editable) primaryKey(tableName) else null)
            }
            ui {
                if (seq != loadSeq) return@ui
                // A filtered answer names its columns c0, c1, ...; the real ones come from the unfiltered page.
                val data = if (filtered) page.copy(columns = model.data.columns, editable = model.data.editable) else page
                if (key != null) primaryKey = key
                totalRows = count
                model.keyColumn = keyColumn(data)
                showData(data)
                if (this.filters.size != data.columns.size) this.filters = List(data.columns.size) { "" }
                filterRow.show(this.filters)
                updatePager()
                status.text = if (filtered) "$count matching rows" else "$count rows"
                when {
                    !data.editable -> status.text += " (read-only)"
                    model.keyColumn == null -> status.text += " (no single-column primary key, so rows can only be added)"
                }
                updateEditButtons()
            }
        }
    }

    /**
     * The column that picks a row for edits, or null when rows can't be edited. A composite key won't do: debug-db
     * would find the row by its first column only.
     */
    private fun keyColumn(data: DebugDbClient.TableData): Int? {
        if (!data.editable) return null
        val name = primaryKey?.singleOrNull() ?: return null
        return data.columns.indexOfFirst { it.name == name }.takeIf { it >= 0 }
    }

    private fun updateEditButtons() {
        addButton.isEnabled = model.data.editable
        deleteButton.isEnabled = model.keyColumn != null && table.selectedRowCount > 0
    }

    private fun keyValue(row: Int): RowValue {
        val key = model.keyColumn!!
        return RowValue.of(model.data.columns[key].name, true, model.getValueAt(row, key))
    }

    private fun saveCell(row: Int, column: Int, value: String?) {
        val values = listOf(keyValue(row), RowValue.of(model.data.columns[column].name, false, value))
        change("Saving") { updateRow(tableName, values) }
    }

    private fun addRow() {
        if (!model.data.editable) return
        val dialog = AddRowDialog(this, tableName, model.data.columns)
        if (!dialog.showAndGet()) return
        val values = dialog.values.map { (column, text) -> RowValue.of(column, false, text) }
        change("Adding a row") { addRow(tableName, values) }
    }

    private fun deleteSelectedRows() {
        if (model.keyColumn == null || table.isEditing) return
        val keys = table.selectedRows.map { keyValue(table.convertRowIndexToModel(it)) }
        if (keys.isEmpty()) return
        val what = if (keys.size == 1) "this row" else "these ${keys.size} rows"
        val answer = Messages.showYesNoDialog(
            this,
            "Delete $what from $tableName in the app's database?",
            "Delete Rows",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return
        change("Deleting") { keys.all { deleteRow(tableName, listOf(it)) } }
    }

    /** Runs [edit] in the app, then reloads the page to show what the app's database now holds. */
    private fun change(what: String, edit: DebugDbClient.() -> Boolean) {
        val client = client
        background(status, what) {
            if (!client.withDatabase(database) { edit() }) throw DebugDbException("the app refused the change")
            ui { loadPage() }
        }
    }

    /** Right-click on a cell: select it, then offer what can be done with it. */
    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        val column = table.columnAtPoint(e.point)
        if (row < 0 || column < 0) return
        if (!table.isRowSelected(row)) table.changeSelection(row, column, false, false)
        val modelRow = table.convertRowIndexToModel(row)
        val modelColumn = table.convertColumnIndexToModel(column)
        JBPopupMenu().apply {
            add(
                JBMenuItem("Set to NULL").apply {
                    isEnabled =
                        this@TableView.model.isCellEditable(modelRow, modelColumn) &&
                        this@TableView.model.getValueAt(modelRow, modelColumn) != null
                    addActionListener { saveCell(modelRow, modelColumn, null) }
                },
            )
            add(
                JBMenuItem(if (table.selectedRowCount > 1) "Delete Rows" else "Delete Row").apply {
                    isEnabled = this@TableView.model.keyColumn != null
                    addActionListener { deleteSelectedRows() }
                },
            )
        }.show(table, e.x, e.y)
    }

    /** Replaces the rows; keeps the column widths the user set when the columns are the same. */
    private fun showData(data: DebugDbClient.TableData) {
        val widths = if (data.columns == model.data.columns) {
            table.columnModel.columns.toList().associate { it.modelIndex to it.width }
        } else {
            emptyMap()
        }
        model.data = data
        table.columnModel.columns.toList().forEach { column ->
            widths[column.modelIndex]?.let {
                column.preferredWidth = it
                column.width = it
            }
        }
    }

    private fun updatePager() {
        val shown = model.rowCount
        pageLabel.text = if (shown == 0) "" else "${offset + 1}–${offset + shown} of $totalRows"
        prevButton.isEnabled = offset > 0
        nextButton.isEnabled = offset + shown < totalRows
    }

    override fun dispose() {
        filterTimer.stop()
    }

    /** Shows NULL as a grey "NULL", so it can't be mistaken for an empty string. */
    private class NullAwareRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
            if (value == null) append("NULL", SimpleTextAttributes.GRAYED_ATTRIBUTES) else append(value.toString())
        }
    }

    companion object {
        /** Rows per request. Small enough that debug-db's in-memory JSON stays well under the app's heap. */
        const val PAGE_SIZE = 500

        private const val FILTER_DELAY_MS = 400
    }
}
