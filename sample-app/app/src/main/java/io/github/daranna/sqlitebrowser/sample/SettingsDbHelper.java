package io.github.daranna.sqlitebrowser.sample;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** settings.db: a second, small database, so the plugin shows more than one. */
final class SettingsDbHelper extends SQLiteOpenHelper {

    static final String NAME = "settings.db";

    SettingsDbHelper(Context context) {
        super(context, NAME, null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE flag (key TEXT PRIMARY KEY, enabled INTEGER)");
        db.execSQL("INSERT INTO flag (key, enabled) VALUES"
                + " ('dark_mode', 1), ('beta_features', 0), ('analytics', 0), ('offline_cache', 1)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }
}
