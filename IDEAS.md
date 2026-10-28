# Ideas

Unscheduled feature ideas. Nothing here is committed to.

## Next steps (before the two ideas below)

1. Query window for own queries, as another kind of tab next to `TableView`. Page with `SELECT * FROM (<sql>) LIMIT … OFFSET …`, and warn when the user's SQL breaks the table name rule.

Later: editing in query results. A free-form result has no single table or primary keys, so this probably only works for a plain `SELECT * FROM t …`.

Done:
- Tabs, a database tree and a compact connection bar ([#2](https://github.com/DarAnna/android-studio-sqlite-browser/pull/2)). Tabs load through `DebugDbClient.withDatabase`, which reopens their database when another tab switched it.
- Device, port and open tabs kept across IDE restarts (`SavedSession`, in the workspace file). On start the panel reconnects only if the same app answers on the saved port, and reopens only tabs whose database and table the app still has.
- Editing in the table view: edit a cell, set it to NULL, add a row, delete rows. Only tables with a single-column primary key can have rows edited or deleted, since debug-db picks the row by the first key column alone; the key comes from `PRAGMA table_info`.

Settled: SharedPreferences don't show up in apps that use DataStore instead, because debug-db only lists `shared_prefs/` XML files.

## 1. Free text to query (AI)

Type a question in plain language ("articles written by user 3 in the last week") and get the SQL, run through the same paged `query` path as the rest of the browser.

Things to settle:
- Which model or provider, and where the API key lives (IDE credential store, never the project).
- What goes in the prompt: the schema only (table and column names, types), never row data, since the app's database may hold real user data.
- Show the generated SQL and let the user edit it before it runs.
- Read-only: refuse anything but `SELECT`.
- The generated SQL must respect the debug-db table name rule (the table name appears once in the SQL; see CLAUDE.md).

## 2. Follow a foreign key

When a column is a foreign key, show a button in the cell that opens all rows of the referenced table filtered by that value.

Things to settle:
- Detecting foreign keys: `PRAGMA foreign_key_list(table)` through `query`, once per table when it loads. Check that debug-db's `query` endpoint passes PRAGMA through.
- Which rows to show: the single referenced row, or every row in the other table that matches the value. The latter is what was asked for; both could be offered.
- Navigation: open in the same table view with a filter and a way back, or in a new tab. Tabs exist now (`openTable` in `SqliteBrowserPanel`), so a new tab with the filter already set is the cheaper option. The existing `ColumnFilter` work may already cover the filter part.
- The `WHERE` clause must respect the table name rule: the referenced table's name must appear only once in the SQL.

## 3. App Inspection instead of debug-db

Once the plugin works well on debug-db, try building it on Android Studio's App Inspection, the framework behind the Database Inspector. Then apps would need no extra library and no setup, and the plugin would see live data on the app's own connection, Room and WAL included.

It would also fix a limit of editing through debug-db: the app's screens don't show the edit until they read again. Room learns about changes from temporary triggers, which fire only for writes on Room's own connection, and debug-db writes on a separate one. The Database Inspector's agent tells Room's `InvalidationTracker` to recheck after each change, so its edits show up right away.

It looks possible. Studio 2026.1's Android plugin has an extension point, `com.android.tools.idea.appinspection.inspector.ide.appInspectorTabProvider`, which Google's own inspectors use. A plugin can register there too with `<depends>org.jetbrains.android</depends>`. For the device side, either reuse Google's SQLite agent (Apache 2.0, shipped in Studio as `app-inspection/sqlite-inspection.jar`) or write one with `androidx.inspection`.

The risks:
- **No stable API.** These are internal classes, so Studio updates may break the plugin.
- **Few docs.** The only reference is the AOSP source: `tools/adt/idea/app-inspection` and `frameworks/support/sqlite/sqlite-inspection`.
- **A possible clash.** Reusing Google's agent may clash with the Database Inspector, since each inspector ID attaches to an app only once.

Plan: keep debug-db as the base, and build a small prototype on a branch to measure both problems first.
