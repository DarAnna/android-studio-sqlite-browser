# Working on the plugin

The plugin talks to the HTTP server of [Android-Debug-Database](https://github.com/amitshekhariitbhu/Android-Debug-Database) (debug-db), which runs inside the debug app. It replaces debug-db's web page; it ships none of debug-db's code.

```
Android Studio plugin  --adb forward-->  debug-db server (in the debug app)  -->  SQLite files
```

## Run it from source

Tell Gradle where your Android Studio is, so it does not download a second copy (1.2 GB). Put this line in `~/.gradle/gradle.properties`, not in the project's file:

```properties
localStudioPath=/Applications/Android Studio.app
```

Then:

```bash
./gradlew runIde
```

This starts a second Android Studio, the *sandbox*, with the plugin installed. It has its own settings and plugins, so it cannot break your normal Studio. Open any project in it, then open the **SQLite Browser** tool window at the bottom.

To try it, use [the sample app](sample-app), never a real app's database: the repo is public.

## Commands

| What | Command |
|---|---|
| Unit tests (fake debug-db server, no device) | `./gradlew test` |
| Lint | `./gradlew ktlintCheck` (fix with `./gradlew ktlintFormat`) |
| Build the plugin zip | `./gradlew buildPlugin` |
| Compatibility check with Android Studio | `./gradlew verifyPlugin` |
| End-to-end check against the sample app on an emulator | `scripts/e2e-check.sh` |

## How the code fits together

| File | What it does |
|---|---|
| `src/main/resources/META-INF/plugin.xml` | Tells the IDE what the plugin adds. Here: one tool window, and keyword completion for the query tab. |
| `ui/SqliteBrowserToolWindowFactory.kt` | The IDE calls this the first time the tool window opens. |
| `ui/SqliteBrowserPanel.kt` | The Swing panel: the connection bar, the database tree and a tab per opened table. |
| `ui/SavedSession.kt` | Device, port, app and open tabs, kept in the workspace file across IDE restarts. |
| `ui/SavedQueries.kt` | Recent and favorite queries; application-wide, keyed by app and database. Change them through `SavedQueries.change`, which tells every query tab. |
| `ui/SavedQueryList.kt` | A database's favorites and recent queries, beside a query tab's editor. |
| `ui/DatabaseTree.kt` | Databases and their tables; tables load when a database is expanded. |
| `ui/TableView.kt` | One table in a tab: paging, sorting, a filter per column, editing. |
| `ui/AddRowDialog.kt` | Asks for the values of a new row. |
| `ui/QueryView.kt` | A query tab: the user's SQL, run through `UserSql`, and its rows. |
| `ui/SqlEditor.kt` | The query tab's editor, in the Android plugin's SQLite language, fed the database's tables and columns. |
| `ui/SqlKeywordCompletion.kt` | Adds SQL keywords to that editor's completion. |
| `ui/Threads.kt` | `background()` and `ui()`: run slow work off the EDT and come back to it. |
| `ui/TableDataModel.kt` | Feeds rows to the `JBTable` and passes cell edits on to `TableView`. |
| `debugdb/DebugDbClient.kt` | Calls debug-db's HTTP endpoints and parses the JSON. |
| `debugdb/ColumnFilter.kt` | Turns what the user typed in a filter box into an SQL condition. |
| `debugdb/UserSql.kt` | Splits and rewrites the user's SQL so debug-db runs it as written. |
| `adb/Adb.kt` | Finds and runs the `adb` binary: list devices, `adb forward`. |

### Plugin basics worth knowing

- **plugin.xml is the entry point.** The IDE reads it at startup and creates your classes only when needed. Nothing runs unless plugin.xml points at it.
- **The UI is Swing.** IntelliJ adds its own versions of the usual parts (`JBTable`, `JBLabel`, `ComboBox`); use them so the plugin follows the IDE theme.
- **Two kinds of thread.** Touch Swing parts only on the UI thread (the *EDT*). Do slow work, like adb and HTTP, on a background thread. `background()` and `ui()` in `ui/Threads.kt` show the pattern. If you block the EDT, the whole IDE freezes.
- **Disposables.** Things that must be cleaned up register with a parent `Disposable`; the IDE disposes them when the tool window or project closes.
- **The Android plugin.** The query tab's editor uses Android Studio's own SQLite language, the one of Room's `@Query`, so plugin.xml `<depends>` on `org.jetbrains.android`. Table and column names reach it through the Database Inspector's `SqliteSchemaContext`, which reads a `SqliteSchema` from the edited file's user data. These are internal classes, not an API: after a Studio update, `SqlEditorCompletionTest` and `verifyPlugin` tell whether they moved.
- **Debugging.** Run the `runIde` task in debug mode from IntelliJ IDEA (or Android Studio) and breakpoints in the plugin work in the sandbox.

Docs: [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html), especially *Tool Windows*, *Threading Model* and *Android Studio Plugin Development*.

IntelliJ IDEA (free Community or Ultimate) has the best support for plugin work: it understands the Gradle plugin and offers *Plugin DevKit*. Android Studio also works, since it is IntelliJ underneath. Open this folder as a Gradle project in either.

## debug-db quirks

From reading debug-db 1.0.7's `RequestHandler.java`. The client handles these; they matter when you change it.

- **One bad `getTableList` stops the server for good.** If it runs before `getDbList` has run since the app started, or with a name `getDbList` didn't return, the server thread dies with a `NullPointerException`. The port stays open, so later requests connect but never get an answer. Only an app restart fixes it. Always call `getDbList` first and pass only names from it.
- **Two debug-db apps on one device can't share a port.** The second app's server fails without an error, and `adb forward` reaches whichever app holds the port, so you may be looking at the wrong app's data. Give each app its own port through debug-db's `PORT_NUMBER` string resource. The sample app takes `-PdebugDbPort=8081`.
- **`getDbList` also lists SQLite's side files** (`x.db-wal`, `x.db-shm`, `x.db-journal`), and `getTableList` includes Android's `android_metadata` table.
- **The server keeps state.** `getTableList?database=x` opens database `x`. Every later table call, `query` included, works on that database. So always call `tables()` before `tableData()` for a different database.
- **One request at a time.** The server serves sockets one by one, so `DebugDbClient` makes its methods `synchronized`.
- **Names are not decoded.** `database` and `tableName` are cut from the URL after the first `=`. Names with spaces or `&` break.
- **Edit calls are decoded twice.** `updateTableData`, `addTableData` and `deleteTableData` decode the whole URL, then parse it, so their JSON must be encoded twice.
- **Edit calls take a list of `{title, isPrimary, dataType, value}`.** Primary key columns build the `WHERE`; the other columns go into the `UPDATE` or `INSERT`, so the plugin sends only the key and the changed column, or only the columns the user filled in. The string `"null"` means SQL NULL.
- **An update without a primary key column changes every row**, since the `WHERE` stays empty. A delete without one reports success and deletes nothing. `DebugDbClient` refuses both.
- **`isPrimary` marks only the first column of a composite key** (`pk == 1` in `PRAGMA table_info`), so an edit would pick rows by that column alone. The plugin reads the key with `PRAGMA table_info` through `query` and edits rows only when the key is one column.
- **`integer` and `real` values are parsed with `Long.valueOf` and `Double.valueOf`**, which throw on NULL, and the edit then fails. So NULL and anything the user typed go as `text`; SQLite's column affinity turns `'5'` into 5 in numeric columns. A `dataType` other than `integer`, `real` or `text` is skipped by updates.
- **An add with no columns fails**: debug-db inserts without a "null column hack".
- **BLOBs come back as text**, and every value is typed by SQLite's storage class, not the column's declared type.
- **`isEditable`** is true only for a plain table (not a view) whose columns all came back.
- **`getAllDataFromTheTable` crashes the app on large tables.** It builds the whole table as one JSON string in the app's memory, and the app dies with `OutOfMemoryError`. So the plugin never calls it. It pages with `SELECT * FROM t LIMIT … OFFSET …` through `query`, which returns the same JSON, primary keys and `isEditable` included.
- **The server rewrites table names in your SQL.** In `query`, it finds the table name and replaces every occurrence of it in the SQL text with `[name]`. A column `article_id` in table `article` would become `[article]_id`. So the plugin sorts by column number (`ORDER BY 3`), never by name, and filters refer to columns by position aliases (`c0`, `c1`, …), so the SQL contains the table name only once.
- **How it finds the table name, and how to hide it.** debug-db's `TableNameParser` splits the SQL at whitespace and takes the word after `FROM`, `JOIN`, `INTO`, `UPDATE` or `TABLE` (`(` doesn't count). The name glued to the keyword in quotes, `FROM"article"`, is one word it doesn't know, so it finds no table and rewrites nothing. The query tab sends the user's SQL this way (`UserSql`). Not covered: `CREATE TABLE IF …`, `UPDATE OR …` and `CREATE INDEX … ON t`, where the parser takes another word.
- **Column names come from the guessed table.** `tableInfos` lists the guessed table's `PRAGMA table_info` columns whose names also appear in the result, so aliases, expressions and other tables' columns go missing. Only when it finds no table does it name the columns after the result's own. Another reason the query tab hides table names.
- **`query` reads the first word up to a space** to decide between rows (`select`, `pragma`) and `execSQL`, so `SELECT` followed by a newline, or a leading comment, runs as a write and returns nothing. It runs `;`-separated statements in order, stops at the first that fails and returns the last one's result.
- **A lowercase `delete` at the start** hits a case-sensitive special case in the parser that takes the next word, `from"t"`, for the table name.
- **Temporary views don't work.** A `CREATE TEMP VIEW` over the app's tables fails with "no such table" when selected from, so the query tab can't use one to learn column names.
- The `getDbList` rows are `[name, isEncrypted, isDownloadable]`, and the list ends with a fake database, `APP_SHARED_PREFERENCES`.

## Publishing to the Marketplace

`signPlugin` and `publishPlugin` need a signing key, its certificate, the key's password and a Marketplace token. None of them go in the repo. Make the key and certificate with `openssl` as in [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html), and put them outside the repo:

| What | Locally | CI (environment variable) |
|---|---|---|
| Certificate | `~/.config/jetbrains-marketplace/chain.crt` | `CERTIFICATE_CHAIN` (Base64) |
| Private key | `~/.config/jetbrains-marketplace/private.pem` | `PRIVATE_KEY` (Base64) |
| Key password, only for an encrypted key | `marketplaceKeyPassword` in `~/.gradle/gradle.properties` | `PRIVATE_KEY_PASSWORD` |
| Token | `marketplaceToken` in `~/.gradle/gradle.properties` | `PUBLISH_TOKEN` |

When both files exist, the build uses them instead of the variables. Then `./gradlew signPlugin` writes the signed zip to `build/distributions/`, and `./gradlew publishPlugin` uploads it. The first version of a plugin is uploaded by hand on plugins.jetbrains.com.

## What's next

See [IDEAS.md](IDEAS.md).
