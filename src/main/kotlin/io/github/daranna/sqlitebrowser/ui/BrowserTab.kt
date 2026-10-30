package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.RowIcon
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Point
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.TableCellRenderer

/** The content of a tab in [SqliteBrowserPanel]: a [TableView] or a [QueryView], each on one database. */
abstract class BrowserTab :
    JPanel(BorderLayout()),
    Disposable {

    abstract val database: String

    /** Replaced when the panel reconnects to the same app, since the new client is the one that knows the server's state. */
    abstract var client: DebugDbClient

    abstract val title: String

    abstract val tooltip: String

    abstract val icon: Icon

    /** What gets focus when the tab is selected; otherwise Swing picks the first field, such as a table's first filter. */
    abstract val preferredFocus: JComponent

    /** What [SavedSession] keeps of this tab, to reopen it after an IDE restart. */
    abstract fun savedTab(): SavedSession.Tab
}

/**
 * Replaces the rows of [table]'s [TableDataModel]; keeps the column widths the user set when the columns are the same,
 * and otherwise fits them to the new rows.
 */
internal fun showKeepingWidths(table: JBTable, data: DebugDbClient.TableData) {
    val model = table.model as TableDataModel
    val widths = if (data.columns == model.data.columns) {
        table.columnModel.columns.toList().associate { it.modelIndex to it.width }
    } else {
        emptyMap()
    }
    model.data = data
    if (widths.isEmpty()) return fitColumns(table)
    table.columnModel.columns.toList().forEach { column ->
        widths[column.modelIndex]?.let {
            column.preferredWidth = it
            column.width = it
        }
    }
}

/** Sizes each column to its header and its first rows, within limits, so short columns don't cut their names. */
private fun fitColumns(table: JTable) {
    val header = table.tableHeader.defaultRenderer
    val rows = minOf(table.rowCount, FIT_ROWS)
    table.columnModel.columns.toList().forEachIndexed { view, column ->
        val name = header.getTableCellRendererComponent(table, column.headerValue, false, false, -1, view).preferredSize.width
        val cells = (0 until rows).maxOfOrNull { row ->
            table.prepareRenderer(table.getCellRenderer(row, view), row, view).preferredSize.width
        } ?: 0
        // Room for the sort arrow, so sorting doesn't cut the name.
        val width = maxOf(name + JBUI.scale(SORT_ICON_ROOM), cells) + table.intercellSpacing.width
        column.preferredWidth = width.coerceIn(JBUI.scale(MIN_COLUMN_WIDTH), JBUI.scale(MAX_COLUMN_WIDTH))
        column.width = column.preferredWidth
    }
}

private const val FIT_ROWS = 50
private const val SORT_ICON_ROOM = 20
private const val MIN_COLUMN_WIDTH = 64
private const val MAX_COLUMN_WIDTH = 320

/** Marks the primary key and the sorted column of a [TableDataModel] with icons after the column's name. */
internal class HeaderRenderer(private val base: TableCellRenderer) : TableCellRenderer {
    override fun getTableCellRendererComponent(
        table: JTable,
        value: Any?,
        selected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        val component = base.getTableCellRendererComponent(table, value, selected, hasFocus, row, column)
        val label = component as? JLabel ?: return component
        val model = table.model as? TableDataModel ?: return component
        val index = table.convertColumnIndexToModel(column)
        val icons = listOfNotNull(
            StudioIcons.DatabaseInspector.PRIMARY_KEY.takeIf { index == model.keyColumn },
            (if (model.sortDescending) AllIcons.General.ArrowDown else AllIcons.General.ArrowUp).takeIf { index == model.sortColumn },
        )
        label.icon = if (icons.size > 1) RowIcon(*icons.toTypedArray()) else icons.firstOrNull()
        label.horizontalTextPosition = SwingConstants.LEADING
        return label
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

/** A toolbar button that runs [perform], enabled while [enabled] says so. */
internal fun action(text: String, icon: Icon, enabled: () -> Boolean = { true }, perform: () -> Unit): AnAction =
    object : DumbAwareAction(text, null, icon) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = enabled()
        }

        override fun actionPerformed(e: AnActionEvent) = perform()
    }

internal fun toolbar(target: JComponent, actions: List<AnAction>): JComponent =
    ActionManager.getInstance().createActionToolbar(TAB_TOOLBAR, DefaultActionGroup(actions), true).run {
        targetComponent = target
        // Otherwise it keeps an empty slot for an overflow arrow at its end.
        setReservePlaceAutoPopupIcon(false)
        component
    }

/** The row on top of a tab's rows or SQL: [start], usually buttons, then [middle], then [end] at the far right. */
internal fun tabBar(start: JComponent?, middle: JComponent?, end: JComponent? = null) = JPanel(BorderLayout()).apply {
    border = JBUI.Borders.customLineBottom(JBColor.border())
    start?.let { add(it, BorderLayout.WEST) }
    middle?.let { add(it, BorderLayout.CENTER) }
    end?.let { add(it, BorderLayout.EAST) }
}

/** What a tab is doing, or what went wrong; the whole text is in the tooltip when it doesn't fit. */
internal class StatusLabel : JBLabel() {
    init {
        border = JBUI.Borders.empty(0, 6)
    }

    override fun setText(text: String?) {
        super.setText(text)
        toolTipText = text?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Pages through [total] rows, [TableView.PAGE_SIZE] at a time: [actions] go to the first, previous, next and last page
 * around a label of the rows shown, then call [load] to show the page at [offset]. [active] is false while there is
 * nothing to page, such as in a query tab whose last statement was no SELECT.
 */
internal class Pager(private val active: () -> Boolean = { true }, private val load: () -> Unit) {
    var offset = 0
    var total = 0L

    /** Rows on the page shown. */
    var shown = 0

    private val label = JBLabel().apply { border = JBUI.Borders.empty(0, 4) }

    private val hasPrevious get() = active() && offset > 0
    private val hasNext get() = active() && offset + shown < total

    val actions: List<AnAction> = listOf(
        action("First Page", AllIcons.Actions.Play_first, { hasPrevious }) { go(0) },
        action("Previous Page", AllIcons.Actions.Play_back, { hasPrevious }) { go(maxOf(0, offset - TableView.PAGE_SIZE)) },
        object : DumbAwareAction(), CustomComponentAction {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun createCustomComponent(presentation: Presentation, place: String) = label

            override fun actionPerformed(e: AnActionEvent) = Unit
        },
        action("Next Page", AllIcons.Actions.Play_forward, { hasNext }) { go(offset + TableView.PAGE_SIZE) },
        action("Last Page", AllIcons.Actions.Play_last, { hasNext }) {
            go(((total - 1) / TableView.PAGE_SIZE * TableView.PAGE_SIZE).toInt())
        },
    )

    private fun go(to: Int) {
        offset = to
        load()
    }

    /** Shows the rows on the page; call after changing [offset], [total] or [shown]. */
    fun update() {
        label.text = when {
            !active() -> ""
            shown == 0 -> "No rows"
            else -> "${offset + 1}–${offset + shown} of $total"
        }
    }
}

private const val TAB_TOOLBAR = "SqliteBrowserTabToolbar"
