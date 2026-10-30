package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.table.JBTable
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbException
import io.github.daranna.sqlitebrowser.debugdb.UserSql
import java.awt.BorderLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * SQL the user writes against one database, and what it returns. The content of a tab.
 *
 * Run sends the statements one at a time, rewritten by [UserSql] so debug-db leaves them alone. When the last one is
 * a SELECT, its rows are paged and sorted in SQL like a table's. Paging and sorting run only that SELECT again, never
 * the statements before it, which may be writes.
 *
 * The editor completes the database's table and column names, loaded when the tab opens, on reconnect, and after
 * SQL that changes the schema.
 *
 * The SQL each Run sends is kept in the database's recent queries in [SavedQueries], and the star keeps the editor's
 * SQL there as a favorite. Both are listed beside the editor, in a [SavedQueryList]; a click on one runs it right
 * away when it only reads, and otherwise only puts it in the editor, since it may hold writes.
 */
class QueryView(
    project: Project,
    private val app: String,
    override val database: String,
    sql: String,
    client: DebugDbClient,
    private val onSqlChanged: () -> Unit,
    /** Opens SQL in a new query tab on the same database, for a saved query that would replace edited SQL here. */
    private val openInNewTab: (String) -> Unit,
) : BrowserTab() {

    override var client: DebugDbClient = client
        set(value) {
            field = value
            loadPage()
            loadSchema()
        }

    private val editor = SqlEditor(project, sql)

    /** In place of the saved queries while they are hidden, at the same edge, to bring them back. */
    private val savedStripe = FoldedStripe(AllIcons.Actions.ListFiles, "Saved queries") { setSavedShown(true) }
    private val savedList = SavedQueryList(app, database, this, onHide = { setSavedShown(false) }, onOpen = ::openSaved)
    private val sides = OnePixelSplitter(false, "sqliteBrowser.savedQueries.proportion", 0.25f)
    private val status = StatusLabel()

    private val model = TableDataModel()
    private val table = JBTable(model).apply {
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
        setDefaultRenderer(Any::class.java, NullAwareRenderer())
        tableHeader.defaultRenderer = HeaderRenderer(tableHeader.defaultRenderer)
    }

    /** The SELECT whose rows are shown, from the last run; null when that run ended in something else. */
    private var select: String? = null
    private val pager = Pager({ select != null }, ::loadPage)

    /** Counts runs and page loads, so a slow answer doesn't replace a newer one. */
    private var loadSeq = 0

    val sql: String get() = editor.text

    init {
        val run = RunAction()
        val top = JPanel(BorderLayout()).apply {
            add(tabBar(toolbar(this@QueryView, listOf(run, FavoriteAction())), null), BorderLayout.NORTH)
            add(editor.component, BorderLayout.CENTER)
        }
        val results = JPanel(BorderLayout()).apply {
            add(tabBar(null, status, toolbar(this@QueryView, pager.actions)), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(table, true), BorderLayout.CENTER)
        }
        sides.firstComponent = savedList.component
        sides.secondComponent = OnePixelSplitter(true, 0.3f).apply {
            firstComponent = top
            secondComponent = results
        }
        add(savedStripe, BorderLayout.WEST)
        add(sides, BorderLayout.CENTER)

        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            SavedQueries.TOPIC,
            SavedQueries.Listener { showSaved() },
        )
        // On the component, so it wins over the keymap's ⌘⏎ (Split Line) in the editor.
        run.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), null),
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), null),
            ),
            editor.component,
            this,
        )
        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) = onSqlChanged()
            },
            this,
        )
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = table.columnAtPoint(e.point)
                if (column < 0 || select == null) return
                model.toggleSort(table.convertColumnIndexToModel(column))
                pager.offset = 0
                loadPage()
            }
        })
        showSaved()
        status.text = "SQL on $database. Press Run."
        loadSchema()
    }

    /** Leaves the old names when it fails: completion without them is still an editor, and Run reports the error. */
    private fun loadSchema() {
        val client = client
        ApplicationManager.getApplication().executeOnPooledThread {
            val tables = try {
                client.withDatabase(database) { tables(database).associateWith { columns(it) } }
            } catch (_: Exception) {
                return@executeOnPooledThread
            }
            ui { editor.setSchema(tables) }
        }
    }

    private fun toggleFavorite() {
        if (sql.isBlank()) return run { status.text = "Nothing to save" }
        val favorite = SavedQueries.get().favoriteOf(app, database, sql)
        if (favorite != null) {
            SavedQueries.change { delete(favorite) }
            status.text = "Removed \"${favorite.name}\" from Favorites"
        } else {
            val added = SavedQueries.change { addFavorite(app, database, sql) }
            status.text = "Saved to Favorites as \"${added.name}\". Right-click it in the list to rename."
        }
    }

    private fun setSavedShown(shown: Boolean) {
        savedShown = shown
        SavedQueries.changed()
    }

    private fun showSaved() {
        savedList.component.isVisible = savedShown
        savedStripe.isVisible = !savedShown
        revalidate()
    }

    private inner class RunAction : DumbAwareAction("Run", "Run the selected SQL, or all of it", AllIcons.Actions.Execute) {
        init {
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun actionPerformed(e: AnActionEvent) = runSql()
    }

    /** The star: whether the editor's SQL is a favorite, and a click to make it one or not. */
    private inner class FavoriteAction : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val favorite = sql.isNotBlank() && SavedQueries.get().favoriteOf(app, database, sql) != null
            e.presentation.icon = if (favorite) AllIcons.Nodes.Favorite else AllIcons.Nodes.NotFavoriteOnHover
            e.presentation.text = if (favorite) "Remove from Favorites" else "Add to Favorites"
        }

        override fun actionPerformed(e: AnActionEvent) = toggleFavorite()
    }

    /**
     * Puts [query] in this editor when that loses nothing: it is empty, or holds SQL that is saved too. Otherwise in a
     * new tab, so edited SQL stays.
     */
    private fun openSaved(query: SavedQueries.Query) {
        val queries = SavedQueries.get()
        val saved = queries.favoriteOf(app, database, sql) != null || queries.recentOf(app, database).any { it.sql.trim() == sql.trim() }
        if (sql.isNotBlank() && !saved) return openInNewTab(query.sql)
        editor.component.text = query.sql
        runIfReadOnly()
    }

    /** Runs the editor's SQL if it can't change the app's data; otherwise says why it waits for Run. */
    fun runIfReadOnly() {
        if (UserSql.parse(sql).readOnly) {
            runSql()
        } else {
            status.text = "This SQL may change data. Press Run to run it."
        }
    }

    private fun runSql() {
        val text = editor.selectedText?.takeIf { it.isNotBlank() } ?: editor.text
        val parsed = UserSql.parse(text)
        if (parsed.statements.isEmpty()) return run { status.text = "Nothing to run" }
        SavedQueries.change { addRecent(app, database, text) }
        val client = client
        val seq = ++loadSeq
        // The old rows would page and sort as if they were the new query's.
        select = null
        pager.offset = 0
        model.sortColumn = null
        showKeepingWidths(table, DebugDbClient.TableData(emptyList(), emptyList(), false))
        updatePager()
        background(status, "Running") {
            val shown = client.withDatabase(database) {
                val last = parsed.leading.mapIndexed { i, statement ->
                    try {
                        query(statement)
                    } catch (e: DebugDbException) {
                        if (parsed.statements.size == 1) throw e
                        throw DebugDbException("statement ${i + 1} of ${parsed.statements.size}: ${e.message}")
                    }
                }.lastOrNull()
                val select = parsed.select ?: return@withDatabase Shown(last!!, null, 0)
                Shown(selectPage(select, 0, TableView.PAGE_SIZE), select, selectCount(select))
            }
            ui {
                if (parsed.statements.any { it.substringBefore(' ').uppercase() in SCHEMA_CHANGES }) loadSchema()
                if (seq != loadSeq) return@ui
                show(shown)
                if (shown.select == null) {
                    val ran = parsed.statements.size.let { if (it == 1) "1 statement" else "$it statements" }
                    status.text = if (shown.data.columns.isEmpty()) "Ran $ran" else "${shown.data.rows.size} rows"
                }
            }
        }
    }

    private fun loadPage() {
        val select = select ?: return
        val client = client
        val offset = pager.offset
        val sortColumn = model.sortColumn
        val descending = model.sortDescending
        val seq = ++loadSeq
        background(status, "Loading") {
            val shown = client.withDatabase(database) {
                Shown(selectPage(select, offset, TableView.PAGE_SIZE, sortColumn, descending), select, selectCount(select))
            }
            ui { if (seq == loadSeq) show(shown) }
        }
    }

    private class Shown(val data: DebugDbClient.TableData, val select: String?, val count: Long)

    private fun show(shown: Shown) {
        select = shown.select
        pager.total = shown.count
        showKeepingWidths(table, shown.data)
        updatePager()
        status.text = ""
    }

    private fun updatePager() {
        pager.shown = model.rowCount
        pager.update()
    }

    override val title get() = "Query ($database)"

    override val tooltip get() = "SQL on $database"

    override val icon: Icon get() = AllIcons.Nodes.DataSchema

    override val preferredFocus: JComponent get() = editor.component

    override fun savedTab() = SavedSession.Tab(database, query = sql)

    override fun dispose() = Unit

    private companion object {
        val SCHEMA_CHANGES = setOf("CREATE", "DROP", "ALTER")

        const val SAVED_SHOWN = "sqliteBrowser.savedQueries.shown"

        /** Whether query tabs show [SavedQueryList], the same in every tab. */
        var savedShown: Boolean
            get() = PropertiesComponent.getInstance().getBoolean(SAVED_SHOWN, true)
            set(value) = PropertiesComponent.getInstance().setValue(SAVED_SHOWN, value, true)
    }
}
