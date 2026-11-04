package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import java.awt.GridBagLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.DocumentEvent

/** Appends [text] with the first place it holds [search], ignoring case, marked the way IntelliJ marks speed search matches. */
internal fun SimpleColoredComponent.appendHighlighted(
    text: String,
    search: String,
    attributes: SimpleTextAttributes = SimpleTextAttributes.REGULAR_ATTRIBUTES,
) {
    val start = if (search.isEmpty()) -1 else text.indexOf(search, ignoreCase = true)
    if (start < 0) return append(text, attributes)
    val end = start + search.length
    append(text.substring(0, start), attributes)
    append(
        text.substring(start, end),
        SimpleTextAttributes.merge(attributes, SimpleTextAttributes(SimpleTextAttributes.STYLE_SEARCH_MATCH, null)),
    )
    append(text.substring(end), attributes)
}

/** [name] as HTML with the first place it holds [search] in bold, for a header label; null when it doesn't hold it. */
internal fun highlightedHtml(name: String, search: String): String? {
    val start = if (search.isEmpty()) -1 else name.indexOf(search, ignoreCase = true)
    if (start < 0) return null
    val end = start + search.length
    fun escape(text: String) = StringUtil.escapeXmlEntities(text)
    return "<html>${escape(name.substring(0, start))}<b>${escape(name.substring(start, end))}</b>${escape(name.substring(end))}</html>"
}

/** The view indexes of the columns of [table] whose name holds [search], ignoring case, left to right. */
internal fun matchingColumns(table: JTable, search: String): List<Int> = if (search.isEmpty()) {
    emptyList()
} else {
    (0 until table.columnCount).filter {
        table.getColumnName(it).contains(search, ignoreCase = true)
    }
}

/**
 * A field that finds a column of a wide [table] by part of its name: it scrolls the first match into view and selects
 * its cell in the selected row. Enter or ↓ goes to the next match, Shift+Enter or ↑ back. [HeaderRenderer] shows the
 * matching names in bold. The names come from the page shown, so nothing is asked of the app.
 */
internal class ColumnSearch(private val table: JTable) {

    private val model = table.model as TableDataModel

    /** The view index of the match last selected. */
    private var current = -1

    private val field = SearchTextField(false).apply {
        textEditor.emptyText.text = "Find column"
        textEditor.columns = 10
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                current = -1
                model.columnSearch = text.trim()
                table.tableHeader.repaint()
                go(1)
            }
        })
        textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ENTER -> go(if (e.isShiftDown) -1 else 1)
                    e.keyCode == KeyEvent.VK_DOWN -> go(1)
                    e.keyCode == KeyEvent.VK_UP -> go(-1)
                    e.keyCode == KeyEvent.VK_ESCAPE && text.isNotEmpty() -> text = ""
                    else -> return
                }
                e.consume()
            }
        })
    }

    /** The field, kept at its own height in a tab bar. */
    val component: JComponent = JPanel(GridBagLayout()).apply { add(field) }

    /** Selects the match after [current], or before it when [step] is -1, wrapping around. */
    private fun go(step: Int) {
        val matches = matchingColumns(table, model.columnSearch)
        field.textEditor.putClientProperty(
            "JComponent.outline",
            if (matches.isEmpty() &&
                model.columnSearch.isNotEmpty()
            ) {
                "error"
            } else {
                null
            },
        )
        if (matches.isEmpty()) return
        val next = if (step >
            0
        ) {
            matches.firstOrNull { it > current } ?: matches.first()
        } else {
            matches.lastOrNull { it < current } ?: matches.last()
        }
        current = next
        if (table.rowCount == 0) return table.scrollRectToVisible(table.getCellRect(0, next, true))
        table.changeSelection(table.selectedRow.takeIf { it >= 0 } ?: 0, next, false, false)
    }
}
