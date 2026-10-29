package io.github.daranna.sqlitebrowser.ui

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer
import javax.swing.tree.TreePath

class DatabaseTreeTest {

    private val tree = DatabaseTree(LabelStatus(JBLabel()), { _, _ -> }, { _, _ -> }).apply {
        show(null, listOf(DebugDbClient.Database("shop.db", false)))
    }

    @Test
    fun `the tree UI paints with our renderer`() {
        // The tree can report our renderer while its UI still paints with the platform's NodeRenderer,
        // which shows each node's toString(). BasicTreeUI keeps the one it paints with in a protected field.
        val field = BasicTreeUI::class.java.getDeclaredField("currentCellRenderer").apply { isAccessible = true }

        assertSame(tree.component.cellRenderer, field.get(tree.component.ui) as TreeCellRenderer)
    }

    @Test
    fun `a database shows its name`() {
        val component = tree.component
        val node = (component.model.root as DefaultMutableTreeNode).firstChild
        val rendered = component.cellRenderer.getTreeCellRendererComponent(component, node, false, false, false, 0, false)

        assertEquals("shop.db", (rendered as SimpleColoredComponent).getCharSequence(false).toString())
    }

    @Test
    fun `a new query goes to the selected database, else the first one that is not the preferences`() {
        tree.show(
            null,
            listOf(
                DebugDbClient.Database("APP_SHARED_PREFERENCES", false),
                DebugDbClient.Database("shop.db", false),
                DebugDbClient.Database("settings.db", false),
            ),
        )
        assertEquals("shop.db" to null, tree.queryTarget())

        val root = tree.component.model.root as DefaultMutableTreeNode
        tree.component.selectionPath = TreePath(arrayOf(root, root.getChildAt(2)))
        assertEquals("settings.db" to null, tree.queryTarget())

        tree.component.selectionPath = TreePath(arrayOf(root, root.getChildAt(0)))
        assertEquals("shop.db" to null, tree.queryTarget())
    }

    @Test
    fun `no database, no query`() {
        tree.show(null, listOf(DebugDbClient.Database("APP_SHARED_PREFERENCES", false)))

        assertNull(tree.queryTarget())
    }
}
