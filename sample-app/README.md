# Sample app

A small Android app to try the plugin on. Its debug build includes
[debug-db](https://github.com/amitshekhariitbhu/Android-Debug-Database) 1.0.7, which serves the
app's databases over HTTP on port 8080.

On first launch it fills two databases on a background thread, in one transaction, and shows how
long that took:

- `shop.db`
  - `article`: 50 rows. Its column `article_id` contains the table name, which trips debug-db's
    table-name rewrite in `query` (`ORDER BY article_id` becomes `ORDER BY [article]_id`).
  - `big_table`: 150,000 rows of about 1,000 characters each. `getAllDataFromTheTable` on it runs
    the app out of memory, so the plugin must page through `query` instead.
- `settings.db`
  - `flag`: 4 rows, so more than one database shows up.

Seeding takes a few seconds on an emulator. Later launches skip it.

## Build, install, run

It is a separate Gradle build; use the root wrapper. Run from the repository root:

```sh
./gradlew -p sample-app installDebug
adb shell am start -n io.github.daranna.sqlitebrowser.sample/.MainActivity
adb forward tcp:8080 tcp:8080
curl http://localhost:8080/getDbList
```

The build finds the Android SDK through `ANDROID_HOME`, or through `sdk.dir` in
`sample-app/local.properties` (git-ignored).

If another debug-db app on the device already holds port 8080, this app's server cannot start.
Build it on another port with `-PdebugDbPort=8081` and forward that port.

To check the whole path end to end, run `scripts/e2e-check.sh` (see `--help`).
