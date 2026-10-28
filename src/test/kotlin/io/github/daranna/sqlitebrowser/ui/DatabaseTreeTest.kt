package io.github.daranna.sqlitebrowser.ui

import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import javax.swing.plaf.basic.BasicTreeUI
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

class DatabaseTreeTest {

    private val tree = DatabaseTree(JBLabel()) { _, _ -> }.apply {
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
}
