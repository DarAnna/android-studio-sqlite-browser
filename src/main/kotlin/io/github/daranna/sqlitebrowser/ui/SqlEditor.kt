package io.github.daranna.sqlitebrowser.ui

import com.android.tools.idea.lang.androidSql.AndroidSqlFileType
import com.android.tools.idea.sqlite.model.RowIdName
import com.android.tools.idea.sqlite.model.SqliteAffinity
import com.android.tools.idea.sqlite.model.SqliteColumn
import com.android.tools.idea.sqlite.model.SqliteSchema
import com.android.tools.idea.sqlite.model.SqliteTable
import com.android.tools.idea.sqlite.sqlLanguage.SqliteSchemaContext
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.EditorTextField
import io.github.daranna.sqlitebrowser.debugdb.DebugDbClient

/**
 * The SQL editor of a [QueryView], in Android Studio's own SQLite language: the one of Room's `@Query` and the
 * Database Inspector. It highlights, matches brackets, and completes table and column names and, through
 * [SqlKeywordCompletion], keywords.
 *
 * The language finds names through the Database Inspector's `SqliteSchemaContext`, which reads a [SqliteSchema] from
 * the edited file's user data, so the editor works on a [LightVirtualFile] that carries one. These are internal
 * classes of the Android plugin, not an API, and a Studio update may move them.
 */
internal class SqlEditor(private val project: Project, sql: String) {

    private val file = LightVirtualFile("query.sql", AndroidSqlFileType.INSTANCE, sql).apply {
        putUserData(QUERY_TAB_FILE, true)
    }

    val document = FileDocumentManager.getInstance().getDocument(file)!!

    val component = EditorTextField(document, project, AndroidSqlFileType.INSTANCE, false, false).apply {
        setFontInheritedFromLAF(false)
        addSettingsProvider { editor ->
            editor.setVerticalScrollbarVisible(true)
            editor.setHorizontalScrollbarVisible(true)
            editor.settings.isCaretRowShown = true
            // A gutter like the editor's, so the SQL doesn't touch the tab's edge.
            editor.settings.isLineNumbersShown = true
            editor.settings.isFoldingOutlineShown = false
            editor.settings.additionalLinesCount = 0
            editor.setBorder(null)
        }
    }

    val text: String get() = document.text

    val selectedText: String? get() = component.editor?.selectionModel?.selectedText

    /** Tables and their columns, for completion. */
    fun setSchema(tables: Map<String, List<DebugDbClient.ColumnInfo>>) {
        setSchema(file, tables)
        PsiDocumentManager.getInstance(project).getPsiFile(document)?.let {
            DaemonCodeAnalyzer.getInstance(project).restart(it, "names were resolved against the old schema")
        }
    }

    companion object {
        /** Marks the files of query tabs, so [SqlKeywordCompletion] stays out of Room's `@Query` strings. */
        val QUERY_TAB_FILE = Key.create<Boolean>("io.github.daranna.sqlitebrowser.queryTabFile")

        fun setSchema(file: VirtualFile, tables: Map<String, List<DebugDbClient.ColumnInfo>>) {
            val schema = SqliteSchema(
                tables.map { (name, columns) ->
                    val sqliteColumns = columns.map {
                        SqliteColumn(it.name, SqliteAffinity.fromTypename(it.type), !it.notNull, it.keyPosition > 0)
                    }
                    SqliteTable(name, sqliteColumns, RowIdName.ROWID, false)
                },
            )
            file.putUserData(SqliteSchemaContext.SQLITE_SCHEMA_KEY, schema)
        }
    }
}
