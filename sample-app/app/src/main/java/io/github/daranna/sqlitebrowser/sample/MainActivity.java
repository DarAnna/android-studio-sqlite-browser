package io.github.daranna.sqlitebrowser.sample;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the sample databases on a background thread, then lists {@code article} and {@code flag}.
 *
 * <p>The list reads the databases again on Refresh and whenever the app comes to the front, so
 * edits made through the plugin show up. Nothing watches the tables, as Room would: debug-db writes
 * on its own connection, which the app can't notice.
 */
public class MainActivity extends Activity {

    private static final String TAG = "SqliteBrowserSample";

    private ShopDbHelper shop;
    private SettingsDbHelper settings;
    private TextView status;
    private ArrayAdapter<String> rows;
    /** Set on the UI thread once seeding is done; until then the tables may be half filled. */
    private boolean seeded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        shop = new ShopDbHelper(getApplicationContext());
        settings = new SettingsDbHelper(getApplicationContext());

        status = new TextView(this);
        status.setText("Seeding databases…");
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(v -> load());
        rows = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        ListView list = new ListView(this);
        list.setAdapter(rows);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 48, 48, 0);
        layout.setFitsSystemWindows(true);
        layout.addView(status);
        layout.addView(refresh);
        layout.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(layout);

        new Thread(() -> {
            String message;
            try {
                long start = SystemClock.elapsedRealtime();
                settings.getWritableDatabase();
                boolean didSeed = shop.seedIfEmpty();
                long ms = SystemClock.elapsedRealtime() - start;
                message = didSeed
                        ? "Seeded " + ShopDbHelper.BIG_TABLE_ROWS + " rows in " + ms + " ms"
                        : "Already seeded";
                Log.i(TAG, "SEED_DONE " + message);
            } catch (RuntimeException e) {
                message = "Seeding failed: " + e;
                Log.e(TAG, "SEED_FAILED", e);
            }
            // Looked up by name: the build sets PORT_NUMBER in debug builds only.
            int portId = getResources().getIdentifier("PORT_NUMBER", "string", getPackageName());
            String port = portId == 0 ? "8080" : getString(portId);
            String text = message + "\n\nadb forward tcp:" + port + " tcp:" + port;
            runOnUiThread(() -> {
                status.setText(text);
                seeded = true;
                load();
            });
        }, "seed").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    /** Reads both tables on a background thread and shows them. */
    private void load() {
        if (!seeded) {
            return;
        }
        new Thread(() -> {
            List<String> lines = new ArrayList<>();
            try {
                lines.add("shop.db › article");
                read(shop.getReadableDatabase(), "SELECT article_id, name, price, note FROM article ORDER BY article_id", lines);
                lines.add("settings.db › flag");
                read(settings.getReadableDatabase(), "SELECT key, enabled FROM flag ORDER BY key", lines);
            } catch (RuntimeException e) {
                lines.add("Reading failed: " + e);
                Log.e(TAG, "LOAD_FAILED", e);
            }
            runOnUiThread(() -> {
                rows.clear();
                rows.addAll(lines);
            });
        }, "load").start();
    }

    /** One line per row, the values joined by " · ", NULL spelled out. */
    private static void read(SQLiteDatabase db, String sql, List<String> lines) {
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < cursor.getColumnCount(); i++) {
                    if (i > 0) {
                        line.append(" · ");
                    }
                    line.append(cursor.isNull(i) ? "NULL" : cursor.getString(i));
                }
                lines.add(line.toString());
            }
        }
    }
}
