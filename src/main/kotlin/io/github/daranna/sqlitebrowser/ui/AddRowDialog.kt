package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient
import java.awt.Component
import javax.swing.JComponent

/** Asks for the values of a new row. A column left empty is left out of the INSERT, so it gets its default. */
class AddRowDialog(parent: Component, table: String, private val columns: List<DebugDbClient.Column>) : DialogWrapper(parent, false) {

    private val fields = columns.map { JBTextField(30).apply { emptyText.text = "default" } }

    init {
        title = "Add Row to $table"
        init()
    }

    /** Column name to typed text, for the columns the user filled in. */
    val values: Map<String, String>
        get() = columns.zip(fields).filter { (_, field) -> field.text.isNotEmpty() }
            .associate { (column, field) -> column.name to field.text }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder().apply {
        columns.zip(fields).forEach { (column, field) -> addLabeledComponent(column.name, field) }
    }.panel

    override fun getPreferredFocusedComponent() = fields.firstOrNull()

    // debug-db inserts with no "null column hack", so an INSERT without columns fails.
    override fun doValidate() = if (values.isEmpty()) ValidationInfo("Fill in at least one column", fields.firstOrNull()) else null
}
