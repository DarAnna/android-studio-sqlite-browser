---
name: run-sandbox
description: Start a sandbox Android Studio with the plugin installed, connected to the sample app, so a person can try a UI change. Use when the user wants to see or try the plugin, or says /run-sandbox.
---

# Run the sandbox

The sandbox is a second Android Studio with its own settings and the plugin loaded. It can't harm the user's normal Studio.

1. **Check the Studio path.** `grep localStudioPath ~/.gradle/gradle.properties`. If it's missing, ask the user before running: without it Gradle downloads a 1.2 GB Android Studio. On this project's main machine the path is `/Users/annadar/Applications/Android Studio.app`.
2. **Get a debug-db app running.** If `adb devices` shows a device, install and start the sample app with `scripts/e2e-check.sh`. It also leaves `adb forward` in place for its port: 8080, or `DEBUG_DB_PORT` if another debug app holds 8080. Tell the user which port to type in the plugin. If no device is attached, tell the user to start an emulator.
3. **Start the sandbox in the background:** `./gradlew runIde`. It blocks until the window closes, so run it with `run_in_background`. The first start takes a minute or two.
4. **Tell the user what to do:** open any project in the sandbox, open the **SQLite Browser** tool window at the bottom, pick the device, press **Connect**, pick `shop.db`, then a table. `big_table` is the large one; `article` has a column whose name contains the table name.

You can't see or click the sandbox window, so the user checks the UI. Ask them what they saw.

To pick up code changes, the sandbox must be closed and started again.
