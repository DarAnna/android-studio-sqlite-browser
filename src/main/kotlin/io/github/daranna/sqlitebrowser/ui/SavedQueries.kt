package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * The SQL last run on each database, and the user's favorite queries. Application-wide, unlike [SavedSession]: SQL
 * fits an app's schema in any project. Keyed by app and database, since it rarely fits another app's.
 */
@Service(Service.Level.APP)
@State(name = "SqliteBrowserQueries", storages = [Storage("sqliteBrowser.xml")])
class SavedQueries : PersistentStateComponent<SavedQueries.Queries> {

    class Queries {
        var last: MutableList<Query> = mutableListOf()
        var favorites: MutableList<Query> = mutableListOf()

        fun lastQuery(app: String, database: String): String? = last.firstOrNull { it.app == app && it.database == database }?.sql

        fun setLast(app: String, database: String, sql: String) {
            last.removeAll { it.app == app && it.database == database }
            last += Query(app, database, sql)
        }

        /** [app]'s favorites, by database and then name. */
        fun favoritesOf(app: String): List<Query> =
            favorites.filter { it.app == app }.sortedWith(compareBy({ it.database }, { it.name.lowercase() }))

        /** Adds a favorite, replacing the one of the same name on the same database. */
        fun addFavorite(app: String, database: String, name: String, sql: String) {
            favorites.removeAll { it.app == app && it.database == database && it.name == name }
            favorites += Query(app, database, sql, name)
        }

        /** Renames [favorite], replacing another of the new name on its database. */
        fun rename(favorite: Query, name: String) {
            favorites.removeAll { it !== favorite && it.app == favorite.app && it.database == favorite.database && it.name == name }
            favorite.name = name
        }

        fun delete(favorite: Query) {
            favorites.removeAll { it === favorite }
        }
    }

    /** SQL run on [database] of [app]; a favorite also has a [name]. */
    data class Query(var app: String = "", var database: String = "", var sql: String = "", var name: String = "")

    private var queries = Queries()

    override fun getState() = queries

    override fun loadState(state: Queries) {
        queries = state
    }

    companion object {
        fun get(): Queries = service<SavedQueries>().state
    }
}
