package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer

/**
 * One table of one database, a page at a time, with sorting and a filter per column. The content of a tab.
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
            loadPage()
        }

    private val refreshButton = JButton("Refresh")
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

    init {
        val bottom = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
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
        val seq = ++loadSeq
        background(status, "Loading $tableName") {
            val (count, page) = client.withDatabase(database) {
                rowCount(tableName, filters) to tablePage(tableName, offset, PAGE_SIZE, sortColumn, descending, filters)
            }
            ui {
                if (seq != loadSeq) return@ui
                // A filtered answer names its columns c0, c1, ...; the real ones come from the unfiltered page.
                val data = if (filtered) page.copy(columns = model.data.columns, editable = model.data.editable) else page
                totalRows = count
                showData(data)
                if (this.filters.size != data.columns.size) this.filters = List(data.columns.size) { "" }
                filterRow.show(this.filters)
                updatePager()
                status.text = if (filtered) "$count matching rows" else "$count rows"
                if (!data.editable) status.text += " (read-only)"
            }
        }
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

    companion object {
        /** Rows per request. Small enough that debug-db's in-memory JSON stays well under the app's heap. */
        const val PAGE_SIZE = 500

        private const val FILTER_DELAY_MS = 400
    }
}
