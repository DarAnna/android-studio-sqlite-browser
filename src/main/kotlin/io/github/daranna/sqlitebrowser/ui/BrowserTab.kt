package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.Point
import javax.swing.JPanel
import javax.swing.JTable

/** The content of a tab in [SqliteBrowserPanel]: a [TableView] or a [QueryView], each on one database. */
abstract class BrowserTab :
    JPanel(BorderLayout()),
    Disposable {

    abstract val database: String

    /** Replaced when the panel reconnects to the same app, since the new client is the one that knows the server's state. */
    abstract var client: DebugDbClient

    abstract val title: String

    abstract val tooltip: String

    /** What [SavedSession] keeps of this tab, to reopen it after an IDE restart. */
    abstract fun savedTab(): SavedSession.Tab
}

/** Replaces the rows of [table]'s [TableDataModel]; keeps the column widths the user set when the columns are the same. */
internal fun showKeepingWidths(table: JBTable, data: DebugDbClient.TableData) {
    val model = table.model as TableDataModel
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

/**
 * Shows NULL as a grey "NULL", so it can't be mistaken for an empty string.
 *
 * [link] gives the tooltip of a cell that leads elsewhere, such as along a foreign key, by model row and column; such
 * cells get [LINK_ICON] at their left edge (see [isOnLink]).
 */
internal class NullAwareRenderer(private val link: (row: Int, column: Int) -> String? = { _, _ -> null }) : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        if (value == null) append("NULL", SimpleTextAttributes.GRAYED_ATTRIBUTES) else append(value.toString())
        val tooltip = link(table.convertRowIndexToModel(row), table.convertColumnIndexToModel(column))
        if (tooltip != null) icon = LINK_ICON
        toolTipText = tooltip
    }
}

internal val LINK_ICON = AllIcons.Ide.External_link_arrow

/** Whether [point] in [table] is on the icon that [NullAwareRenderer] draws in a linked cell. */
internal fun isOnLink(table: JTable, point: Point): Boolean {
    val row = table.rowAtPoint(point)
    val column = table.columnAtPoint(point)
    if (row < 0 || column < 0) return false
    return point.x - table.getCellRect(row, column, false).x < LINK_ICON.iconWidth + JBUI.scale(6)
}
