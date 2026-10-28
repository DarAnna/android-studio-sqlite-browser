# SQLite Browser for Android Studio

An Android Studio plugin that shows the SQLite databases of a running debug app, live, in a proper table view.

It talks to the HTTP server of [Android-Debug-Database](https://github.com/amitshekhariitbhu/Android-Debug-Database) (debug-db), which runs inside your debug app. The plugin replaces debug-db's web page; it ships none of debug-db's code.

```
Android Studio plugin  --adb forward-->  debug-db server (in the debug app)  -->  SQLite files
```

**Status:** first step. You can pick a device, connect, pick a database and a table, and see its rows. You can sort by clicking a column header. Editing, filters and the SQL console come next.

## Try it

### 1. Add debug-db to an app

In the app's `settings.gradle.kts`, add JitPack:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { setUrl("https://jitpack.io") }
    }
}
```

In the app module's `build.gradle.kts`:

```kotlin
debugImplementation("com.github.amitshekhariitbhu.Android-Debug-Database:debug-db:1.0.7")
```

Run the debug build on an emulator or phone. debug-db starts by itself and listens on port 8080.

### 2. Run the plugin

Tell Gradle where your Android Studio is, so it does not download a second copy (1.2 GB). Put this line in `~/.gradle/gradle.properties`, not in the project's file:

```properties
localStudioPath=/Applications/Android Studio.app
```

Then:

```bash
./gradlew runIde
```

This starts a second Android Studio, the *sandbox*, with the plugin installed. It has its own settings and plugins, so it cannot break your normal Studio. Open any project in it, then open the **SQLite Browser** tool window at the bottom.

Pick the device, keep port 8080, press **Connect**, then pick a database and a table.

To install the plugin in your normal Studio instead: `./gradlew buildPlugin`, then *Settings → Plugins → ⚙ → Install Plugin from Disk* and pick `build/distributions/sqlite-browser-0.1.0.zip`.

## How the code fits together

| File | What it does |
|---|---|
| `src/main/resources/META-INF/plugin.xml` | Tells the IDE what the plugin adds. Here: one tool window. |
| `ui/SqliteBrowserToolWindowFactory.kt` | The IDE calls this the first time the tool window opens. |
| `ui/SqliteBrowserPanel.kt` | The Swing panel: device, port, database and table pickers, the table. |
| `ui/TableDataModel.kt` | Feeds rows to the `JBTable`. Editing will go here. |
| `debugdb/DebugDbClient.kt` | Calls debug-db's HTTP endpoints and parses the JSON. |
| `adb/Adb.kt` | Finds and runs the `adb` binary: list devices, `adb forward`. |

### Plugin basics worth knowing

- **plugin.xml is the entry point.** The IDE reads it at startup and creates your classes only when needed. Nothing runs unless plugin.xml points at it.
- **The UI is Swing.** IntelliJ adds its own versions of the usual parts (`JBTable`, `JBLabel`, `ComboBox`); use them so the plugin follows the IDE theme.
- **Two kinds of thread.** Touch Swing parts only on the UI thread (the *EDT*). Do slow work, like adb and HTTP, on a background thread. `SqliteBrowserPanel.background()` and `ui()` show the pattern. If you block the EDT, the whole IDE freezes.
- **Disposables.** Things that must be cleaned up register with a parent `Disposable`; the IDE disposes them when the tool window or project closes.
- **Debugging.** Run the `runIde` task in debug mode from IntelliJ IDEA (or Android Studio) and breakpoints in the plugin work in the sandbox.

Docs: [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html), especially *Tool Windows*, *Threading Model* and *Android Studio Plugin Development*.

### Which IDE to write the plugin in

IntelliJ IDEA (free Community or Ultimate) has the best support for plugin work: it understands the Gradle plugin and offers *Plugin DevKit*. Android Studio also works, since it is IntelliJ underneath. Open this folder as a Gradle project in either.

## debug-db quirks

From reading debug-db 1.0.7's `RequestHandler.java`. The client handles these; they matter when you change it.

- **The server keeps state.** `getTableList?database=x` opens database `x`. Every later table call, `query` included, works on that database. So always call `tables()` before `tableData()` for a different database.
- **One request at a time.** The server serves sockets one by one, so `DebugDbClient` makes its methods `synchronized`.
- **Names are not decoded.** `database` and `tableName` are cut from the URL after the first `=`. Names with spaces or `&` break.
- **Edit calls are decoded twice.** `updateTableData`, `addTableData` and `deleteTableData` decode the whole URL, then parse it, so their JSON must be encoded twice.
- **Edits send every column.** Each row is a list of `{title, isPrimary, dataType, value}`. Primary key columns build the `WHERE`; the string `"null"` means SQL NULL.
- **BLOBs come back as text**, and every value is typed by SQLite's storage class, not the column's declared type.
- **`isEditable`** is true only for a plain table (not a view) whose columns all came back.
- **No paging.** `getAllDataFromTheTable` sends the whole table.
- The `getDbList` rows are `[name, isEncrypted, isDownloadable]`, and the list ends with a fake database, `APP_SHARED_PREFERENCES`.

## Next steps

1. Inline cell editing: make `TableDataModel` editable when `data.editable` is true, and call `DebugDbClient.updateRow`.
2. A filter box per column: build a `WHERE` and use `DebugDbClient.query` in place of `tableData`.
3. Row insert and delete.
4. SQL console tab.
5. Auto-refresh toggle.

## Licence

Apache 2.0, like debug-db.
