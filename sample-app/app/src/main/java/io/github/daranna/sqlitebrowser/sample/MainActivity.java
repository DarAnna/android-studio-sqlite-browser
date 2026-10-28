package io.github.daranna.sqlitebrowser.sample;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

/** Seeds the sample databases on a background thread and shows how it went. */
public class MainActivity extends Activity {

    private static final String TAG = "SqliteBrowserSample";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView status = new TextView(this);
        status.setPadding(48, 48, 48, 48);
        status.setText("Seeding databases…");
        setContentView(status);

        new Thread(() -> {
            String message;
            try {
                long start = SystemClock.elapsedRealtime();
                new SettingsDbHelper(getApplicationContext()).getWritableDatabase();
                boolean seeded = new ShopDbHelper(getApplicationContext()).seedIfEmpty();
                long ms = SystemClock.elapsedRealtime() - start;
                message = seeded
                        ? "Seeded " + ShopDbHelper.BIG_TABLE_ROWS + " rows in " + ms + " ms"
                        : "Already seeded";
                Log.i(TAG, "SEED_DONE " + message);
            } catch (RuntimeException e) {
                message = "Seeding failed: " + e;
                Log.e(TAG, "SEED_FAILED", e);
            }
            String text = message + "\n\nadb forward tcp:8080 tcp:8080";
            runOnUiThread(() -> status.setText(text));
        }, "seed").start();
    }
}
