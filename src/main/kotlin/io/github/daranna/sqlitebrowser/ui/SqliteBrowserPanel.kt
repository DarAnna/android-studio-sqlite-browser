package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.adb.Adb
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Timer

/**
 * Device and port on top, then database and table pickers, then the rows, one page at a time.
 *
 * All adb and HTTP calls run on a pooled thread; Swing components are only touched on the EDT.
 */
class SqliteBrowserPanel(parent: Disposable) :
    JPanel(BorderLayout()),
    Disposable {

    private val deviceBox = ComboBox<Adb.Device>()
    private val portField = JBTextField("8080", 5)
    private val connectButton = JButton("Connect")

    /** The app that answers on the port, which may not be the one the user means. */
    private val appLabel = JBLabel()
    private val databaseBox = ComboBox<String>()
    private val tableBox = ComboBox<String>()
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

    private var client: DebugDbClient? = null

    /** Set while we fill the combo boxes ourselves, so their listeners don't fire requests. */
    private var updatingCombos = false

    // Paging and sorting of the current table. Sorting happens in SQL, so it covers all rows, not just the page.
    private var offset = 0
    private var totalRows = 0L

    /** What the user typed per column, in column order; empty until a table is shown. */
    private var filters: List<String> = emptyList()

    /** Waits for a pause in typing before asking the app for the filtered rows. */
    private val filterTimer = Timer(FILTER_DELAY_MS) {
        offset = 0
        loadPage()
    }.apply { isRepeats = false }

    /** While no device is listed, looks again: the emulator may have started after this panel opened. */
    private val devicePoll = Timer(DEVICE_POLL_MS) { if (deviceBox.itemCount == 0) pollDevices() }

    /** Counts page loads, so a slow answer to an old filter doesn't replace the newer one. */
    private var loadSeq = 0

    init {
        Disposer.register(parent, this)

        // Two rows: a FlowLayout row reports a one-line height, so a single row clips whatever doesn't fit the width.
        val connection = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Device:"))
            add(deviceBox)
            add(JBLabel("Port:"))
            add(portField)
            add(connectButton)
            add(appLabel)
        }
        val picker = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Database:"))
            add(databaseBox)
            add(JBLabel("Table:"))
            add(tableBox)
            add(refreshButton)
        }
        val top = JPanel(GridLayout(2, 1)).apply {
            add(connection)
            add(picker)
        }
        val bottom = JPanel(BorderLayout()).apply {
            add(status, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                    add(prevButton)
                    add(pageLabel)
                    add(nextButton)
                },
                BorderLayout.EAST,
            )
        }
        status.border = JBUI.Borders.empty(2, 6)

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        connectButton.addActionListener { connect() }
        databaseBox.addActionListener { if (!updatingCombos) loadTables() }
        tableBox.addActionListener { if (!updatingCombos) openTable() }
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

        loadDevices()
        devicePoll.start()
    }

    private fun pollDevices() = ApplicationManager.getApplication().executeOnPooledThread {
        val devices = try {
            Adb.devices()
        } catch (e: Exception) {
            emptyList()
        }
        if (devices.isNotEmpty()) {
            ui {
                if (deviceBox.itemCount == 0) {
                    setItems(deviceBox, devices)
                    status.text = "Pick a device and press Connect."
                }
            }
        }
    }

    private fun loadDevices() = background("Looking for devices") {
        val devices = Adb.devices()
        ui {
            setItems(deviceBox, devices)
            status.text = if (devices.isEmpty()) "No device. Start an emulator or plug in a phone." else "Pick a device and press Connect."
        }
    }

    private fun connect() {
        val device = deviceBox.item ?: return loadDevices()
        val port = portField.text.trim().toIntOrNull() ?: return run { status.text = "Port must be a number" }
        client = null
        appLabel.text = ""
        background("Connecting to ${device.serial}:$port") {
            val owner = Adb.portOwner(device.serial, port)
                ?: error("No app on ${device.serial} listens on port $port. Is the debug app running, with debug-db?")
            Adb.forward(device.serial, port)
            val newClient = DebugDbClient(port)
            val databases = newClient.databases()
            client = newClient
            ui {
                appLabel.text = "App: $owner"
                setItems(databaseBox, databases.map { it.name }, select = false)
                setItems(tableBox, emptyList())
                clearTable()
                status.text = "Connected to $owner. ${databases.size} databases. " +
                    "Not the app you meant? Another debug-db app holds port $port; give each app its own port."
            }
        }
    }

    private fun loadTables() {
        val client = client ?: return
        val database = databaseBox.item ?: return
        background("Opening $database") {
            val tables = client.tables(database)
            ui {
                setItems(tableBox, tables, select = false)
                clearTable()
                status.text = "$database: ${tables.size} tables"
            }
        }
    }

    private fun filtersEdited() {
        filters = filterRow.texts
        filterTimer.restart()
    }

    /** A new table: back to the first page, no sorting, no filters. */
    private fun openTable() {
        offset = 0
        filters = emptyList()
        model.sortColumn = null
        model.sortDescending = false
        loadPage()
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
        val client = client ?: return
        val tableName = tableBox.item ?: return
        val offset = offset
        val sortColumn = model.sortColumn
        val descending = model.sortDescending
        val filters = filters
        val filtered = filters.any { it.isNotBlank() }
        val seq = ++loadSeq
        background("Loading $tableName") {
            val count = client.rowCount(tableName, filters)
            val page = client.tablePage(tableName, offset, PAGE_SIZE, sortColumn, descending, filters)
            ui {
                if (seq != loadSeq) return@ui
                // A filtered answer names its columns c0, c1, ...; the real ones come from the unfiltered page.
                val data = if (filtered) page.copy(columns = model.data.columns, editable = model.data.editable) else page
                totalRows = count
                showData(data)
                if (this.filters.size != data.columns.size) this.filters = List(data.columns.size) { "" }
                filterRow.show(this.filters)
                updatePager()
                status.text = if (filtered) "$tableName: $count matching rows" else "$tableName: $count rows"
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

    private fun clearTable() {
        offset = 0
        filters = emptyList()
        filterRow.show(emptyList())
        totalRows = 0
        model.data = DebugDbClient.TableData(emptyList(), emptyList(), false)
        updatePager()
    }

    private fun updatePager() {
        val shown = model.rowCount
        pageLabel.text = if (shown == 0) "" else "${offset + 1}–${offset + shown} of $totalRows"
        prevButton.isEnabled = offset > 0
        nextButton.isEnabled = offset + shown < totalRows
    }

    private fun <T> setItems(box: ComboBox<T>, items: List<T>, select: Boolean = true) {
        updatingCombos = true
        try {
            box.removeAllItems()
            items.forEach(box::addItem)
            box.selectedIndex = if (select && items.isNotEmpty()) 0 else -1
        } finally {
            updatingCombos = false
        }
    }

    private fun background(what: String, task: () -> Unit) {
        status.text = "$what…"
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                task()
            } catch (e: Exception) {
                ui { status.text = "$what failed: ${e.message ?: e.javaClass.simpleName}" }
            }
        }
    }

    private fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    override fun dispose() {
        filterTimer.stop()
        devicePoll.stop()
        client = null
    }

    companion object {
        /** Rows per request. Small enough that debug-db's in-memory JSON stays well under the app's heap. */
        const val PAGE_SIZE = 500

        private const val FILTER_DELAY_MS = 400
        private const val DEVICE_POLL_MS = 2000
    }
}
