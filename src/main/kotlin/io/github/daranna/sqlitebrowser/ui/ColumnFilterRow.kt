package io.github.daranna.sqlitebrowser.ui

import com.intellij.ui.components.JBTextField
import java.awt.Dimension
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

/**
 * One text field under each column header, kept in line with the columns as they are resized or moved.
 *
 * Meant for the scroll pane's column header view, so it scrolls sideways with the table.
 * [onChange] runs when the user edits a field, not when [show] sets the texts.
 */
class ColumnFilterRow(private val table: JTable, private val onChange: () -> Unit) : JPanel(null) {

    private val fields = mutableListOf<JBTextField>()
    private var syncing = false

    /** What is typed per column, in model order. */
    val texts: List<String> get() = fields.map { it.text }

    init {
        table.columnModel.addColumnModelListener(object : TableColumnModelListener {
            override fun columnAdded(e: TableColumnModelEvent) = revalidate()
            override fun columnRemoved(e: TableColumnModelEvent) = revalidate()
            override fun columnMoved(e: TableColumnModelEvent) = revalidate()
            override fun columnMarginChanged(e: ChangeEvent) = revalidate()
            override fun columnSelectionChanged(e: ListSelectionEvent) = Unit
        })
    }

    /** Shows [texts], one field per entry. Fields that already hold their text are left alone, so typing isn't disturbed. */
    fun show(texts: List<String>) {
        syncing = true
        try {
            if (fields.size != texts.size) {
                removeAll()
                fields.clear()
                texts.forEach { fields += newField().also(::add) }
                revalidate()
                repaint()
            }
            fields.zip(texts).forEach { (field, text) -> if (field.text != text) field.text = text }
        } finally {
            syncing = false
        }
    }

    private fun newField() = JBTextField().apply {
        emptyText.text = "Filter"
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = edited()
            override fun removeUpdate(e: DocumentEvent) = edited()
            override fun changedUpdate(e: DocumentEvent) = edited()
        })
    }

    private fun edited() {
        if (!syncing) onChange()
    }

    override fun getPreferredSize(): Dimension {
        val height = fields.firstOrNull()?.preferredSize?.height ?: 0
        return Dimension(table.columnModel.totalColumnWidth, height)
    }

    override fun doLayout() {
        var x = 0
        for (view in 0 until table.columnModel.columnCount) {
            val width = table.columnModel.getColumn(view).width
            fields.getOrNull(table.convertColumnIndexToModel(view))?.setBounds(x, 0, width, height)
            x += width
        }
    }
}
