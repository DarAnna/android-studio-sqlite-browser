package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.InplaceButton
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.daranna.sqlitebrowser.adb.Adb
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * A connection bar on top: device and port until connected, then just the app's name.
 * Below it, the app's databases and tables on the left, and a tab per opened table on the right.
 *
 * The connection and tabs are kept in [saved]. When the saved device shows up, the panel reconnects
 * if the saved app still answers on the saved port, and reopens the tabs the app still has.
 *
 * All adb and HTTP calls run on a pooled thread; Swing components are only touched on the EDT.
 */
class SqliteBrowserPanel(parent: Disposable, private val saved: SavedSession.Session) :
    JPanel(BorderLayout()),
    Disposable {

    private data class Connection(val serial: String, val port: Int, val owner: String)

    private val deviceBox = ComboBox<Adb.Device>()
    private val portField = JBTextField(saved.port.toString(), 5)
    private val connectButton = JButton("Connect")
    private val cancelLink = ActionLink("Cancel") { showCard(CONNECTED) }

    /** The app that answers on the port, which may not be the one the user means. */
    private val appLabel = JBLabel()
    private val whereLabel = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val reconnectLink = ActionLink("Reconnect") { connect() }
    private val changeLink = ActionLink("Change…") {
        showCard(SETUP)
        loadDevices()
    }

    private val cards = CardLayout()
    private val connectionBar = JPanel(cards)
    private val status = JBLabel()

    private val tree = DatabaseTree(status, ::openTable)
    private val tabs = JBTabbedPane()

    private var client: DebugDbClient? = null
    private var connection: Connection? = null

    /** Whether to reconnect to the saved app once its device is listed. Only tried once. */
    private var restorePending = saved.app != null

    /** While no device is listed, looks again: the emulator may have started after this panel opened. */
    private val devicePoll = Timer(DEVICE_POLL_MS) { if (deviceBox.itemCount == 0) pollDevices() }

    init {
        Disposer.register(parent, this)

        connectionBar.add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JBLabel("Device:"))
                add(deviceBox)
                add(JBLabel("Port:"))
                add(portField)
                add(connectButton)
                add(cancelLink)
            },
            SETUP,
        )
        connectionBar.add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(appLabel)
                add(whereLabel)
                add(reconnectLink)
                add(changeLink)
            },
            CONNECTED,
        )
        status.border = JBUI.Borders.emptyLeft(6)
        val top = JPanel(BorderLayout()).apply {
            add(connectionBar, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(false, 0.2f).apply {
            firstComponent = JBScrollPane(tree.component)
            secondComponent = tabs
        }

        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        connectButton.addActionListener { connect() }
        portField.addActionListener { connect() }
        tabs.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!SwingUtilities.isMiddleMouseButton(e)) return
                val index = tabs.indexAtLocation(e.x, e.y)
                if (index >= 0) closeTab(tabs.getComponentAt(index) as TableView)
            }
        })
        tabs.addChangeListener { saveTabs() }
        showCard(SETUP)

        loadDevices()
        devicePoll.start()
    }

    private fun showCard(name: String) {
        cards.show(connectionBar, name)
        cancelLink.isVisible = connection != null
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
                    showDevices(devices)
                    status.text = "Pick a device and press Connect."
                    restore()
                }
            }
        }
    }

    private fun loadDevices() = background(status, "Looking for devices") {
        val devices = Adb.devices()
        ui {
            showDevices(devices)
            status.text = if (devices.isEmpty()) "No device. Start an emulator or plug in a phone." else "Pick a device and press Connect."
            restore()
        }
    }

    /** Reconnects to the saved app if its device is listed now. */
    private fun restore() {
        if (!restorePending) return
        val device = (0 until deviceBox.itemCount).map(deviceBox::getItemAt).firstOrNull { it.serial == saved.serial } ?: return
        restorePending = false
        deviceBox.item = device
        connect(quiet = true)
    }

    /** Keeps the selected device if it is still there. */
    private fun showDevices(devices: List<Adb.Device>) {
        val selected = deviceBox.item
        deviceBox.removeAllItems()
        devices.forEach(deviceBox::addItem)
        deviceBox.selectedIndex = if (devices.isEmpty()) -1 else devices.indexOf(selected).coerceAtLeast(0)
    }

    /**
     * Connects to the app on the chosen device and port. Tabs stay open when it is the same app,
     * with the new client, since a restarted app's server has forgotten which database was open.
     *
     * The first connection to the saved app reopens its saved tabs. With [quiet], connects only to the
     * saved app, and sends it nothing if another app holds the port.
     */
    private fun connect(quiet: Boolean = false) {
        val device = deviceBox.item ?: return loadDevices()
        val port = portField.text.trim().toIntOrNull() ?: return run { status.text = "Port must be a number" }
        val savedApp = saved.app
        val savedTabs = if (connection == null && views().isEmpty()) saved.tabs.toList() else emptyList()
        val savedSelection = saved.selectedTab
        background(status, "Connecting to ${device.serial}:$port") {
            val owner = Adb.portOwner(device.serial, port)
            if (quiet && owner != savedApp) {
                return@background ui { status.text = "$savedApp is not running on ${device.serial}:$port. Start it and press Connect." }
            }
            owner ?: error("No app on ${device.serial} listens on port $port. Is the debug app running, with debug-db?")
            Adb.forward(device.serial, port)
            val newClient = DebugDbClient(port)
            val databases = newClient.databases()
            val names = databases.map { it.name }
            val reopen = if (owner == savedApp) SavedSession.reopenable(savedTabs, names, newClient::tables) else emptyList()
            ui {
                val newConnection = Connection(device.serial, port, owner)
                val sameApp = newConnection == connection
                client = newClient
                connection = newConnection
                saved.serial = device.serial
                saved.port = port
                saved.app = owner
                tree.show(newClient, databases)
                if (sameApp) views().forEach { it.client = newClient } else views().forEach(::closeTab)
                reopen.forEach { openTable(it.database, it.table) }
                views().firstOrNull { SavedSession.Tab(it.database, it.tableName) == savedSelection }?.let(tabs::setSelectedComponent)
                // Drops another app's saved tabs when nothing is open, which no tab change would.
                saveTabs()
                appLabel.text = "App: $owner"
                whereLabel.text = "${device.serial}:$port"
                appLabel.toolTipText = "Not the app you meant? Another debug-db app holds port $port; give each app its own port."
                showCard(CONNECTED)
                status.text = "${databases.size} databases. Double-click a table to open it."
            }
        }
    }

    /** Opens [table] in a new tab, or selects its tab if it is open already. */
    private fun openTable(database: String, table: String) {
        val client = client ?: return
        views().firstOrNull { it.database == database && it.tableName == table }?.let { return tabs.setSelectedComponent(it) }
        val view = TableView(database, table, client)
        Disposer.register(this, view)
        tabs.addTab(table, view)
        val index = tabs.indexOfComponent(view)
        tabs.setToolTipTextAt(index, "$database › $table")
        tabs.setTabComponentAt(index, tabHeader(table, view))
        tabs.selectedIndex = index
    }

    private fun tabHeader(title: String, view: TableView) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        add(JBLabel(title).apply { border = JBUI.Borders.emptyRight(4) })
        add(InplaceButton("Close", AllIcons.Actions.Close) { closeTab(view) })
    }

    private fun closeTab(view: TableView) {
        tabs.remove(view)
        Disposer.dispose(view)
    }

    private fun saveTabs() {
        saved.tabs = views().map { SavedSession.Tab(it.database, it.tableName) }.toMutableList()
        saved.selectedTab = (tabs.selectedComponent as? TableView)?.let { SavedSession.Tab(it.database, it.tableName) }
    }

    private fun views(): List<TableView> = (0 until tabs.tabCount).map { tabs.getComponentAt(it) as TableView }

    override fun dispose() {
        devicePoll.stop()
        client = null
    }

    private companion object {
        const val SETUP = "setup"
        const val CONNECTED = "connected"
        const val DEVICE_POLL_MS = 2000
    }
}
