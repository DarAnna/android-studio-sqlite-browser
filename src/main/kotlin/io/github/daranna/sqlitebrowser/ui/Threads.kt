package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel

/** Where [background] shows what it does, and why it failed. Called on the EDT. */
interface Status {
    fun busy(what: String)
    fun failed(message: String)

    /** The work finished; called by the task itself, since [background] can't tell when its `ui { }` ran. */
    fun done()
}

/** A [Status] that is one label, also used for the task's own result. */
internal class LabelStatus(private val label: JBLabel) : Status {
    override fun busy(what: String) {
        label.text = "$what…"
    }

    override fun failed(message: String) {
        label.text = message
    }

    override fun done() {
        label.text = ""
    }
}

/** Runs [task] on a pooled thread, showing what it does in [status] and why it failed, if it does. Call on the EDT. */
internal fun background(status: JBLabel, what: String, task: () -> Unit) = background(LabelStatus(status), what, task)

internal fun background(status: Status, what: String, task: () -> Unit) {
    status.busy(what)
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            task()
        } catch (e: Exception) {
            ui { status.failed("$what failed: ${e.message ?: e.javaClass.simpleName}") }
        }
    }
}

internal fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
