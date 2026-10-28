package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Databases, each with its tables, which load when the database is expanded.
 * Double-click or Enter on a table calls [onOpen]. Add [component] to the UI.
 */
class DatabaseTree(private val status: JBLabel, private val onOpen: (database: String, table: String) -> Unit) {

    private sealed interface Item
    private data class DatabaseItem(val database: DebugDbClient.Database) : Item
    private data class TableItem(val database: String, val table: String) : Item
    private data class Message(val text: String) : Item

    private var client: DebugDbClient? = null

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)

    // Not a Tree subclass: a renderer set in a subclass's init never reaches the tree UI, which keeps
    // painting with the platform's NodeRenderer, and that shows toString().
    val component: Tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean,
            ) {
                when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                    is DatabaseItem -> {
                        icon = AllIcons.Nodes.DataSchema
                        append(item.database.name)
                        if (item.database.encrypted) append("  encrypted", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }

                    is TableItem -> {
                        icon = AllIcons.Nodes.DataTables
                        append(item.table)
                    }

                    is Message -> append(item.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                val item = node.userObject as? DatabaseItem ?: return
                if ((node.firstChild as DefaultMutableTreeNode).userObject == LOADING) loadTables(node, item.database.name)
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
    }

    /** Shows [databases] of the app [client] talks to, all collapsed. */
    fun show(client: DebugDbClient?, databases: List<DebugDbClient.Database>) {
        this.client = client
        root.removeAllChildren()
        databases.forEach { database ->
            root.add(DefaultMutableTreeNode(DatabaseItem(database)).apply { add(DefaultMutableTreeNode(LOADING)) })
        }
        treeModel.reload()
    }

    private fun loadTables(node: DefaultMutableTreeNode, database: String) {
        val client = client ?: return
        background(status, "Opening $database") {
            val tables = client.tables(database)
            ui {
                if (this.client !== client) return@ui
                node.removeAllChildren()
                tables.forEach { node.add(DefaultMutableTreeNode(TableItem(database, it))) }
                if (tables.isEmpty()) node.add(DefaultMutableTreeNode(Message("No tables")))
                treeModel.nodeStructureChanged(node)
                status.text = ""
            }
        }
    }

    private fun openSelected() {
        val item = (component.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TableItem ?: return
        onOpen(item.database, item.table)
    }

    private companion object {
        val LOADING = Message("Loading…")
    }
}
