package io.github.daranna.sqlitebrowser.debugdb

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Client for the HTTP server in Android-Debug-Database (debug-db) 1.0.7.
 *
 * Quirks of that server, from its RequestHandler.java:
 * - It keeps state: `getTableList(db)` opens `db`, and every later table call works on it.
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

    data class TableData(
        val columns: List<Column>,
        val rows: List<List<Cell>>,
        val editable: Boolean,
    )

    /** Databases plus debug-db's "APP_SHARED_PREFERENCES" pseudo-database. */
    @Synchronized
    fun databases(): List<Database> {
        val json = get("getDbList")
        return json.getAsJsonArray("rows").map { row ->
            val fields = row.asJsonArray
            Database(fields[0].asString, fields[1].asString == "true")
        }
    }

    /** Opens [database] on the server and returns its tables and views. */
    @Synchronized
    fun tables(database: String): List<String> =
        get("getTableList?database=$database").getAsJsonArray("rows").map { it.asString }

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
     */
    @Synchronized
    fun tablePage(table: String, offset: Int, limit: Int, orderBy: Int? = null, descending: Boolean = false): TableData {
        val order = if (orderBy == null) "" else " ORDER BY ${orderBy + 1}" + if (descending) " DESC" else " ASC"
        return parseTableData(get("query?query=" + encode("SELECT * FROM $table$order LIMIT $limit OFFSET $offset")))
    }

    @Synchronized
    fun rowCount(table: String): Long {
        val data = parseTableData(get("query?query=" + encode("SELECT COUNT(*) FROM $table")))
        return (data.rows.firstOrNull()?.firstOrNull()?.value as? Number)?.toLong() ?: 0
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

    private fun get(path: String): JsonObject {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/$path"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw DebugDbException("HTTP ${response.statusCode()} from $path")
        return gson.fromJson(response.body(), JsonObject::class.java)
    }

    private fun encode(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}

class DebugDbException(message: String) : Exception(message)
