package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

/**
 * The SQL recently run on each database, and the user's favorite queries. Application-wide, unlike [SavedSession]:
 * SQL fits an app's schema in any project. Keyed by app and database, since it rarely fits another app's.
 */
@Service(Service.Level.APP)
@State(name = "SqliteBrowserQueries", storages = [Storage("sqliteBrowser.xml")])
class SavedQueries : PersistentStateComponent<SavedQueries.Queries> {

    class Queries {
        /** Newest first. */
        var recent: MutableList<Query> = mutableListOf()
        var favorites: MutableList<Query> = mutableListOf()

        fun lastQuery(app: String, database: String): String? = recentOf(app, database).firstOrNull()?.sql

        /** Newest first. */
        fun recentOf(app: String, database: String): List<Query> = recent.filter { it.app == app && it.database == database }

        /** Moves [sql] to the top of the database's recent queries, keeping [RECENT_LIMIT] of them. */
        fun addRecent(app: String, database: String, sql: String, time: Long = System.currentTimeMillis()) {
            recent.removeAll { it.app == app && it.database == database && same(it.sql, sql) }
            recent.add(0, Query(app, database, sql, time = time))
            recentOf(app, database).drop(RECENT_LIMIT).forEach { old -> recent.removeAll { it === old } }
        }

        /** [database]'s favorites, by name. */
        fun favoritesOf(app: String, database: String): List<Query> =
            favorites.filter { it.app == app && it.database == database }.sortedBy { it.name.lowercase() }

        fun favoriteOf(app: String, database: String, sql: String): Query? =
            favorites.firstOrNull { it.app == app && it.database == database && same(it.sql, sql) }

        /** Adds [sql] as a favorite named after its first line, made unique on the database; rename it later. */
        fun addFavorite(app: String, database: String, sql: String): Query {
            favoriteOf(app, database, sql)?.let { return it }
            val base = sql.trim().lineSequence().first().replace(WHITESPACE, " ").trim().take(NAME_LENGTH).ifBlank { "Query" }
            val taken = favoritesOf(app, database).map { it.name }.toSet()
            val name = if (base !in taken) base else generateSequence(2) { it + 1 }.map { "$base ($it)" }.first { it !in taken }
            return Query(app, database, sql, name).also { favorites += it }
        }

        /** Renames [favorite], replacing another of the new name on its database. */
        fun rename(favorite: Query, name: String) {
            favorites.removeAll { it !== favorite && it.app == favorite.app && it.database == favorite.database && it.name == name }
            favorite.name = name
        }

        fun delete(favorite: Query) {
            favorites.removeAll { it === favorite }
        }

        /** Whitespace around the SQL doesn't make it another query. */
        private fun same(a: String, b: String) = a.trim() == b.trim()
    }

    /** SQL run on [database] of [app]; a favorite also has a [name], a recent query the [time] it last ran. */
    data class Query(var app: String = "", var database: String = "", var sql: String = "", var name: String = "", var time: Long = 0)

    /** Told after recent queries or favorites changed, so every query tab's list shows them. */
    fun interface Listener {
        fun changed()
    }

    private var queries = Queries()

    override fun getState() = queries

    override fun loadState(state: Queries) {
        queries = state
    }

    companion object {
        const val RECENT_LIMIT = 20
        private const val NAME_LENGTH = 40
        private val WHITESPACE = Regex("\\s+")

        val TOPIC = Topic.create("SQLite browser saved queries", Listener::class.java)

        fun get(): Queries = service<SavedQueries>().state

        /** Changes the saved queries and tells every query tab. */
        fun <T> change(block: Queries.() -> T): T = get().block().also { changed() }

        fun changed() = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
    }
}
