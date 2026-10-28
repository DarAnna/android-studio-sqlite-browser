# CLAUDE.md

Android Studio plugin (Kotlin, IntelliJ Platform Gradle Plugin 2.x) that shows a debug app's SQLite databases live. The backend is the HTTP server of Android-Debug-Database (debug-db) 1.0.7 inside the app, reached through `adb forward`. CONTRIBUTING.md has the full picture; read its "debug-db quirks" before touching `DebugDbClient`. README.md is for users of the plugin.

## Commands

| What | Command |
|---|---|
| Unit tests (fake debug-db server, no device) | `./gradlew test` |
| Lint (CI runs this) | `./gradlew ktlintCheck` |
| Build the plugin zip | `./gradlew buildPlugin` |
| Check API compatibility with Android Studio | `./gradlew verifyPlugin` |
| End-to-end against a real debug-db (needs an emulator) | `scripts/e2e-check.sh` |
| Start a sandbox Android Studio with the plugin | `./gradlew runIde` (blocks until the sandbox closes) |

`localStudioPath` in `~/.gradle/gradle.properties` points at the installed Android Studio. Without it, Gradle downloads a 1.2 GB copy. Never commit it to the project's gradle.properties.

`/verify` runs tests, lint, build and the end-to-end check in order. Run it before saying a change works.

## Rules that are easy to break

- **The table name appears once in query SQL.** debug-db replaces every occurrence of the table name in `query` SQL with `[name]`, so a column `article_id` in table `article` breaks. Sort by column number (`ORDER BY 3`), and plan any `WHERE` with this in mind. The sample app's `article` table exists to catch this.
- **Never call `getAllDataFromTheTable`.** It builds the whole table as one JSON string in the app and crashes large apps with `OutOfMemoryError`. Page through `query` with `LIMIT`/`OFFSET`.
- **The server keeps state.** `tables(db)` opens `db`; every later call runs against it.
- **Call `databases()` before `tables()`, and pass only names it returned.** Anything else kills debug-db's server thread until the app restarts. The port then accepts connections but never answers.
- **One debug-db app per port.** If another debug app on the device holds 8080, run the sample app and check on another port: `./gradlew -p sample-app installDebug -PdebugDbPort=8081` and `DEBUG_DB_PORT=8081 scripts/e2e-check.sh`. The script stops and names the app if the port belongs to someone else. Never send requests to a port that belongs to another app.
- **Edit endpoints need their JSON encoded twice**; `query` needs it once; `database` and `tableName` are never decoded.
- **Threads.** Swing only on the EDT; adb and HTTP on a pooled thread. Follow `background { }` and `ui { }` in `ui/Threads.kt`.
- **Tabs share one client and one server.** A tab's loads go through `client.withDatabase(db) { ... }`, which reopens its database when another tab switched it.
- **Test data comes from `sample-app/`**, never from a real app's database. The repo is public.

## Code

- `debugdb/DebugDbClient.kt`: every HTTP call. Add endpoints here, with a test in `DebugDbClientTest` against `FakeDebugDbServer`.
- `ui/`: Swing panel and table model. Use IntelliJ components (`JBTable`, `JBLabel`, `ComboBox`).
- `adb/Adb.kt`: runs the `adb` binary.
- Kotlin style: ktlint with `.editorconfig`; a hook formats files after each edit.
- Keep comments to the why, as in the existing code.
