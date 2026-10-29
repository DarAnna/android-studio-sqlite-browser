# Ideas

Unscheduled feature ideas. Nothing here is committed to.

## Next steps (before the ideas below)

None queued.

Later: the other way along a foreign key, from a row to the rows that refer to it (an article's purchases). Needs every table's `PRAGMA foreign_key_list`, not just the open one's.

Later: editing in query results. A free-form result has no single table or primary keys, so this probably only works for a plain `SELECT * FROM t …`.

Done:
- Following a foreign key from the table view. `DebugDbClient.foreignKeys` reads `PRAGMA foreign_key_list` (it passes through `query`) once per table, single-column keys only; a key without a column (`REFERENCES article`) means the target's primary key. A cell gets an arrow icon; a click opens the target table in a new tab, even when it is open already so that tab's filters stay, and `TableView.filterBy` sets `=value` in the target column's filter, which already keeps to the table name rule. That shows every matching row, which for a key into a primary key is the one row. Closing that tab selects the one it was followed from. The sample app's `purchase` table refers to `article`.
- Tabs, a database tree and a compact connection bar ([#2](https://github.com/DarAnna/android-studio-sqlite-browser/pull/2)). Tabs load through `DebugDbClient.withDatabase`, which reopens their database when another tab switched it.
- Device, port and open tabs kept across IDE restarts (`SavedSession`, in the workspace file). On start the panel reconnects only if the same app answers on the saved port, and reopens only tabs whose database and table the app still has.
- Editing in the table view: edit a cell, set it to NULL, add a row, delete rows. Only tables with a single-column primary key can have rows edited or deleted, since debug-db picks the row by the first key column alone; the key comes from `PRAGMA table_info`.
- Recent queries and favorites (`SavedQueries`, application-level, keyed by app and database). Run puts the SQL it sent at the top of the database's 20 recent queries; **New Query** on a database starts with the newest, on a table still with `SELECT * FROM <table>`. The ☆ in a query tab toggles its SQL as a favorite, named after its first line. Both are listed beside the editor (`SavedQueryList`); a click runs read-only SQL (`UserSql.readOnly`) and only loads the rest. A favorite naming a table that is gone just fails on Run.
- Highlighting and completion in the query tab (`SqlEditor`): Android Studio's Room SQL language in an `EditorTextField` on a `LightVirtualFile`, with names through the Database Inspector's `SqliteSchemaContext` and keywords from `SqlKeywordCompletion`. Internal Android plugin classes, not an API; `SqlEditorCompletionTest` catches a move.
- Query tabs (`QueryView`), opened with **New Query** above the tree or its right-click menu. A last `SELECT` pages with `SELECT * FROM (<sql>) LIMIT … OFFSET …`. Instead of warning about the table name rule, `UserSql` hides table names from debug-db (`FROM"article"`), so the rule doesn't apply and results keep their own column names. See CONTRIBUTING.md.

Settled: SharedPreferences don't show up in apps that use DataStore instead, because debug-db only lists `shared_prefs/` XML files.

## 1. Free text to query (AI)

Type a question in plain language ("articles written by user 3 in the last week") and get the SQL, run through the same paged `query` path as the rest of the browser.

Things to settle:
- Which model or provider, and where the API key lives (IDE credential store, never the project).
- What goes in the prompt: the schema only (table and column names, types), never row data, since the app's database may hold real user data.
- Show the generated SQL and let the user edit it before it runs.
- Read-only: refuse anything but `SELECT`.
- Run the generated SQL through the query tab's `UserSql`, which takes care of the table name rule.

## 2. App Inspection instead of debug-db

Once the plugin works well on debug-db, try building it on Android Studio's App Inspection, the framework behind the Database Inspector. Then apps would need no extra library and no setup, and the plugin would see live data on the app's own connection, Room and WAL included.

It would also fix a limit of editing through debug-db: the app's screens don't show the edit until they read again. Room learns about changes from temporary triggers, which fire only for writes on Room's own connection, and debug-db writes on a separate one. The Database Inspector's agent tells Room's `InvalidationTracker` to recheck after each change, so its edits show up right away.

It looks possible. Studio 2026.1's Android plugin has an extension point, `com.android.tools.idea.appinspection.inspector.ide.appInspectorTabProvider`, which Google's own inspectors use. A plugin can register there too with `<depends>org.jetbrains.android</depends>`. For the device side, either reuse Google's SQLite agent (Apache 2.0, shipped in Studio as `app-inspection/sqlite-inspection.jar`) or write one with `androidx.inspection`.

The risks:
- **No stable API.** These are internal classes, so Studio updates may break the plugin.
- **Few docs.** The only reference is the AOSP source: `tools/adt/idea/app-inspection` and `frameworks/support/sqlite/sqlite-inspection`.
- **A possible clash.** Reusing Google's agent may clash with the Database Inspector, since each inspector ID attaches to an app only once.

Plan: keep debug-db as the base, and build a small prototype on a branch to measure both problems first.

## 3. Room integration: open from an entity file

In an `@Entity` class, a gutter icon or an editor action opens that entity's table in the browser, so there's no hunting through the tree.

Things to settle:
- Table name: `@Entity(tableName = …)` if set, else the class name. Read it with UAST so Kotlin and Java both work.
- Which database: the entity is listed in a `@Database(entities = […])` class, but the file name only appears in `Room.databaseBuilder(…, "name.db")`, which may be built at runtime. Simpler: ask the connected app which of its databases has that table, and offer a choice when several do. Remember that `databases()` comes before `tables()`, and only names it returned may be passed.
- Not connected: open the panel and connect first, or show a hint instead of the icon doing nothing.
- Maybe also: a `@Query` in a `@Dao` opens in a query tab with its SQL, with `:param` placeholders left for the user to fill in.
