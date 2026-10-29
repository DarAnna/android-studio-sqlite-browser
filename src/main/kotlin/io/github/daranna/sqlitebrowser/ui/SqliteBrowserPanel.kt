package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.adb.Adb
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * A toolbar on top: the connected app as a dropdown of devices and the port, then reconnect and the query actions.
 * Work in progress shows at the toolbar's right end, failures in a banner under it.
 * Below, the app's databases and tables on the left, and a tab per opened table or query on the right,
 * or, with no tab open, what to do next.
 *
 * The connection and tabs are kept in [saved]. When the saved device shows up, the panel reconnects
 * if the saved app still answers on the saved port, and reopens the tabs the app still has.
 *
 * All adb and HTTP calls run on a pooled thread; Swing components are only touched on the EDT.
 */
class SqliteBrowserPanel(private val project: Project, parent: Disposable, private val saved: SavedSession.Session) :
    JPanel(BorderLayout()),
    Disposable {

    private data class Connection(val device: Adb.Device, val port: Int, val owner: String)

    private var devices: List<Adb.Device> = emptyList()

    /** Why the last device listing failed, such as no adb; shown while there is no device. */
    private var devicesError: String? = null

    /** The device last connected to or picked, kept while it is listed. */
    private var device: Adb.Device? = null

    private val progress = JBLabel(AnimatedIcon.Default.INSTANCE).apply {
        border = JBUI.Borders.emptyRight(8)
        isVisible = false
    }
    private val bannerHolder = JPanel(BorderLayout())
    private val status = PanelStatus()

    private val tree = DatabaseTree(status, ::openTable, ::openQuery)
    private val tabs = JBTabbedPane()
    private val emptyTabs = JBPanelWithEmptyText()
    private val contentCards = CardLayout()
    private val content = JPanel(contentCards)

    private var client: DebugDbClient? = null

    /** A tab opened by following a foreign key, to the tab it was followed from. */
    private val openedFrom = mutableMapOf<BrowserTab, BrowserTab>()
    private var connection: Connection? = null

    /** Whether to reconnect to the saved app once its device is listed. Only tried once. */
    private var restorePending = saved.app != null

    /** Devices come and go, such as an emulator started after this panel opened. */
    private val devicePoll = Timer(DEVICE_POLL_MS) { if (isShowing) pollDevices() }
    private val polling = AtomicBoolean()

    init {
        Disposer.register(parent, this)

        val actions = DefaultActionGroup(
            ConnectionAction(),
            ReconnectAction(),
            Separator.getInstance(),
            NewQueryAction(),
        )
        val toolbar = ActionManager.getInstance().createActionToolbar(TOOLBAR_PLACE, actions, true).apply {
            targetComponent = this@SqliteBrowserPanel
        }
        bannerHolder.border = JBUI.Borders.empty(0, 8, 4, 8)
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.CENTER)
            add(progress, BorderLayout.EAST)
            add(bannerHolder, BorderLayout.SOUTH)
        }

        content.add(emptyTabs, EMPTY)
        content.add(tabs, TABS)
        val splitter = OnePixelSplitter(false, 0.2f).apply {
            firstComponent = JBScrollPane(tree.component)
            secondComponent = content
        }

        add(top, BorderLayout.NORTH)
        add(splitter, BorderLayout.CENTER)

        tabs.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!SwingUtilities.isMiddleMouseButton(e)) return
                val index = tabs.indexAtLocation(e.x, e.y)
                if (index >= 0) closeTab(tabs.getComponentAt(index) as BrowserTab)
            }
        })
        tabs.addChangeListener { saveTabs() }
        showContent()

        pollDevices()
        devicePoll.start()
    }

    private fun pollDevices() {
        if (!polling.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val listed = try {
                Result.success(Adb.devices())
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                polling.set(false)
            }
            ui {
                val found = listed.getOrDefault(emptyList())
                val error = listed.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
                if (found == devices && error == devicesError) return@ui
                devices = found
                devicesError = error
                device = found.firstOrNull { it.serial == device?.serial }
                    ?: found.firstOrNull { it.serial == saved.serial }
                    ?: found.firstOrNull()
                showContent()
                restore()
            }
        }
    }

    /** Reconnects to the saved app if its device is listed now. */
    private fun restore() {
        if (!restorePending) return
        val device = devices.firstOrNull { it.serial == saved.serial } ?: return
        restorePending = false
        connect(device, saved.port, quiet = true)
    }

    /**
     * Connects to the app on [device] and [port]. Tabs stay open when it is the same app,
     * with the new client, since a restarted app's server has forgotten which database was open.
     *
     * The first connection to the saved app reopens its saved tabs. With [quiet], connects only to the
     * saved app, and sends it nothing if another app holds the port.
     */
    private fun connect(device: Adb.Device, port: Int, quiet: Boolean = false) {
        this.device = device
        val savedApp = saved.app
        val savedTabs = if (connection == null && views().isEmpty()) saved.tabs.toList() else emptyList()
        val savedSelection = saved.selectedTab
        background(status, "Connecting to $device") {
            val owner = Adb.portOwner(device.serial, port)
            if (quiet && owner != savedApp) {
                return@background ui {
                    status.done()
                    status.show(
                        EditorNotificationPanel.Status.Warning,
                        "${shortName(savedApp.orEmpty())} isn't running on $device, port $port. Start it, then reconnect.",
                    )
                }
            }
            owner ?: error("No app listens on port $port. Is the debug app running, with debug-db?")
            Adb.forward(device.serial, port)
            val newClient = DebugDbClient(port)
            val databases = newClient.databases()
            val names = databases.map { it.name }
            val reopen = if (owner == savedApp) SavedSession.reopenable(savedTabs, names, newClient::tables) else emptyList()
            ui {
                val newConnection = Connection(device, port, owner)
                val sameApp = newConnection == connection
                client = newClient
                connection = newConnection
                saved.serial = device.serial
                saved.port = port
                saved.app = owner
                tree.show(newClient, databases)
                if (sameApp) views().forEach { it.client = newClient } else views().forEach(::closeTab)
                reopen.forEach { tab -> tab.query?.let { openQuery(tab.database, sql = it) } ?: openTable(tab.database, tab.table) }
                views().firstOrNull { it.savedTab() == savedSelection }?.let(tabs::setSelectedComponent)
                // Drops another app's saved tabs when nothing is open, which no tab change would.
                saveTabs()
                status.done()
                showContent()
            }
        }
    }

    private fun reconnect() {
        val device = device ?: return
        connect(device, connection?.port ?: saved.port)
    }

    private fun askPort() {
        val text = Messages.showInputDialog(
            project,
            "The port debug-db listens on in the app. Each debug app on a device needs its own.",
            "Port",
            null,
            (connection?.port ?: saved.port).toString(),
            object : InputValidator {
                override fun checkInput(text: String?) = text?.trim()?.toIntOrNull() in 1..65535
                override fun canClose(text: String?) = checkInput(text)
            },
        ) ?: return
        val port = text.trim().toInt()
        saved.port = port
        device?.let { connect(it, port) }
    }

    /** The tabs, or with none open, a hint at what to do. */
    private fun showContent() {
        if (tabs.tabCount > 0) return contentCards.show(content, TABS)
        val text = emptyTabs.emptyText.clear()
        val device = device
        when {
            connection != null -> text.appendText("Double-click a table to open it").appendLine("or press New Query to write SQL")

            device != null -> {
                text.appendText("Not connected")
                text.appendLine("Connect to $device", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { connect(device, saved.port) }
            }

            devicesError != null -> text.appendText("Can't list devices").appendLine(devicesError!!)

            else -> text.appendText("No device").appendLine("Start an emulator or plug in a phone")
        }
        contentCards.show(content, EMPTY)
        emptyTabs.repaint()
    }

    /** Opens [table] in a new tab, or selects its tab if it is open already. */
    private fun openTable(database: String, table: String) {
        views().firstOrNull {
            it is TableView && it.database == database && it.tableName == table
        }?.let { return tabs.setSelectedComponent(it) }
        tableView(database, table)?.let(::addTab)
    }

    private fun tableView(database: String, table: String): TableView? {
        val client = client ?: return null
        lateinit var view: TableView
        view = TableView(database, table, client) { key, value -> followForeignKey(view, key, value) }
        return view
    }

    /**
     * Opens the rows [key] leads to from [from] in a new tab, even when the table is open already, so the filters there
     * stay as they are. Closing the new tab goes back to [from].
     */
    private fun followForeignKey(from: TableView, key: DebugDbClient.ForeignKey, value: Any) {
        val view = tableView(from.database, key.targetTable) ?: return
        openedFrom[view] = from
        addTab(view)
        view.filterBy(key.targetColumn, value)
    }

    /**
     * Opens a new query tab on [database], with [sql] in it; by default a SELECT of [table], or with no table the SQL
     * last run on [database]. It doesn't run yet: saved SQL may hold writes.
     */
    private fun openQuery(database: String, table: String? = null, sql: String? = null): QueryView? {
        val client = client ?: return null
        val app = connection?.owner ?: return null
        val text = sql ?: table?.let { "SELECT * FROM $it" } ?: SavedQueries.get().lastQuery(app, database).orEmpty()
        val view = QueryView(project, app, database, text, client, ::saveTabs) { openQuery(database, sql = it)?.runIfReadOnly() }
        addTab(view)
        return view
    }

    private fun addTab(view: BrowserTab) {
        Disposer.register(this, view)
        tabs.addTab(view.title, view)
        val index = tabs.indexOfComponent(view)
        tabs.setToolTipTextAt(index, view.tooltip)
        tabs.setTabComponentAt(index, tabHeader(view))
        tabs.selectedIndex = index
        showContent()
    }

    private fun tabHeader(view: BrowserTab) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        add(JBLabel(view.title).apply { border = JBUI.Borders.emptyRight(4) })
        add(InplaceButton("Close", AllIcons.Actions.Close) { closeTab(view) })
    }

    private fun closeTab(view: BrowserTab) {
        val back = openedFrom.remove(view)?.takeIf { tabs.indexOfComponent(it) >= 0 }
        openedFrom.values.removeAll { it == view }
        tabs.remove(view)
        back?.let { tabs.setSelectedComponent(it) }
        Disposer.dispose(view)
        showContent()
    }

    /** Also called as the user types in a query tab, so the saved SQL stays current. */
    private fun saveTabs() {
        saved.tabs = views().map { it.savedTab() }.toMutableList()
        saved.selectedTab = (tabs.selectedComponent as? BrowserTab)?.savedTab()
    }

    private fun views(): List<BrowserTab> = (0 until tabs.tabCount).map { tabs.getComponentAt(it) as BrowserTab }

    override fun dispose() {
        devicePoll.stop()
        client = null
    }

    /** Work in progress at the toolbar's right end; failures and warnings in a banner under the toolbar. */
    private inner class PanelStatus : Status {
        override fun busy(what: String) {
            hideBanner()
            progress.text = "$what…"
            progress.isVisible = true
        }

        override fun failed(message: String) {
            done()
            show(EditorNotificationPanel.Status.Error, message)
        }

        override fun done() {
            progress.isVisible = false
        }

        fun show(kind: EditorNotificationPanel.Status, message: String) {
            val banner = InlineBanner(message, kind).setCloseAction(::hideBanner)
            if (device != null) banner.addAction("Reconnect", ::reconnect)
            bannerHolder.removeAll()
            bannerHolder.add(banner)
            bannerHolder.revalidate()
            bannerHolder.repaint()
        }

        private fun hideBanner() {
            bannerHolder.removeAll()
            bannerHolder.revalidate()
            bannerHolder.repaint()
        }
    }

    /** The connected app, with a dot for whether it is; opens a list of devices and the port. */
    private inner class ConnectionAction : ComboBoxAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val connection = connection
            e.presentation.text = connection?.let { shortName(it.owner) } ?: "Not connected"
            e.presentation.icon = if (connection != null) CONNECTED_DOT else DISCONNECTED_DOT
            e.presentation.description = connection?.let {
                "${it.owner} on ${it.device}, port ${it.port}. " +
                    "Not the app you meant? Another debug-db app holds the port; give each app its own."
            } ?: "Choose a device to connect to"
        }

        override fun createPopupActionGroup(button: JComponent, dataContext: DataContext) = DefaultActionGroup().apply {
            if (devices.isEmpty()) add(InfoAction("No device"))
            devices.forEach { add(DeviceAction(it)) }
            addSeparator()
            add(object : DumbAwareAction("Port ${connection?.port ?: saved.port}…", "Change the port debug-db listens on", null) {
                override fun getActionUpdateThread() = ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = askPort()
            })
        }
    }

    private inner class DeviceAction(private val target: Adb.Device) : DumbAwareAction(target.toString()) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.icon = if (target.serial == connection?.device?.serial) AllIcons.Actions.Checked else null
        }

        override fun actionPerformed(e: AnActionEvent) = connect(target, connection?.port ?: saved.port)
    }

    private class InfoAction(text: String) : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = false
        }

        override fun actionPerformed(e: AnActionEvent) = Unit
    }

    private inner class ReconnectAction : DumbAwareAction(AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.text = if (connection == null) "Connect" else "Reconnect"
            e.presentation.isEnabled = device != null && !progress.isVisible
        }

        override fun actionPerformed(e: AnActionEvent) = reconnect()
    }

    private inner class NewQueryAction :
        DumbAwareAction(
            "New Query",
            "Write SQL for the selected table, or start from the SQL last run on the selected database",
            AllIcons.General.Add,
        ) {
        init {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = connection != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val (database, table) = tree.queryTarget()
                ?: return status.show(EditorNotificationPanel.Status.Info, "The app has no database to query.")
            openQuery(database, table)
        }
    }

    private class DotIcon(private val color: Color) : Icon {
        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                GraphicsUtil.setupAAPainting(g2)
                g2.color = color
                val size = JBUI.scale(DOT_SIZE)
                g2.fillOval(x + (iconWidth - size) / 2, y + (iconHeight - size) / 2, size, size)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth() = JBUI.scale(ICON_SIZE)
        override fun getIconHeight() = JBUI.scale(ICON_SIZE)
    }

    internal companion object {
        private const val TOOLBAR_PLACE = "SqliteBrowserToolbar"
        private const val EMPTY = "empty"
        private const val TABS = "tabs"
        private const val DEVICE_POLL_MS = 2000
        private const val DOT_SIZE = 8
        private const val ICON_SIZE = 16
        private val CONNECTED_DOT = DotIcon(JBColor(0x369650, 0x5FAD65))
        private val DISCONNECTED_DOT = DotIcon(JBColor(0xA8ADBD, 0x6F737A))

        /** Build variants that end a package name but don't name the app, as in `com.speedy.debug`. */
        private val VARIANT_SUFFIXES = setOf("debug", "dev", "staging", "qa", "internal", "beta")

        /** The package's last part, or the last two when the last is a build variant. */
        fun shortName(owner: String): String {
            val parts = owner.split('.')
            return if (parts.size > 1 && parts.last() in VARIANT_SUFFIXES) parts.takeLast(2).joinToString(".") else parts.last()
        }
    }
}
