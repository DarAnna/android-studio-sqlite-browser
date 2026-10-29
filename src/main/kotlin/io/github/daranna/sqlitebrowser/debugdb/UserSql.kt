package io.github.daranna.sqlitebrowser.debugdb

/**
 * SQL the user typed, split into statements and rewritten so debug-db runs it as written.
 *
 * debug-db's `TableNameParser` splits the SQL at whitespace and takes the word after FROM, JOIN, INTO, UPDATE or
 * TABLE as the table name, then brackets every occurrence of that name in the SQL, so a column `article_id` of table
 * `article` breaks. Gluing the name to the keyword in quotes, as in `FROM"article"`, leaves the parser one word
 * it doesn't know, so it finds no table and rewrites nothing. SQLite reads it as the same table. With no table found,
 * debug-db also names the result columns after the query's own columns instead of a guessed table's.
 *
 * The other rewrites, each for something debug-db would get wrong:
 * - Comments go and whitespace becomes one space: debug-db reads the first word up to a space to decide whether a
 *   statement returns rows, so `SELECT` followed by a newline, or a leading comment, would run as a write.
 * - `;` inside a string literal becomes `char(59)`: debug-db splits at every `;`.
 * - A leading `delete` becomes `DELETE`: the parser has a case-sensitive special case for `delete` that would take
 *   `FROM"t"` for a table name.
 */
class UserSql private constructor(
    /** The rewritten statements, in order, none empty. */
    val statements: List<String>,
    private val words: List<List<String>>,
) {
    /** The last statement when it returns rows that can be paged with `LIMIT` and `OFFSET`, else null. */
    val select: String? = statements.lastOrNull()?.takeIf { returnsRows(words.last()) }

    /** The statements to run once, in order, before paging [select]; all of them when there is no [select]. */
    val leading: List<String> = if (select == null) statements else statements.dropLast(1)

    /** Whether every statement only reads, so running it unasked can't change the app's data. */
    val readOnly: Boolean = statements.isNotEmpty() && statements.indices.all { readsOnly(statements[it], words[it]) }

    companion object {
        fun parse(sql: String): UserSql {
            val statements = mutableListOf<String>()
            val words = mutableListOf<List<String>>()
            val out = StringBuilder()
            val statementWords = mutableListOf<String>()
            // Whitespace seen since the last token, written as one space before the next token.
            var pendingSpace = false
            // The keyword just written is one a table name follows.
            var afterKeyword = false

            fun endStatement() {
                val text = out.toString()
                if (text.isNotEmpty()) {
                    statements += text
                    words += statementWords.toList()
                }
                out.clear()
                statementWords.clear()
                pendingSpace = false
                afterKeyword = false
            }

            /** Writes [token]; a [name] right after one of [HIDE_AFTER] is glued to it. */
            fun emit(token: CharSequence, name: Boolean = false) {
                if (pendingSpace && out.isNotEmpty() && !(afterKeyword && name)) out.append(' ')
                out.append(token)
                pendingSpace = false
                afterKeyword = false
            }

            var i = 0
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c.isWhitespace() -> {
                        pendingSpace = true
                        i++
                    }

                    sql.startsWith("--", i) -> {
                        pendingSpace = true
                        i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it + 1 }
                    }

                    sql.startsWith("/*", i) -> {
                        pendingSpace = true
                        i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                    }

                    c == ';' -> {
                        endStatement()
                        i++
                    }

                    c == '\'' -> {
                        val end = quoteEnd(sql, i, '\'')
                        emit(sql.substring(i, end).replace(";", "'||char(59)||'"))
                        i = end
                    }

                    c == '"' || c == '`' || c == '[' -> {
                        val end = quoteEnd(sql, i, if (c == '[') ']' else c)
                        emit(sql.substring(i, end), name = true)
                        i = end
                    }

                    c.isLetter() || c == '_' -> {
                        var end = i + 1
                        while (end < sql.length && (sql[end].isLetterOrDigit() || sql[end] == '_' || sql[end] == '$')) end++
                        val word = sql.substring(i, end)
                        val upper = word.uppercase()
                        when {
                            afterKeyword && upper !in NOT_A_NAME -> emit("\"$word\"", name = true)
                            statementWords.isEmpty() && upper == "DELETE" -> emit(upper)
                            else -> emit(word)
                        }
                        afterKeyword = upper in HIDE_AFTER
                        statementWords += upper
                        i = end
                    }

                    else -> {
                        emit(c.toString())
                        i++
                    }
                }
            }
            endStatement()
            return UserSql(statements, words)
        }

        /** Index just past the quote that closes the one at [start]; a doubled quote inside stands for itself. */
        private fun quoteEnd(sql: String, start: Int, close: Char): Int {
            var i = start + 1
            while (i < sql.length) {
                if (sql[i] == close) {
                    if (close != ']' && i + 1 < sql.length && sql[i + 1] == close) i += 2 else return i + 1
                } else {
                    i++
                }
            }
            return sql.length
        }

        /** A `WITH` can lead to a write instead of a SELECT, and a write can't be wrapped in `SELECT * FROM (...)`. */
        private fun returnsRows(words: List<String>): Boolean = when (words.firstOrNull()) {
            "SELECT", "VALUES" -> true
            "WITH" -> words.none { it in WRITES }
            else -> false
        }

        /** A PRAGMA sets its value with `=` or, for most of them, with an argument: `PRAGMA journal_mode(WAL)`. */
        private fun readsOnly(statement: String, words: List<String>): Boolean = when (words.firstOrNull()) {
            "PRAGMA" -> '=' !in statement && ('(' !in statement || words.getOrNull(1) in PRAGMAS_WITH_ARGUMENT)
            else -> returnsRows(words)
        }

        /** PRAGMAs whose argument names what to read rather than a value to set. */
        private val PRAGMAS_WITH_ARGUMENT = setOf(
            "TABLE_INFO", "TABLE_XINFO", "INDEX_INFO", "INDEX_XINFO", "INDEX_LIST", "FOREIGN_KEY_LIST",
            "FOREIGN_KEY_CHECK", "INTEGRITY_CHECK", "QUICK_CHECK",
        )

        private val HIDE_AFTER = setOf("FROM", "JOIN", "INTO", "UPDATE", "TABLE")

        /** Words that follow those keywords without being a name; quoting them would make them one. */
        private val NOT_A_NAME = setOf("OR", "IF", "SELECT", "VALUES", "WITH")

        private val WRITES = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")
    }
}
