# Ideas

Unscheduled feature ideas. Nothing here is committed to.

## Next steps (before the two ideas below)

- Multiple tabs.
- Query window for own queries.
- Editing in query result views and in the table view.
- Clean up the UX of the utility view (the db connect panel); maybe hide it.
- Cache opened databases.
- Find out why SharedPreferences doesn't show up in the speedy app.

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
- Navigation: open in the same table view with a filter and a way back, or in a new tab. The existing `ColumnFilter` work may already cover the filter part.
- The `WHERE` clause must respect the table name rule: the referenced table's name must appear only once in the SQL.
