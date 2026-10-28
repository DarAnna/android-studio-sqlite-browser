#!/usr/bin/env bash
# End-to-end check of debug-db, as the plugin uses it, against the sample app.
#
# Builds and installs sample-app, starts it, waits for it to seed its databases, forwards
# port 8080 and checks the debug-db endpoints the plugin calls.
#
# Usage: scripts/e2e-check.sh [--fresh] [--skip-build] [--crash-repro]
#   --fresh        clear the app's data first, so it seeds again
#   --skip-build   do not build and install; use the app already on the device
#   --crash-repro  after the checks, call getAllDataFromTheTable on big_table and
#                  check that the app dies (the bug the plugin works around)
#
# Picks the device from $ANDROID_SERIAL, or the only one attached.
# debug-db listens on $DEBUG_DB_PORT (default 8080) on the device; the same port is forwarded on
# this machine. Set it when another debug-db app on the device already holds 8080.
# Needs adb, curl and jq.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="io.github.daranna.sqlitebrowser.sample"
ACTIVITY="$PACKAGE/.MainActivity"
PORT="${DEBUG_DB_PORT:-8080}"
BASE_URL="http://localhost:$PORT"
BIG_TABLE_ROWS=150000
ARTICLE_ROWS=50
SEED_TIMEOUT_S=300

FRESH=0
SKIP_BUILD=0
CRASH_REPRO=0
for arg in "$@"; do
    case "$arg" in
        --fresh) FRESH=1 ;;
        --skip-build) SKIP_BUILD=1 ;;
        --crash-repro) CRASH_REPRO=1 ;;
        -h|--help) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Unknown argument: $arg" >&2; exit 2 ;;
    esac
done

die() { echo "ERROR: $*" >&2; exit 2; }

for tool in adb curl jq; do
    command -v "$tool" >/dev/null || die "$tool not found on PATH"
done

# --- Device ---------------------------------------------------------------------------------
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    devices=()
    while IFS= read -r serial; do devices+=("$serial"); done \
        < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
    case "${#devices[@]}" in
        0) die "no device attached (adb devices lists none in state 'device')" ;;
        1) ANDROID_SERIAL="${devices[0]}" ;;
        *) die "several devices attached (${devices[*]}); set ANDROID_SERIAL" ;;
    esac
fi
export ANDROID_SERIAL
adb get-state >/dev/null 2>&1 || die "device $ANDROID_SERIAL is not ready"
echo "Device: $ANDROID_SERIAL"

# --- Install and start ----------------------------------------------------------------------
if [[ "$SKIP_BUILD" == 0 ]]; then
    echo "Building and installing the sample app..."
    "$REPO_ROOT/gradlew" -p "$REPO_ROOT/sample-app" installDebug -q "-PdebugDbPort=$PORT"
fi
if [[ "$FRESH" == 1 ]]; then
    adb shell pm clear "$PACKAGE" >/dev/null
fi
adb shell am force-stop "$PACKAGE"
adb shell am start -n "$ACTIVITY" >/dev/null
adb forward "tcp:$PORT" "tcp:$PORT" >/dev/null

# Package that listens on $PORT on the device, or empty. Another debug-db app may hold the port;
# then our app's server cannot bind and every request would go to that app.
port_owner() {
    local hex uid
    hex="$(printf '%04X' "$PORT")"
    uid="$(adb shell cat /proc/net/tcp6 /proc/net/tcp 2>/dev/null \
        | awk -v p=":$hex" '$2 ~ p"$" && $4 == "0A" { print $8; exit }')"
    [[ -n "$uid" ]] || return 0
    adb shell pm list packages -U 2>/dev/null | tr -d '\r' \
        | awk -v u="uid:$uid" '$2 == u || $2 ~ "^"u"," { sub("package:", "", $1); print $1; exit }'
}

# GET an endpoint; prints the body. The server serves one request at a time, so calls are serial.
get() {
    curl -sS --fail --max-time "${2:-60}" "$BASE_URL/$1"
}

uri_encode() {
    jq -rn --arg s "$1" '$s | @uri'
}

