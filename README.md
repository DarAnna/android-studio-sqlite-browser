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
- **Refresh** a tab to see what your app wrote since.

Coming next: editing cells, adding and deleting rows, and a query tab for your own SQL.

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
