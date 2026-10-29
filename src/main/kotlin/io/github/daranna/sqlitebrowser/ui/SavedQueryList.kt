package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.InplaceButton
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * The favorites and recent queries of one database, beside a query tab's editor. A click hands the query to
 * [onOpen]; a click on "Recent" folds that section. Rename and delete are on the context menu, F2 and Delete. The
 * button in its header calls [onHide].
 */
internal class SavedQueryList(
    private val app: String,
    private val database: String,
    parent: Disposable,
    onHide: () -> Unit,
    private val onOpen: (SavedQueries.Query) -> Unit,
) {
    private sealed interface Row
    private class Header(val title: String, val folds: Boolean) : Row
    private class Hint(val text: String) : Row
    private class Saved(val query: SavedQueries.Query, val favorite: Boolean) : Row

    private val model = CollectionListModel<Row>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Renderer()
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 6))
                add(JBLabel("Saved queries").apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
                add(InplaceButton("Hide saved queries", AllIcons.General.HideToolWindow) { onHide() }, BorderLayout.EAST)
            },
            BorderLayout.NORTH,
        )
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    init {
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                if (index < 0 || !list.getCellBounds(index, index).contains(e.point)) return
                list.selectedIndex = index
                when {
                    e.isPopupTrigger || SwingUtilities.isRightMouseButton(e) -> showMenu(e)
                    SwingUtilities.isLeftMouseButton(e) -> activate(model.getElementAt(index))
                }
            }
        })
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val saved = list.selectedValue as? Saved
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> list.selectedValue?.let(::activate)
                    KeyEvent.VK_F2 -> saved?.takeIf { it.favorite }?.let { rename(it.query) }
                    KeyEvent.VK_DELETE, KeyEvent.VK_BACK_SPACE -> saved?.let(::remove)
                    else -> return
                }
                e.consume()
            }
        })
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(SavedQueries.TOPIC, SavedQueries.Listener { reload() })
        reload()
    }

    private fun activate(row: Row) {
        when (row) {
            is Saved -> onOpen(row.query)

            is Header -> if (row.folds) {
                recentShown = !recentShown
                SavedQueries.changed()
            }

            is Hint -> Unit
        }
    }

    private fun showMenu(e: MouseEvent) {
        val saved = list.selectedValue as? Saved ?: return
        JBPopupMenu().apply {
            add(JMenuItem("Open", AllIcons.Actions.Execute).apply { addActionListener { onOpen(saved.query) } })
            if (saved.favorite) {
                add(JMenuItem("Rename…").apply { addActionListener { rename(saved.query) } })
                add(JMenuItem("Remove from Favorites").apply { addActionListener { remove(saved) } })
            } else {
                add(
                    JMenuItem("Add to Favorites", AllIcons.Nodes.Favorite).apply {
                        addActionListener { SavedQueries.change { addFavorite(app, database, saved.query.sql) } }
                    },
                )
                add(JMenuItem("Remove from Recent").apply { addActionListener { remove(saved) } })
            }
        }.show(list, e.x, e.y)
    }

    private fun rename(favorite: SavedQueries.Query) {
        val name = Messages.showInputDialog(
            list,
            "New name. A favorite of that name on $database is replaced.",
            "Rename Favorite",
            null,
            favorite.name,
            NON_BLANK,
        )?.trim() ?: return
        SavedQueries.change { rename(favorite, name) }
    }

    /** No confirmation: removing a favorite leaves its SQL in the editor and in Recent if it ran, and the star adds it back. */
    private fun remove(saved: Saved) = SavedQueries.change {
        if (saved.favorite) delete(saved.query) else recent.removeAll { it === saved.query }
    }

    private fun reload() {
        val selected = (list.selectedValue as? Saved)?.query
        val queries = SavedQueries.get()
        val favorites = queries.favoritesOf(app, database)
        val recent = queries.recentOf(app, database)
        model.replaceAll(
            buildList {
                add(Header("Favorites", folds = false))
                if (favorites.isEmpty()) add(Hint("Star a query to keep it here"))
                favorites.forEach { add(Saved(it, favorite = true)) }
                add(Header("Recent", folds = true))
                if (recentShown) {
                    if (recent.isEmpty()) add(Hint("Queries you run show here"))
                    recent.forEach { add(Saved(it, favorite = false)) }
                }
            },
        )
        val index = model.items.indexOfFirst { it is Saved && it.query === selected }
        if (index >= 0) list.selectedIndex = index
    }

    private inner class Renderer : ColoredListCellRenderer<Row>() {
        override fun customizeCellRenderer(list: JList<out Row>, value: Row, index: Int, selected: Boolean, hasFocus: Boolean) {
            toolTipText = null
            when (value) {
                is Header -> {
                    if (value.folds) icon = if (recentShown) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
                    append(value.title, SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                }

                is Hint -> append(value.text, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

                is Saved -> {
                    toolTipText = "<html><pre>${StringUtil.escapeXmlEntities(value.query.sql.trim())}</pre></html>"
                    if (value.favorite) {
                        icon = AllIcons.Nodes.Favorite
                        append(value.query.name)
                    } else {
                        append(value.query.sql.trim().replace(WHITESPACE, " "))
                        if (value.query.time > 0) {
                            append(
                                "  " + DateFormatUtil.formatPrettyDateTime(value.query.time),
                                SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES,
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** The same in every tab, until Android Studio restarts. */
        var recentShown = true
    }
}

/** For the name of a favorite. */
internal val NON_BLANK = object : InputValidator {
    override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()

    override fun canClose(inputString: String?) = checkInput(inputString)
}