query() {
    get "query?query=$(uri_encode "$1")"
}

# Value of the first cell of a query response, or empty.
first_value() {
    jq -r '.rows[0][0].value // empty'
}

echo "Waiting for debug-db and for seeding (up to ${SEED_TIMEOUT_S}s)..."
start=$SECONDS
count=""
while true; do
    owner="$(port_owner)"
    if [[ -n "$owner" && "$owner" != "$PACKAGE" ]]; then
        die "port $PORT on the device is held by $owner, not $PACKAGE. Stop that app" \
            "(adb shell am force-stop $owner) or rerun with DEBUG_DB_PORT=<free port>."
    fi
    # Call getDbList first, every time, and getTableList only for a database it listed:
    # getTableList for a database the server has not listed throws a NullPointerException in
    # debug-db's server thread, and the server stops answering until the app restarts.
    dbs=""
    if [[ "$owner" == "$PACKAGE" ]]; then
        dbs="$(get getDbList 10 2>/dev/null | jq -r '[.rows[][0]] | join(",")' 2>/dev/null || true)"
    fi
    if [[ ",$dbs," == *",shop.db,"* ]] && get "getTableList?database=shop.db" 10 >/dev/null 2>&1; then
        count="$(query "SELECT COUNT(*) FROM big_table" 2>/dev/null | first_value 2>/dev/null || true)"
        [[ "$count" == "$BIG_TABLE_ROWS" ]] && break
    fi
    if (( SECONDS - start > SEED_TIMEOUT_S )); then
        die "timed out waiting for seeding (big_table count: '${count:-none}')"
    fi
    sleep 2
done
echo "Seeded after $(( SECONDS - start ))s of waiting"

# --- Checks ---------------------------------------------------------------------------------
FAILURES=0
check() {
    local name="$1"; shift
    local detail
    if detail="$("$@" 2>&1)"; then
        echo "PASS  $name${detail:+  ($detail)}"
    else
        echo "FAIL  $name${detail:+  ($detail)}"
        FAILURES=$((FAILURES + 1))
    fi
}

check_db_list() {
    local names
    names="$(get getDbList | jq -r '[.rows[][0]] | join(",")')"
    [[ ",$names," == *",shop.db,"* && ",$names," == *",settings.db,"* ]] || { echo "got: $names"; return 1; }
    echo "$names"
}

check_table_list() {
    local names
    names="$(get "getTableList?database=shop.db" | jq -r '.rows | join(",")')"
    [[ ",$names," == *",article,"* && ",$names," == *",big_table,"* ]] || { echo "got: $names"; return 1; }
    echo "$names"
}

check_big_table_page() {
    local body ok rows first
    body="$(query "SELECT * FROM big_table ORDER BY 1 DESC LIMIT 500 OFFSET 0")"
    ok="$(jq -r '.isSuccessful' <<<"$body")"
    rows="$(jq -r '.rows | length' <<<"$body")"
    first="$(jq -r '.rows[0][0].value' <<<"$body")"
    echo "isSuccessful=$ok rows=$rows first id=$first"
    [[ "$ok" == true && "$rows" == 500 && "$first" == "$BIG_TABLE_ROWS" ]]
}

check_big_table_count() {
    local n
    n="$(query "SELECT COUNT(*) FROM big_table" | first_value)"
    echo "count=$n"
    [[ "$n" == "$BIG_TABLE_ROWS" ]]
}

check_article_number_sort() {
    local body ok rows first
    # article_id contains the table name, so the SQL may hold "article" only once: sort by number.
    # Column 3 is price; prices fall as ids rise, so the dearest article is id 1.
    get "getTableList?database=shop.db" >/dev/null
    body="$(query "SELECT * FROM article ORDER BY 3 DESC LIMIT $ARTICLE_ROWS OFFSET 0")"
    ok="$(jq -r '.isSuccessful' <<<"$body")"
    rows="$(jq -r '.rows | length' <<<"$body")"
    first="$(jq -r '.rows[0][0].value' <<<"$body")"
    echo "isSuccessful=$ok rows=$rows first article_id=$first"
    [[ "$ok" == true && "$rows" == "$ARTICLE_ROWS" && "$first" == 1 ]]
}

