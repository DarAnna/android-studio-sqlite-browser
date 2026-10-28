package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.components.JBLabel

/** Runs [task] on a pooled thread, showing what it does in [status] and why it failed, if it does. Call on the EDT. */
internal fun background(status: JBLabel, what: String, task: () -> Unit) {
    status.text = "$what…"
    ApplicationManager.getApplication().executeOnPooledThread {
        try {
            task()
        } catch (e: Exception) {
            ui { status.text = "$what failed: ${e.message ?: e.javaClass.simpleName}" }
        }
    }
}

internal fun ui(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any())
