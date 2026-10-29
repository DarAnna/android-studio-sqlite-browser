package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbException
import io.github.daranna.sqlitebrowser.debugdb.UserSql
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
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
 * The SQL each Run sends is kept as the database's last query in [SavedQueries], and the star saves the editor's SQL
 * there as a favorite.
 */
class QueryView(
    project: Project,
    private val app: String,
    override val database: String,
    sql: String,
    client: DebugDbClient,
    private val onSqlChanged: () -> Unit,
) : BrowserTab() {

    override var client: DebugDbClient = client
        set(value) {
            field = value
            loadPage()
            loadSchema()
        }

    private val editor = SqlEditor(project, sql)
    private val runButton = JButton("Run", AllIcons.Actions.Execute).apply {
        toolTipText = "Run the selected SQL, or all of it (${if (SystemInfo.isMac) "⌘⏎" else "Ctrl+Enter"})"
    }
    private val favoriteButton = JButton("Add to Favorites", AllIcons.Nodes.Favorite).apply {
        toolTipText = "Save this SQL under a name, to open it again from Favorites"
    }
    private val prevButton = JButton("◀")
    private val nextButton = JButton("▶")
    private val pageLabel = JBLabel()
    private val status = JBLabel()

    private val model = TableDataModel()
    private val table = JBTable(model).apply {
        autoResizeMode = JBTable.AUTO_RESIZE_OFF
        setDefaultRenderer(Any::class.java, NullAwareRenderer())
    }

    /** The SELECT whose rows are shown, from the last run; null when that run ended in something else. */
    private var select: String? = null
    private var offset = 0
    private var totalRows = 0L

    /** Counts runs and page loads, so a slow answer doesn't replace a newer one. */
    private var loadSeq = 0

    val sql: String get() = editor.text

    init {
        val top = JPanel(BorderLayout()).apply {
            add(editor.component, BorderLayout.CENTER)
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
                    add(runButton)
                    add(favoriteButton)
                },
                BorderLayout.SOUTH,
            )
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
        val results = JPanel(BorderLayout()).apply {
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        add(
            OnePixelSplitter(true, 0.3f).apply {
                firstComponent = top
                secondComponent = results
            },
            BorderLayout.CENTER,
        )

        runButton.addActionListener { runSql() }
        favoriteButton.addActionListener { addToFavorites() }
        // On the component, so it wins over the keymap's ⌘⏎ (Split Line) in the editor.
        DumbAwareAction.create { runSql() }.registerCustomShortcutSet(
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
        prevButton.addActionListener {
            offset = maxOf(0, offset - TableView.PAGE_SIZE)
            loadPage()
        }
        nextButton.addActionListener {
            offset += TableView.PAGE_SIZE
            loadPage()
        }
        table.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = table.columnAtPoint(e.point)
                if (column < 0 || select == null) return
                model.toggleSort(table.convertColumnIndexToModel(column))
                offset = 0
                loadPage()
            }
        })
        updatePager()
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

    private fun addToFavorites() {
        if (sql.isBlank()) return run { status.text = "Nothing to save" }
        val name = Messages.showInputDialog(
            this,
            "Name. A favorite of that name on $database is replaced.",
            "Add to Favorites",
            null,
            sql.trim().lineSequence().first().take(FAVORITE_NAME_LENGTH),
            NON_BLANK,
        )?.trim() ?: return
        SavedQueries.get().addFavorite(app, database, name, sql)
        status.text = "Saved to Favorites as \"$name\""
    }

    private fun runSql() {
        val text = editor.selectedText?.takeIf { it.isNotBlank() } ?: editor.text
        val parsed = UserSql.parse(text)
        if (parsed.statements.isEmpty()) return run { status.text = "Nothing to run" }
        SavedQueries.get().setLast(app, database, text)
        val client = client
        val seq = ++loadSeq
        // The old rows would page and sort as if they were the new query's.
        select = null
        offset = 0
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
        val offset = offset
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
        totalRows = shown.count
        showKeepingWidths(table, shown.data)
        updatePager()
        status.text = "${shown.count} rows"
    }

    private fun updatePager() {
        val shown = model.rowCount
        pageLabel.text = if (select == null || shown == 0) "" else "${offset + 1}–${offset + shown} of $totalRows"
        prevButton.isEnabled = select != null && offset > 0
        nextButton.isEnabled = select != null && offset + shown < totalRows
    }

    override val title get() = "Query ($database)"

    override val tooltip get() = "SQL on $database"

    override fun savedTab() = SavedSession.Tab(database, query = sql)

    override fun dispose() = Unit

    private companion object {
        val SCHEMA_CHANGES = setOf("CREATE", "DROP", "ALTER")
        const val FAVORITE_NAME_LENGTH = 40
    }
}
