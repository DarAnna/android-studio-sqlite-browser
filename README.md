# SQLite Browser for Android Studio

Look at your debug app's SQLite databases, live, but everything that annoyed me about Database Inspector was fixed.

## Why

Android Studio's App Inspection (the Database Inspector) is painful to use. You can see a table, but you can't simply narrow it down: for a simple filter you still write SQL by hand.

This plugin does the same job as the Database Inspector, but better. It starts with the basics the Inspector gets wrong, filtering first, and more is coming.

## Features

- **Filter by column.** Every column has a filter box under its header. Type and the table narrows down as you go, across the whole table, not just the visible page.
  - Plain text matches anywhere in the value, ignoring case: `alice` finds `Alice Smith`.
  - Start with an operator to compare instead: `=3`, `!=draft`, `>100`, `<=2026-01-01`. Numbers are compared as numbers.
  - Filters in several columns combine: a row must match all of them.
- **Sort by any column.** Click a header to sort the whole table by it: once ascending, twice descending, a third time back to table order.
- **Big tables are fine.** Rows load 500 at a time, so a table with hundreds of thousands of rows opens fast and doesn't run your app out of memory.
- **Tabs.** All databases and their tables sit in a tree; double-click a table to open it in its own tab. Keep several tables from several databases open side by side.
- **Picks up where you left off.** The plugin remembers the device, port and open tabs. Next time, it reconnects by itself if the same app is running.
- **Edit the data.** Double-click a cell to change it, right-click it to set it to NULL. **Add Row** inserts a row; columns you leave empty get their default. Select rows and press Delete (or **Delete**) to remove them. Changes go straight into the app's database.
  - Editing and deleting rows need a table with a single-column primary key, which picks the row. Other tables can only get new rows.
  - The key itself can't be changed.
  - Typing the text `null` stores NULL: debug-db can't tell them apart.
  - NULL shows as a grey `NULL`, so it doesn't look like an empty string.
  - Your app's screens don't update right away when they watch the data through Room (`Flow`, `LiveData`): they show the change the next time they read, e.g. when you reopen the screen. debug-db writes on its own connection, which Room doesn't notice. Android Studio's Database Inspector does tell Room.
- **Refresh** a tab to see what your app wrote since.
- **Your own SQL.** Press **New Query** above the tree to write SQL for the selected database; with a table selected, it starts as `SELECT * FROM` that table. Right-clicking a database or table does the same. Press **Run** (⌘⏎ or Ctrl+Enter); with text selected, only the selection runs.
  - A `SELECT` (or `WITH … SELECT`) loads 500 rows at a time, and you can sort it by clicking a header, like a table.
  - Several statements separated by `;` run in order, and you see the result of the last one. Paging and sorting run only that last `SELECT` again, never the statements before it.
  - Writes (`UPDATE`, `INSERT`, `DELETE`, …) go straight into the app's database. Query results can't be edited in the grid.
  - The SQL is kept when you restart Android Studio, but it doesn't run until you press **Run**.
  - **New Query** on a database starts with the SQL you last ran there, even after you closed its tab. On a table, it starts with `SELECT * FROM` that table.
  - **Add to Favorites** saves a tab's SQL under a name. **Favorites**, next to **New Query**, lists the app's favorites: open one in a new tab (it doesn't run), rename or delete it. Favorites and last queries are kept per app and database, in all your projects.
  - The editor highlights SQL and completes keywords and the database's table and column names (Ctrl+Space). It learns the names when the tab opens, on reconnect, and after you run `CREATE`, `DROP` or `ALTER`.

## Usage

### 1. Add debug-db to your app

The plugin reads the databases through [Android-Debug-Database](https://github.com/amitshekhariitbhu/Android-Debug-Database) (debug-db), a small server that runs inside your app's debug build only.

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

`debugImplementation` keeps it out of your release build.

### 2. Install the plugin

Needs Android Studio 2026.1 or newer.

There is no Marketplace release yet. Build the plugin from this repository:

```bash
./gradlew buildPlugin
```

Then in Android Studio: *Settings → Plugins → ⚙ → Install Plugin from Disk*, and pick `build/distributions/sqlite-browser-0.1.0.zip`.

### 3. Connect

1. Run your app's debug build on an emulator or phone. debug-db starts by itself on port 8080.
2. Open the **SQLite Browser** tool window at the bottom of Android Studio.
3. Pick the device, keep port 8080, and press **Connect**.
4. Expand a database in the tree and double-click a table.

### More than one debug app on the device?

Only one app can use port 8080. If another debug app with debug-db holds it, the plugin shows that app's name next to the port, and you'd be looking at the wrong data. Give your app its own port by adding this to its `res/values/strings.xml` (debug-db reads it):

```xml
<string name="PORT_NUMBER">8081</string>
```

Then connect on 8081.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building, running the plugin from source and how the code works. Ideas and what's planned are in [IDEAS.md](IDEAS.md).

## Licence

Apache 2.0, like debug-db.
