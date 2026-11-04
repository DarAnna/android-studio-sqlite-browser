package io.github.daranna.sqlitebrowser.ui

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBLabel
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import io.github.daranna.sqlitebrowser.debugdb.FakeDebugDbServer
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/**
 * Expanding a table in the database tree lists its columns, through a fake debug-db.
 *
 * JUnit 3 style, as the platform's fixtures need, so names are camel case: ktlint allows backticks only on `@Test`.
 */
class DatabaseTreeColumnsTest : BasePlatformTestCase() {

    private lateinit var server: FakeDebugDbServer
    private val status = JBLabel()
    private val tree = DatabaseTree(LabelStatus(status), { _, _ -> }, { _, _ -> })

    override fun setUp() {
        super.setUp()
        server = FakeDebugDbServer()
        server.responses["getDbList"] =
            """{"rows":[["shop.db","false","true"]],"columns":[],"isSuccessful":true,"error":null,"dbVersion":0}"""
        server.responses["getTableList?database=shop.db"] =
            """{"rows":["purchase"],"columns":[],"isSuccessful":true,"error":null,"dbVersion":1}"""
        server.queries["PRAGMA table_info(purchase)"] = rows(
            """[${int(0)},${text("id")},${text("INTEGER")},${int(1)},${text(null)},${int(1)}]""",
            """[${int(1)},${text("article_id")},${text("INTEGER")},${int(1)},${text(null)},${int(0)}]""",
            """[${int(2)},${text("note")},${text("TEXT")},${int(0)},${text("'none'")},${int(0)}]""",
        )
        server.queries["PRAGMA foreign_key_list(purchase)"] = rows(
            """[${int(
                0,
            )},${int(
                0,
            )},${text("article")},${text("article_id")},${text("id")},${text("NO ACTION")},${text("NO ACTION")},${text("NONE")}]""",
        )
        tree.show(DebugDbClient(server.port), listOf(DebugDbClient.Database("shop.db", false)))
    }

    override fun tearDown() {
        try {
            server.close()
        } finally {
            super.tearDown()
        }
    }

    private fun rows(vararg rows: String) =
        """{"isSuccessful":true,"isEditable":false,"tableInfos":[],"rows":[${rows.joinToString(",")}]}"""

    private fun int(value: Int) = """{"dataType":"integer","value":$value}"""

    private fun text(value: String?) = """{"dataType":"text","value":${value?.let { "\"$it\"" } ?: "null"}}"""

    private fun text(node: DefaultMutableTreeNode): String {
        val component = tree.component
        val rendered = component.cellRenderer.getTreeCellRendererComponent(component, node, false, false, node.isLeaf, 0, false)
        return (rendered as SimpleColoredComponent).getCharSequence(false).toString()
    }

    /** Expands [node] and waits until what it lists has loaded: the status is cleared then. */
    private fun expand(node: DefaultMutableTreeNode) {
        status.text = "waiting"
        tree.component.expandPath(TreePath(node.path))
        PlatformTestUtil.waitWithEventsDispatching("never loaded", { status.text.isEmpty() }, 5)
    }

    fun testExpandingATableListsItsColumnsWithTypesAndKeys() {
        val database = (tree.component.model.root as DefaultMutableTreeNode).firstChild as DefaultMutableTreeNode
        expand(database)
        val table = database.firstChild as DefaultMutableTreeNode
        expand(table)

        assertEquals(
            listOf("id  INTEGER, NOT NULL", "article_id  INTEGER, NOT NULL, → article.id", "note  TEXT, DEFAULT 'none'"),
            table.children().toList().map { text(it as DefaultMutableTreeNode) },
        )
    }
}
