package io.github.daranna.sqlitebrowser.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.JBMenuItem
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import icons.StudioIcons
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Databases, each with its tables, which load when the database is expanded, and each table with its columns, which
 * load when the table is expanded. Double-click or Enter on a table, or on one of its columns, calls [onOpen]. A right-click offers [onQuery], with the table if it was on one.
 *
 * The search field above the tree narrows it to the tables whose name holds what is typed, in every database. Tables
 * of databases nobody expanded yet are listed then, which switches the server's open database; tabs reopen theirs
 * through [DebugDbClient.withDatabase].
 *
 * Add [view] to the UI.
 */
class DatabaseTree(
    private val status: Status,
    private val onOpen: (database: String, table: String) -> Unit,
    private val onQuery: (database: String, table: String?) -> Unit,
) {

    private sealed interface Item
    private data class DatabaseItem(val database: DebugDbClient.Database) : Item
    private data class TableItem(val database: String, val table: String) : Item
    private data class ColumnItem(val database: String, val table: String, val column: ColumnDetails) : Item
    private data class Message(val text: String) : Item

    private var client: DebugDbClient? = null

    private var databases: List<DebugDbClient.Database> = emptyList()

    /** The tables of each database listed so far, kept so a search shows them without asking the app again. */
    private val tables = mutableMapOf<String, List<String>>()

    /** The columns of each table listed so far, by database and table, kept like [tables]. */
    private val columns = mutableMapOf<Pair<String, String>, List<ColumnDetails>>()

    /** Databases expanded while there was no search, expanded again when the search is cleared. */
    private val expanded = mutableSetOf<String>()

    /** Tables expanded while there was no search, by database and table, like [expanded]. */
    private val expandedTables = mutableSetOf<Pair<String, String>>()

    /** What the search field holds, trimmed; empty for no search. */
    private var search = ""

    /** Set while [rebuild] replaces the nodes, whose collapsing says nothing about what the user expanded. */
    private var rebuilding = false

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)

    // Not a Tree subclass: a renderer set in a subclass's init never reaches the tree UI, which keeps
    // painting with the platform's NodeRenderer, and that shows toString().
    val component: Tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        // A double-click opens a table; its arrow shows the columns.
        toggleClickCount = 0
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
                        appendHighlighted(item.table, search)
                    }

                    is ColumnItem -> {
                        icon = if (item.column.inPrimaryKey) StudioIcons.DatabaseInspector.PRIMARY_KEY else AllIcons.Nodes.DataColumn
                        append(item.column.name)
                        item.column.description.takeIf { it.isNotEmpty() }?.let {
                            append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        }
                    }

                    is Message -> append(item.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
        addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as DefaultMutableTreeNode
                if ((node.firstChild as DefaultMutableTreeNode).userObject != LOADING) return
                when (val item = node.userObject) {
                    is DatabaseItem -> loadTables(node, item.database.name)
                    is TableItem -> loadColumns(node, item.database, item.table)
                }
            }

            override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
        })
        addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                if (rebuilding || search.isNotEmpty()) return
                databaseAt(event.path)?.let { expanded += it }
                tableAt(event.path)?.let { expandedTables += it }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {
                if (rebuilding || search.isNotEmpty()) return
                databaseAt(event.path)?.let { expanded -= it }
                tableAt(event.path)?.let { expandedTables -= it }
            }
        })
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }

            override fun mousePressed(e: MouseEvent) = maybeShowPopup(e)

            override fun mouseReleased(e: MouseEvent) = maybeShowPopup(e)
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelected()
            }
        })
    }

    val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search tables"
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = searchChanged()
        })
        textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> openSelected()

                    KeyEvent.VK_DOWN -> component.requestFocusInWindow()

                    KeyEvent.VK_ESCAPE -> if (text.isNotEmpty()) {
                        text = ""
                        e.consume()
                    }
                }
            }
        })
    }

    /** The search field on top of the tree. */
    val view: JComponent = JPanel(BorderLayout()).apply {
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2))
                add(searchField, BorderLayout.CENTER)
            },
            BorderLayout.NORTH,
        )
        add(ScrollPaneFactory.createScrollPane(component, true), BorderLayout.CENTER)
    }

    /** Shows [databases] of the app [client] talks to, collapsed unless a search shows their tables. */
    fun show(client: DebugDbClient?, databases: List<DebugDbClient.Database>) {
        this.client = client
        this.databases = databases
        tables.clear()
        columns.clear()
        expanded.clear()
        expandedTables.clear()
        rebuild()
        if (search.isNotEmpty()) listMissingTables()
    }

    private fun searchChanged() {
        val wasSearching = search.isNotEmpty()
        search = searchField.text.trim()
        if (search.isEmpty() && !wasSearching) return
        rebuild()
        if (search.isNotEmpty()) listMissingTables()
    }

    /**
     * Replaces the nodes with what [tables] and [search] call for: without a search every database, expanded as the
     * user left it; with one only the tables that match, under their expanded databases, and the first one selected
     * so Enter in the search field opens it.
     */
    private fun rebuild() {
        rebuilding = true
        try {
            root.removeAllChildren()
            if (search.isEmpty()) {
                databases.forEach { database ->
                    root.add(DefaultMutableTreeNode(DatabaseItem(database)).apply { addTables(this, database.name, tables[database.name]) })
                }
            } else {
                databases.forEach { database ->
                    val matches = tables[database.name]?.filter { it.contains(search, ignoreCase = true) }
                    if (!matches.isNullOrEmpty()) {
                        root.add(
                            DefaultMutableTreeNode(DatabaseItem(database)).apply {
                                addTables(this, database.name, matches)
                            },
                        )
                    }
                }
                val listing = databases.any { it.name !in tables }
                if (root.childCount == 0 && databases.isNotEmpty()) root.add(DefaultMutableTreeNode(if (listing) SEARCHING else NO_MATCH))
            }
            treeModel.reload()
            root.children().asSequence().map { it as DefaultMutableTreeNode }.forEach { node ->
                val database = (node.userObject as? DatabaseItem)?.database?.name ?: return@forEach
                if (search.isNotEmpty() || database in expanded) component.expandPath(TreePath(node.path))
                if (search.isNotEmpty()) return@forEach
                node.children().asSequence().map { it as DefaultMutableTreeNode }.forEach { tableNode ->
                    val item = tableNode.userObject as? TableItem
                    if (item != null && (item.database to item.table) in expandedTables) component.expandPath(TreePath(tableNode.path))
                }
            }
            if (search.isNotEmpty()) {
                root.children().asSequence().map { it as DefaultMutableTreeNode }.firstOrNull { it.childCount > 0 }?.let {
                    val first = it.firstChild as DefaultMutableTreeNode
                    if (first.userObject is TableItem) component.selectionPath = TreePath(first.path)
                }
            }
        } finally {
            rebuilding = false
        }
    }

    /** [tables] null means not listed yet: a placeholder that lists them on expand. */
    private fun addTables(node: DefaultMutableTreeNode, database: String, tables: List<String>?) {
        when {
            tables == null -> node.add(DefaultMutableTreeNode(LOADING))

            tables.isEmpty() -> node.add(DefaultMutableTreeNode(Message("No tables")))

            else -> tables.forEach { table ->
                node.add(DefaultMutableTreeNode(TableItem(database, table)).apply { addColumns(this, database, table) })
            }
        }
    }

    /** Like [addTables]. The preferences' "tables" are files, not SQLite tables, so they have no columns to show. */
    private fun addColumns(node: DefaultMutableTreeNode, database: String, table: String) {
        if (database == SHARED_PREFERENCES) return
        val columns = columns[database to table]
        when {
            columns == null -> node.add(DefaultMutableTreeNode(LOADING))
            columns.isEmpty() -> node.add(DefaultMutableTreeNode(Message("No columns")))
            else -> columns.forEach { node.add(DefaultMutableTreeNode(ColumnItem(database, table, it))) }
        }
    }

    /** Lists the tables of every database not listed yet, for the search; each shows up as soon as it is listed. */
    private fun listMissingTables() {
        val client = client ?: return
        val missing = databases.map { it.name }.filter { it !in tables }
        if (missing.isEmpty()) return
        background(status, "Searching tables") {
            missing.forEach { database ->
                val listed = client.tables(database)
                ui {
                    if (this.client !== client) return@ui
                    tables[database] = listed
                    if (search.isNotEmpty()) rebuild()
                }
            }
            ui { status.done() }
        }
    }

    private fun databaseAt(path: TreePath) =
        ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? DatabaseItem)?.database?.name

    private fun tableAt(path: TreePath) =
        ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? TableItem)?.let { it.database to it.table }

    /** The database and table of [item]: a column's are its table's. */
    private fun tableOf(item: Any?): Pair<String, String?>? = when (item) {
        is DatabaseItem -> item.database.name to null
        is TableItem -> item.database to item.table
        is ColumnItem -> item.database to item.table
        else -> null
    }

    /**
     * Where a new query goes: the selected database, with the selected table if it is one. With nothing selected, or
     * the preferences, the first database; null when there is no database to query.
     */
    fun queryTarget(): Pair<String, String?>? {
        val selected = tableOf((component.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject)
        if (selected != null && selected.first != SHARED_PREFERENCES) return selected
        return root.children().asSequence()
            .map { ((it as DefaultMutableTreeNode).userObject as DatabaseItem).database.name }
            .firstOrNull { it != SHARED_PREFERENCES }
            ?.let { it to null }
    }

    private fun loadTables(node: DefaultMutableTreeNode, database: String) {
        val client = client ?: return
        background(status, "Opening $database") {
            val tables = client.tables(database)
            ui {
                if (this.client !== client) return@ui
                this.tables[database] = tables
                node.removeAllChildren()
                addTables(node, database, tables)
                treeModel.nodeStructureChanged(node)
                status.done()
            }
        }
    }

    /**
     * Through [DebugDbClient.withDatabase], unlike [loadTables]: tabs may have switched the server to another
     * database since this one was listed.
     */
    private fun loadColumns(node: DefaultMutableTreeNode, database: String, table: String) {
        val client = client ?: return
        background(status, "Reading the columns of $table") {
            val details = client.withDatabase(database) { columnDetails(columns(table), foreignKeys(table)) }
            ui {
                if (this.client !== client) return@ui
                columns[database to table] = details
                node.removeAllChildren()
                addColumns(node, database, table)
                treeModel.nodeStructureChanged(node)
                status.done()
            }
        }
    }

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = component.getPathForLocation(e.x, e.y) ?: return
        component.selectionPath = path
        val (database, table) = tableOf((path.lastPathComponent as DefaultMutableTreeNode).userObject) ?: return
        JBPopupMenu().apply {
            if (table != null) add(JBMenuItem("Open").apply { addActionListener { onOpen(database, table) } })
            add(
                JBMenuItem("New Query").apply {
                    // debug-db's query only knows SQLite databases.
                    isEnabled = database != SHARED_PREFERENCES
                    addActionListener { onQuery(database, table) }
                },
            )
        }.show(component, e.x, e.y)
    }

    private fun openSelected() {
        val (database, table) = tableOf((component.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject) ?: return
        onOpen(database, table ?: return)
    }

    private companion object {
        val LOADING = Message("Loading…")
        val SEARCHING = Message("Listing tables…")
        val NO_MATCH = Message("No table matches")
        const val SHARED_PREFERENCES = "APP_SHARED_PREFERENCES"
    }
}

/** A column as the tree shows it: its name, then [description] in grey, such as `INTEGER, NOT NULL, → article.id`. */
internal data class ColumnDetails(val name: String, val inPrimaryKey: Boolean, val description: String)

/** [columns] in table order, each with its declared type, constraints and the foreign key it is in, if any. */
internal fun columnDetails(columns: List<DebugDbClient.ColumnInfo>, foreignKeys: List<DebugDbClient.ForeignKey>): List<ColumnDetails> =
    columns.map { column ->
        val description = listOfNotNull(
            column.type.takeIf { it.isNotEmpty() },
            "NOT NULL".takeIf { column.notNull },
            column.defaultValue?.let { "DEFAULT $it" },
        ) + foreignKeys.filter { it.column == column.name }.map { "→ ${it.targetTable}.${it.targetColumn}" }
        ColumnDetails(column.name, column.keyPosition > 0, description.joinToString(", "))
    }
