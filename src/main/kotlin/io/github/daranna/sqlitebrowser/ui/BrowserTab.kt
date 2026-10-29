package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.Disposable
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.table.JBTable
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
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

/** Shows NULL as a grey "NULL", so it can't be mistaken for an empty string. */
internal class NullAwareRenderer : ColoredTableCellRenderer() {
    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        if (value == null) append("NULL", SimpleTextAttributes.GRAYED_ATTRIBUTES) else append(value.toString())
    }
}
