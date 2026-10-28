package io.github.daranna.sqlitebrowser.debugdb

private val OPERATORS = listOf(">=", "<=", "<>", "!=", "=", ">", "<")
private val NUMBER = Regex("-?\\d+(\\.\\d+)?")

/**
 * SQL condition for what the user typed into a column's filter box, or null when it filters nothing.
 *
 * Plain text matches anywhere in the value, ignoring case. A leading `=`, `!=` (or `<>`), `>`, `>=`, `<` or `<=`
 * compares instead, as a number when the rest looks like one.
 *
 * [column] is the alias the query gives the column. Real column names can't be used: debug-db rewrites the table
 * name inside them, so a column `article_id` in table `article` would become `[article]_id`.
 */
internal fun filterCondition(column: String, text: String, table: String): String? {
    val input = text.trim()
    val operator = OPERATORS.firstOrNull { input.startsWith(it) }
    val value = (if (operator == null) input else input.removePrefix(operator)).trim()
    if (value.isEmpty()) return null
    val number = NUMBER.matches(value)
    return when {
        operator == null -> "CAST($column AS TEXT) LIKE ${literal("%" + escapeLike(value) + "%", table)} ESCAPE '\\'"

        // The aliased columns have lost their type affinity, so 5 no longer equals '5': try both.
        operator == "=" && number -> "$column IN ($value, '$value')"

        operator == "=" -> "$column = ${literal(value, table)}"

        // Rows with NULL count as "not equal" too.
        (operator == "!=" || operator == "<>") && number -> "($column IS NULL OR $column NOT IN ($value, '$value'))"

        operator == "!=" || operator == "<>" -> "$column IS NOT ${literal(value, table)}"

        else -> "$column $operator ${if (number) value else literal(value, table)}"
    }
}

private fun escapeLike(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/**
 * An SQL string literal for [text], safe from debug-db's rewrites: it splits a query at every `;`, so those become
 * `char(59)`, and it rewrites the table name everywhere, so the literal is cut inside the name and joined with `||`.
 */
private fun literal(text: String, table: String): String {
    val quoted = text.replace("'", "''").replace(";", "'||char(59)||'")
    val cut = quoted.replace(tableName(table)) { it.value.take(1) + "'||'" + it.value.drop(1) }
    return "'$cut'"
}

/** Matches [table] as debug-db's `String.replace` does: case-sensitive, anywhere. */
internal fun tableName(table: String) = Regex(Regex.escape(table))
