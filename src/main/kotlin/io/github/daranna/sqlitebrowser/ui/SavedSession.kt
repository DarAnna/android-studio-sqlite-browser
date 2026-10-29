package io.github.daranna.sqlitebrowser.ui

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/**
 * The connection and open tabs of the panel, kept across IDE restarts. In the workspace file,
 * since a device serial means nothing on another machine.
 */
@Service(Service.Level.PROJECT)
@State(name = "SqliteBrowser", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SavedSession : PersistentStateComponent<SavedSession.Session> {

    class Session {
        var serial: String? = null
        var port: Int = 8080

        /** The app that answered on [port]. On start we reconnect only if the same app answers again. */
        var app: String? = null
        var tabs: MutableList<Tab> = mutableListOf()
        var selectedTab: Tab? = null
    }

    /** A table tab, or a query tab when [query] holds its SQL. */
    data class Tab(var database: String = "", var table: String = "", var query: String? = null)

    private var session = Session()

    override fun getState() = session

    override fun loadState(state: Session) {
        session = state
    }

    companion object {
        /**
         * The [saved] tabs the app still has, in their order: query tabs whose database it lists, table tabs whose
         * table it has. [tablesOf] is only asked about names in [databases], since debug-db dies on any other.
         */
        fun reopenable(saved: List<Tab>, databases: List<String>, tablesOf: (String) -> List<String>): List<Tab> {
            val tables = saved.filter { it.query == null }.map { it.database }.distinct().filter { it in databases }
                .associateWith { tablesOf(it).toSet() }
            return saved.filter { if (it.query != null) it.database in databases else tables[it.database]?.contains(it.table) == true }
        }
    }
}
