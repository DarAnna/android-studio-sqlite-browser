package io.github.daranna.sqlitebrowser.ui

import com.android.tools.idea.lang.androidSql.psi.AndroidSqlPsiTypes
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbAware

/**
 * SQL keywords in the query tab's completion. The language completes only names, from the references it resolves;
 * Room never needed keywords, since its SQL sits in a string the user has mostly written already.
 */
class SqlKeywordCompletion :
    CompletionContributor(),
    DumbAware {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.virtualFile?.getUserData(SqlEditor.QUERY_TAB_FILE) != true) return
        val position = parameters.position
        // Not in strings or comments, and not after `table.`, where only columns fit.
        if (position.node.elementType != AndroidSqlPsiTypes.IDENTIFIER) return
        if (parameters.originalFile.text.getOrNull(parameters.offset - result.prefixMatcher.prefix.length - 1) == '.') return
        // People type keywords in lower case too.
        val keywords = result.caseInsensitive()
        KEYWORDS.forEach {
            // Below the names, which are what completion is mostly for.
            keywords.addElement(PrioritizedLookupElement.withPriority(LookupElementBuilder.create(it).bold(), -1.0))
        }
    }

    private companion object {
        val KEYWORDS = listOf(
            "SELECT", "DISTINCT", "FROM", "WHERE", "GROUP BY", "HAVING", "ORDER BY", "ASC", "DESC", "LIMIT", "OFFSET",
            "JOIN", "LEFT", "INNER", "CROSS", "OUTER", "ON", "USING", "AS", "AND", "OR", "NOT", "IN", "IS", "NULL",
            "LIKE", "GLOB", "BETWEEN", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "UNION", "ALL", "INTERSECT",
            "EXCEPT", "WITH", "RECURSIVE", "INSERT", "INTO", "VALUES", "DEFAULT", "REPLACE", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "VIEW", "INDEX", "TRIGGER", "UNIQUE", "PRIMARY KEY", "REFERENCES", "DROP", "ALTER",
            "RENAME", "ADD", "COLUMN", "IF", "PRAGMA", "COLLATE", "CAST", "ESCAPE", "BEGIN", "COMMIT", "ROLLBACK",
        )
    }
}
