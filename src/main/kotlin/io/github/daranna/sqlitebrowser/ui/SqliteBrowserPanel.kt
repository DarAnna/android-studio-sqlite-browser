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
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Device and port on top, then database and table pickers, then the rows.
 *
 * All adb and HTTP calls run on a pooled thread; Swing components are only touched on the EDT.
 */
class SqliteBrowserPanel(parent: Disposable) : JPanel(BorderLayout()), Disposable {

    private val deviceBox = ComboBox<Adb.Device>()
    private val portField = JBTextField("8080", 5)
    private val connectButton = JButton("Connect")
    private val databaseBox = ComboBox<String>()
    private val tableBox = ComboBox<String>()
    private val refreshButton = JButton("Refresh")
    private val status = JBLabel()

    private val model = TableDataModel()
    private val table = JBTable(model).apply {
        autoCreateRowSorter = true
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
    }

    private var client: DebugDbClient? = null

    /** Set while we fill the combo boxes ourselves, so their listeners don't fire requests. */
    private var updatingCombos = false

    init {
        Disposer.register(parent, this)

        val top = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel("Device:"))
            add(deviceBox)
            add(JBLabel("Port:"))
            add(portField)
            add(connectButton)
            add(JBLabel("Database:"))
            add(databaseBox)
            add(JBLabel("Table:"))
            add(tableBox)
            add(refreshButton)
        }
        status.border = JBUI.Borders.empty(2, 6)

        add(top, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        connectButton.addActionListener { connect() }
        databaseBox.addActionListener { if (!updatingCombos) loadTables() }
        tableBox.addActionListener { if (!updatingCombos) loadRows() }
        refreshButton.addActionListener { loadRows() }

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
        background("Connecting to ${device.serial}:$port") {
            Adb.forward(device.serial, port)
            val newClient = DebugDbClient(port)
            val databases = newClient.databases()
            client = newClient
            ui {
                setItems(databaseBox, databases.map { it.name }, select = false)
                setItems(tableBox, emptyList())
                model.data = DebugDbClient.TableData(emptyList(), emptyList(), false)
                status.text = "Connected. ${databases.size} databases."
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
                status.text = "$database: ${tables.size} tables"
            }
        }
    }

    private fun loadRows() {
        val client = client ?: return
        val tableName = tableBox.item ?: return
        background("Loading $tableName") {
            val data = client.tableData(tableName)
            ui {
                model.data = data
                status.text = "$tableName: ${data.rows.size} rows" + if (data.editable) "" else " (read-only)"
            }
        }
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

    private fun ui(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(block, ModalityState.any())

    override fun dispose() {
        client = null
    }
}
