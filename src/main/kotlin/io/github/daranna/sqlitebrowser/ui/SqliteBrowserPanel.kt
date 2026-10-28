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
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel

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
    private val table = JBTable(model).apply {
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
    }

    private var client: DebugDbClient? = null

    /** Set while we fill the combo boxes ourselves, so their listeners don't fire requests. */
    private var updatingCombos = false

    // Paging and sorting of the current table. Sorting happens in SQL, so it covers all rows, not just the page.
    private var offset = 0
    private var totalRows = 0L

    init {
        Disposer.register(parent, this)

        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Device:"))
            add(deviceBox)
            add(JBLabel("Port:"))
            add(portField)
            add(connectButton)
            add(appLabel)
            add(JBLabel("Database:"))
            add(databaseBox)
            add(JBLabel("Table:"))
            add(tableBox)
            add(refreshButton)
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

    /** A new table: back to the first page, no sorting. */
    private fun openTable() {
        offset = 0
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
        background("Loading $tableName") {
            val count = client.rowCount(tableName)
            val data = client.tablePage(tableName, offset, PAGE_SIZE, sortColumn, descending)
            ui {
                totalRows = count
                model.data = data
                updatePager()
                status.text = "$tableName: $count rows" + if (data.editable) "" else " (read-only)"
            }
        }
    }

    private fun clearTable() {
        offset = 0
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
        client = null
    }

    companion object {
        /** Rows per request. Small enough that debug-db's in-memory JSON stays well under the app's heap. */
        const val PAGE_SIZE = 500
    }
}
