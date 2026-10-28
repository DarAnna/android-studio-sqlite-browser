package io.github.daranna.sqlitebrowser.debugdb

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Client for the HTTP server in Android-Debug-Database (debug-db) 1.0.7.
 *
 * Quirks of that server, from its RequestHandler.java:
 * - It keeps state: `getTableList(db)` opens `db`, and every later table call works on it.
 * - `query` splits the SQL at every `;`. A statement starting with `select` or `pragma` returns rows; any other
 *   runs with execSQL and returns none. It takes a table name that follows FROM or JOIN and brackets every
 *   occurrence of it, case-sensitively, even inside other names and string literals.
 * - It handles one request at a time, so calls here are synchronized.
 * - `database` and `tableName` are cut from the URL after the first `=` and never decoded,
 *   so names with spaces or `&` break.
 * - The edit endpoints decode the whole URL once and then parse it, so their JSON is encoded twice.
 */
class DebugDbClient(private val port: Int) {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val gson = Gson()

    data class Database(val name: String, val encrypted: Boolean)

    data class Column(val name: String, val isPrimaryKey: Boolean)

    data class Cell(val dataType: String, val value: Any?)

    data class TableData(val columns: List<Column>, val rows: List<List<Cell>>, val editable: Boolean)

    /** The database the server has open, as far as this client knows: the last one passed to [tables]. */
    private var openDatabase: String? = null

    /** Databases plus debug-db's "APP_SHARED_PREFERENCES" pseudo-database. */
    @Synchronized
    fun databases(): List<Database> {
        // Short timeout: this is the first call after connecting, and a debug-db server whose thread
        // died still accepts connections but never answers.
        val json = get("getDbList", Duration.ofSeconds(5))
        return json.getAsJsonArray("rows").map { row ->
            val fields = row.asJsonArray
            Database(fields[0].asString, fields[1].asString == "true")
        }.filterNot { db -> SQLITE_SIDE_FILES.any { db.name.endsWith(it) } }
    }

    /**
     * Opens [database] on the server and returns its tables and views.
     *
     * Only pass a name [databases] returned: any other name kills debug-db's server thread until the app restarts.
     */
    @Synchronized
    fun tables(database: String): List<String> {
        val tables = get("getTableList?database=$database").getAsJsonArray("rows")
            .map { it.asString }
            .filterNot { it == "android_metadata" }
        openDatabase = database
        return tables
    }

    /**
     * Runs [block] with [database] open on the server, holding the client's lock so no other caller
     * switches databases halfway through, e.g. between a page and its row count.
     *
     * Reopening asks [databases] first: after an app restart the server has forgotten the list, and
     * [tables] before [databases] would kill it.
     */
    @Synchronized
    fun <T> withDatabase(database: String, block: DebugDbClient.() -> T): T {
        if (openDatabase != database) {
            if (databases().none { it.name == database }) throw DebugDbException("The app no longer has database $database")
            tables(database)
        }
        return block()
    }

    /**
     * One page of [table] in the database last opened with [tables].
     *
     * We avoid `getAllDataFromTheTable`: it builds the whole table as one JSON string in the app,
     * which crashes the app with OutOfMemoryError on large tables.
     *
     * The server wraps the table name in `[...]` by replacing it everywhere in the SQL, so the SQL
     * must not contain the name anywhere else. That's why we sort by column number, not by name.
     *
     * @param orderBy 0-based column index to sort by, or null for table order.
     * @param filters what the user typed per column, in column order (see [filterCondition]); blank means no filter.
     *   When any is set, the result's columns are named `c0`, `c1`, ... and its `editable` flag is not reliable:
     *   keep the ones from an unfiltered page.
     */
    @Synchronized
    fun tablePage(
        table: String,
        offset: Int,
        limit: Int,
        orderBy: Int? = null,
        descending: Boolean = false,
        filters: List<String> = emptyList(),
    ): TableData {
        val order = if (orderBy == null) "" else " ORDER BY ${orderBy + 1}" + if (descending) " DESC" else " ASC"
        return parseTableData(get("query?query=" + encode(select(table, filters, "*", "$order LIMIT $limit OFFSET $offset"))))
    }

