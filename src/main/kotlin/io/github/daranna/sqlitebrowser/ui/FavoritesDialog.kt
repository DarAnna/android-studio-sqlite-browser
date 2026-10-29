package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/**
 * [app]'s favorite queries, to open one in a new query tab, rename or delete. Opening doesn't run it: it may hold
 * writes.
 */
internal class FavoritesDialog(project: Project, private val queries: SavedQueries.Queries, private val app: String) :
    DialogWrapper(project) {

    private val model = CollectionListModel(queries.favoritesOf(app))
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        emptyText.text = "No favorites for $app yet. Star the SQL of a query tab to add it."
        cellRenderer = object : ColoredListCellRenderer<SavedQueries.Query>() {
            override fun customizeCellRenderer(
                list: JList<out SavedQueries.Query>,
                value: SavedQueries.Query,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.name)
                append("  ${value.database}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }
    private val preview = JBTextArea().apply {
        isEditable = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(4)
    }

    /** The favorite to open, once the dialog closed with Open. */
    val selected: SavedQueries.Query? get() = list.selectedValue

    init {
        title = "Favorite Queries"
        setOKButtonText("Open")
        list.addListSelectionListener {
            preview.text = list.selectedValue?.sql.orEmpty()
            preview.caretPosition = 0
            isOKActionEnabled = list.selectedValue != null
        }
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                if (list.selectedValue == null) return false
                doOKAction()
                return true
            }
        }.installOn(list)
        if (!model.isEmpty) list.selectedIndex = 0
        isOKActionEnabled = list.selectedValue != null
        init()
    }

    override fun createCenterPanel(): JComponent {
        val listPanel = ToolbarDecorator.createDecorator(list)
            .disableAddAction()
            .disableUpDownActions()
            .setEditActionName("Rename")
            .setEditAction { rename() }
            .setRemoveAction { delete() }
            .createPanel()
        return OnePixelSplitter(true, 0.5f).apply {
            firstComponent = listPanel
            secondComponent = JBScrollPane(preview)
            preferredSize = JBUI.size(520, 400)
        }
    }

    override fun getPreferredFocusedComponent() = list

    private fun rename() {
        val favorite = list.selectedValue ?: return
        val name = Messages.showInputDialog(
            list,
            "New name. A favorite of that name on ${favorite.database} is replaced.",
            "Rename Favorite",
            null,
            favorite.name,
            NON_BLANK,
        )?.trim() ?: return
        queries.rename(favorite, name)
        reload(favorite)
    }

    private fun delete() {
        val favorite = list.selectedValue ?: return
        val answer = Messages.showYesNoDialog(list, "Delete the favorite \"${favorite.name}\"?", "Delete Favorite", null)
        if (answer != Messages.YES) return
        queries.delete(favorite)
        reload(null)
    }

    private fun reload(select: SavedQueries.Query?) {
        model.replaceAll(queries.favoritesOf(app))
        list.setSelectedValue(select, true)
        if (list.selectedValue == null && !model.isEmpty) list.selectedIndex = 0
    }
}

/** For the name of a favorite. */
internal val NON_BLANK = object : InputValidator {
    override fun checkInput(inputString: String?) = !inputString.isNullOrBlank()

    override fun canClose(inputString: String?) = checkInput(inputString)
}
