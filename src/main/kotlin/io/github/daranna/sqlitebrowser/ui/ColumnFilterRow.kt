package io.github.daranna.sqlitebrowser.ui

import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.FocusEvent
import java.awt.event.FocusListener
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.event.ChangeEvent
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent
import javax.swing.event.TableColumnModelEvent
import javax.swing.event.TableColumnModelListener

/**
 * One text field under each column header, kept in line with the columns as they are resized or moved. The fields
 * have no frame of their own: they sit in the table's grid, and the one with focus is outlined.
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
        background = table.background
        border = IDLE_BORDER
        addFocusListener(object : FocusListener {
            override fun focusGained(e: FocusEvent) {
                border = FOCUSED_BORDER
            }

            override fun focusLost(e: FocusEvent) {
                border = IDLE_BORDER
            }
        })
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
        val height = fields.firstOrNull()?.let { maxOf(it.preferredSize.height, table.rowHeight) + LINE } ?: 0
        return Dimension(table.columnModel.totalColumnWidth, height)
    }

    /** Leaves a line under the fields and between them, where [paintComponent] draws the table's grid. */
    override fun doLayout() {
        var x = 0
        for (view in 0 until table.columnModel.columnCount) {
            val width = table.columnModel.getColumn(view).width
            fields.getOrNull(table.convertColumnIndexToModel(view))?.setBounds(x, 0, width - LINE, height - LINE)
            x += width
        }
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (fields.isEmpty()) return
        g.color = table.gridColor
        val width = table.columnModel.totalColumnWidth
        g.fillRect(0, height - LINE, width, LINE)
        var x = 0
        for (view in 0 until table.columnModel.columnCount) {
            x += table.columnModel.getColumn(view).width
            g.fillRect(x - LINE, 0, LINE, height)
        }
    }

    private companion object {
        const val LINE = 1
        val IDLE_BORDER = JBUI.Borders.empty(0, 6)
        val FOCUSED_BORDER = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBUI.CurrentTheme.Focus.focusColor(), 1),
            JBUI.Borders.empty(0, 5),
        )
    }
}