check_article_name_sort_trap() {
    # Documents the rewrite: "ORDER BY article_id" becomes "ORDER BY [article]_id" and fails.
    local ok
    ok="$(query "SELECT * FROM article ORDER BY article_id LIMIT 1" | jq -r '.isSuccessful')"
    echo "isSuccessful=$ok (false expected)"
    [[ "$ok" == false ]]
}

check_article_filter() {
    # The SQL the plugin sends for filters "article 1" on name and ">=100" on price (DebugDbClient.select):
    # columns renamed by position, the table named once, and the lowercase "article" in the literal cut in two.
    # Names are "Article <i>" and prices 1000/i, so that leaves ids 1 and 10.
    local renamed="SELECT 0 AS c0, 0 AS c1, 0 AS c2, 0 AS c3 WHERE 0 UNION ALL SELECT * FROM article"
    local where="CAST(c1 AS TEXT) LIKE '%a'||'rticle 1%' ESCAPE '\\' AND c2 >= 100"
    local body ok ids n eq
    get "getTableList?database=shop.db" >/dev/null
    body="$(query "SELECT * FROM ($renamed) WHERE $where ORDER BY 1 ASC LIMIT 500 OFFSET 0")"
    ok="$(jq -r '.isSuccessful' <<<"$body")"
    ids="$(jq -r '[.rows[][0].value] | join(",")' <<<"$body")"
    n="$(query "SELECT COUNT(*) FROM ($renamed) WHERE $where" | first_value)"
    eq="$(query "SELECT * FROM ($renamed) WHERE c0 IN (7, '7') LIMIT 500 OFFSET 0" | jq -r '[.rows[][0].value] | join(",")')"
    echo "isSuccessful=$ok ids=$ids count=$n =7 gives $eq"
    [[ "$ok" == true && "$ids" == "1,10" && "$n" == 2 && "$eq" == 7 ]]
}

check_settings_db() {
    local n
    get "getTableList?database=settings.db" >/dev/null
    n="$(query "SELECT COUNT(*) FROM flag" | first_value)"
    echo "flag rows=$n"
    [[ "$n" -gt 0 ]]
}

check_app_alive() {
    local pid
    pid="$(adb shell pidof "$PACKAGE" | tr -d '\r')" || true
    [[ -n "$pid" ]] || { echo "no process"; return 1; }
    echo "pid=$pid"
}

check "getDbList lists shop.db and settings.db" check_db_list
check "getTableList(shop.db) lists article and big_table" check_table_list
check "big_table page of 500 rows via query" check_big_table_page
check "big_table COUNT(*) matches seeded rows" check_big_table_count
check "article sorted by column number" check_article_number_sort
check "article sorted by name hits the table-name rewrite" check_article_name_sort_trap
check "article filtered by column, as the plugin filters" check_article_filter
check "settings.db flag table readable" check_settings_db
check "app process still alive" check_app_alive

# --- Optional crash reproduction ------------------------------------------------------------
if [[ "$CRASH_REPRO" == 1 ]]; then
    check_crash_repro() {
        get "getTableList?database=shop.db" >/dev/null
        local before code
        before="$(adb shell pidof "$PACKAGE" | tr -d '\r')" || true
        code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 180 \
            "$BASE_URL/getAllDataFromTheTable?tableName=big_table" || true)"
        sleep 3
        local after
        after="$(adb shell pidof "$PACKAGE" | tr -d '\r')" || true
        local oom
        oom="$(adb logcat -d -t 2000 2>/dev/null | grep -c 'OutOfMemoryError' || true)"
        echo "pid before=$before after=${after:-none} http=$code OutOfMemoryError lines in logcat=$oom"
        [[ -n "$before" && "$after" != "$before" ]]
    }
    check "getAllDataFromTheTable(big_table) kills the app" check_crash_repro
fi

echo
if (( FAILURES > 0 )); then
    echo "$FAILURES check(s) failed"
    exit 1
fi
echo "All checks passed"
