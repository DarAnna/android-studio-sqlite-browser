package io.github.daranna.sqlitebrowser.ui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.FakeDebugDbServer
import java.awt.event.KeyEvent
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The search field above the database tree, which lists tables through a fake debug-db.
 *
 * JUnit 3 style, as the platform's fixtures need, so names are camel case: ktlint allows backticks only on `@Test`.
 */
class DatabaseTreeSearchTest : BasePlatformTestCase() {

    private lateinit var server: FakeDebugDbServer
    private val opened = mutableListOf<Pair<String, String>>()
    private val status = JBLabel()
    private val tree = DatabaseTree(LabelStatus(status), { database, table -> opened += database to table }, { _, _ -> })

    override fun setUp() {
        super.setUp()
        server = FakeDebugDbServer()
        server.responses["getTableList?database=shop.db"] = tableList("article", "purchase")
        server.responses["getTableList?database=settings.db"] = tableList("setting", "article_draft")
        val databases = listOf(DebugDbClient.Database("shop.db", false), DebugDbClient.Database("settings.db", false))
        tree.show(DebugDbClient(server.port), databases)
    }

    override fun tearDown() {
        try {
            server.close()
        } finally {
            super.tearDown()
        }
    }

    private fun tableList(vararg tables: String) =
        """{"rows":[${tables.joinToString(",") { "\"$it\"" }}],"columns":[],"isSuccessful":true,"error":null,"dbVersion":1}"""

    /** The text of each node, children indented, as the tree's renderer shows it. */
    private fun shown(): List<String> {
        fun text(node: DefaultMutableTreeNode): String {
            val component = tree.component
            val rendered = component.cellRenderer.getTreeCellRendererComponent(component, node, false, false, node.isLeaf, 0, false)
            return (rendered as SimpleColoredComponent).getCharSequence(false).toString()
        }
        val root = tree.component.model.root as DefaultMutableTreeNode
        return root.children().toList().flatMap { node ->
            node as DefaultMutableTreeNode
            listOf(text(node)) + node.children().toList().map { "  " + text(it as DefaultMutableTreeNode) }
        }
    }

    /** Types [text] in the search field and waits until every database is listed: the status is cleared then. */
    private fun search(text: String) {
        tree.searchField.text = text
        PlatformTestUtil.waitWithEventsDispatching("tables never listed", { status.text.isEmpty() }, 5)
    }

    fun testSearchListsEveryDatabaseAndKeepsMatchingTables() {
        search("ART")

        assertEquals(listOf("shop.db", "  article", "settings.db", "  article_draft"), shown())
        assertTrue(tree.component.isExpanded(0))
        assertTrue(tree.component.isExpanded(2))
    }

    fun testEnterInTheSearchFieldOpensTheFirstMatch() {
        search("purch")
        val field = tree.searchField.textEditor
        val enter = KeyEvent(field, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_ENTER, '\n')
        // Not dispatchEvent: Swing would send the key to the focus owner, and nothing has focus in a test.
        field.keyListeners.forEach { it.keyPressed(enter) }

        assertEquals(listOf("shop.db" to "purchase"), opened)
    }

    fun testNoMatchSaysSo() {
        search("nothing")

        assertEquals(listOf("No table matches"), shown())
    }

    fun testClearingTheSearchShowsEveryDatabaseWithItsListedTables() {
        search("art")
        search("")

        assertEquals(listOf("shop.db", "  article", "  purchase", "settings.db", "  setting", "  article_draft"), shown())
        assertFalse(tree.component.isExpanded(0))
    }
}
