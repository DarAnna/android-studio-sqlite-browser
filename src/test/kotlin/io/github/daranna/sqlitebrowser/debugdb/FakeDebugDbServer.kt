package io.github.daranna.sqlitebrowser.debugdb

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder

/**
 * Stands in for debug-db's HTTP server in tests: answers each endpoint with canned JSON
 * and records the raw requests, so tests can check exactly what the client sent.
 *
 * Responses copy the shapes of debug-db 1.0.7 (Gson with serializeNulls).
 */
class FakeDebugDbServer : AutoCloseable {

    /** Raw path and query of each request, e.g. `query?query=SELECT%20...`, as debug-db sees them. */
    val requests = mutableListOf<String>()

    /** Endpoint name (`getDbList`, `query`, ...) to the JSON body it returns. */
    val responses = mutableMapOf<String, String>()

    /** SQL of a `query` request to the JSON body it returns, ahead of [responses], for calls that send several queries. */
    val queries = mutableMapOf<String, String>()

    private val server = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
        createContext("/") { exchange ->
            val uri = exchange.requestURI
            val route = uri.rawPath.removePrefix("/") + (uri.rawQuery?.let { "?$it" } ?: "")
            synchronized(requests) { requests += route }
            val endpoint = uri.rawPath.removePrefix("/")
            val sql = if (endpoint == "query") URLDecoder.decode(uri.rawQuery.substringAfter("="), Charsets.UTF_8) else null
            val body = queries[sql] ?: responses[endpoint]
            if (body == null) {
                exchange.sendResponseHeaders(500, -1)
            } else {
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        start()
    }

    val port: Int get() = server.address.port

    /** The SQL of the last `query` request, decoded once, as debug-db decodes it. */
    fun lastQuerySql(): String {
        val route = requests.last { it.startsWith("query?") }
        return URLDecoder.decode(route.substringAfter("="), Charsets.UTF_8)
    }

    override fun close() = server.stop(0)
}