    @Synchronized
    fun rowCount(table: String, filters: List<String> = emptyList()): Long {
        val data = parseTableData(get("query?query=" + encode(select(table, filters, "COUNT(*)", ""))))
        return (data.rows.firstOrNull()?.firstOrNull()?.value as? Number)?.toLong() ?: 0
    }

    /**
     * Without filters this is plain `SELECT ... FROM table`. With filters, columns get renamed by position to
     * `c0`, `c1`, ..., which the conditions then use: in a `UNION ALL` the first SELECT names the columns, and
     * ours returns no rows.
     *
     * It must start with SELECT (debug-db runs anything else with execSQL, which returns no rows) and name no
     * table but [table] (debug-db picks any name after FROM and brackets it everywhere), so no CTE.
     */
    private fun select(table: String, filters: List<String>, projection: String, tail: String): String {
        val conditions = filters.mapIndexedNotNull { i, text -> filterCondition("c$i", text, table) }
        if (conditions.isEmpty()) return "SELECT $projection FROM $table$tail"
        val names = filters.indices.joinToString(", ") { "0 AS c$it" }
        val sql = "SELECT $projection FROM (SELECT $names WHERE 0 UNION ALL SELECT * FROM $table) WHERE " +
            conditions.joinToString(" AND ") + tail
        if (tableName(table).findAll(sql).count() != 1) {
            throw DebugDbException("Can't filter $table: its name also occurs in the generated SQL, and debug-db would rewrite it.")
        }
        return sql
    }

    /** Runs [sql] against the open database. Several statements may be separated by `;`. */
    @Synchronized
    fun query(sql: String): TableData = parseTableData(get("query?query=" + encode(sql)))

    /** Updates one row. [row] holds every column; primary key columns pick the row. */
    @Synchronized
    fun updateRow(table: String, row: List<RowValue>): Boolean = editCall("updateTableData", "updatedData", table, row)

    @Synchronized
    fun addRow(table: String, row: List<RowValue>): Boolean = editCall("addTableData", "addData", table, row)

    @Synchronized
    fun deleteRow(table: String, row: List<RowValue>): Boolean = editCall("deleteTableData", "deleteData", table, row)

    /** One column of a row as debug-db's `RowDataRequest` wants it. `value` "null" means SQL NULL. */
    data class RowValue(val title: String, val isPrimary: Boolean, val dataType: String, val value: String?)

    private fun editCall(endpoint: String, param: String, table: String, row: List<RowValue>): Boolean {
        val data = encode(encode(gson.toJson(row)))
        return get("$endpoint?tableName=${encode(table)}&$param=$data").get("isSuccessful")?.asBoolean == true
    }

    private fun parseTableData(json: JsonObject): TableData {
        if (json.get("isSuccessful")?.asBoolean != true) {
            throw DebugDbException(json.get("errorMessage")?.takeUnless { it.isJsonNull }?.asString ?: "Query failed")
        }
        val columns = json.getAsJsonArray("tableInfos")?.map {
            val info = it.asJsonObject
            Column(info.get("title").asString, info.get("isPrimary").asBoolean)
        }.orEmpty()
        val rows = json.getAsJsonArray("rows")?.map { row ->
            row.asJsonArray.map { cell ->
                val obj = cell.asJsonObject
                val value = obj.get("value")
                Cell(
                    obj.get("dataType").asString,
                    when {
                        value == null || value.isJsonNull -> null
                        value.asJsonPrimitive.isNumber -> value.asNumber
                        else -> value.asString
                    },
                )
            }
        }.orEmpty()
        return TableData(columns, rows, json.get("isEditable")?.asBoolean == true)
    }

    private fun get(path: String, timeout: Duration = Duration.ofSeconds(30)): JsonObject {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/$path"))
            .timeout(timeout)
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: HttpTimeoutException) {
            throw DebugDbException(
                "No answer on port $port within ${timeout.seconds}s. The app's debug-db server may be stuck; restart the app.",
            )
        }
        if (response.statusCode() != 200) throw DebugDbException("HTTP ${response.statusCode()} from $path")
        return gson.fromJson(response.body(), JsonObject::class.java)
    }

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

class DebugDbException(message: String) : Exception(message)

/** SQLite's companion files, which debug-db lists as if they were databases. */
private val SQLITE_SIDE_FILES = listOf("-journal", "-wal", "-shm")
