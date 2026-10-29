package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.DebugDbException
import io.github.daranna.sqlitebrowser.debugdb.UserSql
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent

/**
 * SQL the user writes against one database, and what it returns. The content of a tab.
 *
 * Run sends the statements one at a time, rewritten by [UserSql] so debug-db leaves them alone. When the last one is
 * a SELECT, its rows are paged and sorted in SQL like a table's. Paging and sorting run only that SELECT again, never
 * the statements before it, which may be writes.
 */
class QueryView(override val database: String, sql: String, client: DebugDbClient, private val onSqlChanged: () -> Unit) :
    BrowserTab() {

    override var client: DebugDbClient = client
        set(value) {
            field = value
            loadPage()
        }

    private val editor = JBTextArea(sql, 6, 0).apply {
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(4)
    }
    private val runButton = JButton("Run", AllIcons.Actions.Execute).apply {
        toolTipText = "Run the selected SQL, or all of it (${if (SystemInfo.isMac) "⌘⏎" else "Ctrl+Enter"})"
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
            add(JBScrollPane(editor), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply { add(runButton) }, BorderLayout.SOUTH)
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
        val runSql = object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = runSql()
        }
        listOf(InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK).forEach {
            editor.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, it), "run")
        }
        editor.actionMap.put("run", runSql)
        editor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = onSqlChanged()
        })
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
    }

    private fun runSql() {
        val parsed = UserSql.parse(editor.selectedText?.takeIf { it.isNotBlank() } ?: editor.text)
        if (parsed.statements.isEmpty()) return run { status.text = "Nothing to run" }
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
}
