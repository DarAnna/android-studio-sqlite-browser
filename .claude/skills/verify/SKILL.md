---
name: verify
description: Check that a change to the plugin works - unit tests, lint, build, then the end-to-end check against the sample app on an emulator. Use before saying a change works, before committing, or when the user says /verify.
---

# Verify

Run these in order from the repo root. Stop at the first failure, fix it, and start again from that step.

1. **Unit tests:** `./gradlew test`. Failures are in `build/reports/tests/test/index.html`; the XML in `build/test-results/test/` is easier to read with tools.
2. **Lint:** `./gradlew ktlintCheck`. Fix with `./gradlew ktlintFormat`, then fix by hand what remains.
3. **Build:** `./gradlew buildPlugin`.
4. **End to end:** `scripts/e2e-check.sh` (port 8080; if the script says another app holds it, use `DEBUG_DB_PORT=8081 scripts/e2e-check.sh`). It needs a running emulator or phone (`adb devices`). If there is none, say so and skip this step; don't start an emulator without asking. The script installs `sample-app/`, waits for it to seed its databases, and checks the debug-db endpoints the plugin uses.

Run `./gradlew verifyPlugin` too when the change touches IntelliJ Platform APIs (anything under `ui/`, `plugin.xml`, or new platform imports). It downloads Android Studio for the check on the first run, so it is slow.

## What the checks don't cover

The Swing UI itself. If the change is in `ui/`, say that it still needs a look in the sandbox (`/run-sandbox`), and what to look at.

## Report

One line per step: passed, failed (with the first real error), or skipped (with why).
