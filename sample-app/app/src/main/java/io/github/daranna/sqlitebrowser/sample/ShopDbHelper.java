package io.github.daranna.sqlitebrowser.sample;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

/**
 * shop.db: a small table and a huge one.
 *
 * <p>{@code article} has a column {@code article_id} that contains the table name. debug-db's
 * {@code query} endpoint replaces every occurrence of the table name in the SQL with
 * {@code [article]}, so {@code ORDER BY article_id} turns into {@code ORDER BY [article]_id}.
 *
 * <p>{@code big_table} holds about 150 MB of text, so debug-db's {@code getAllDataFromTheTable}
 * runs the app out of memory.
 */
final class ShopDbHelper extends SQLiteOpenHelper {

    static final String NAME = "shop.db";
    static final int ARTICLE_ROWS = 50;
    static final int BIG_TABLE_ROWS = 150_000;
    static final int PAYLOAD_LENGTH = 1000;

    ShopDbHelper(Context context) {
        super(context, NAME, null, 1);
        // Lets debug-db read the old state while the seeding transaction runs, instead of
        // failing with "database is locked".
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE article (article_id INTEGER PRIMARY KEY, name TEXT, price REAL, note TEXT NULL)");
        db.execSQL("CREATE TABLE big_table (id INTEGER PRIMARY KEY, payload TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /** Fills both tables in one transaction, unless big_table already has its rows. */
    boolean seedIfEmpty() {
        SQLiteDatabase db = getWritableDatabase();
        if (DatabaseUtils.queryNumEntries(db, "big_table") == BIG_TABLE_ROWS) {
            return false;
        }
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM article");
            db.execSQL("DELETE FROM big_table");

            SQLiteStatement article = db.compileStatement(
                    "INSERT INTO article (article_id, name, price, note) VALUES (?, ?, ?, ?)");
            for (int i = 1; i <= ARTICLE_ROWS; i++) {
                article.bindLong(1, i);
                article.bindString(2, "Article " + i);
                // Prices go down as ids go up, so sorting by price differs from sorting by id.
                article.bindDouble(3, Math.round((1000.0 / i) * 100) / 100.0);
                if (i % 3 == 0) {
                    article.bindNull(4);
                } else {
                    article.bindString(4, "note " + i);
                }
                article.executeInsert();
            }

            String filler = filler();
            SQLiteStatement big = db.compileStatement("INSERT INTO big_table (id, payload) VALUES (?, ?)");
            for (int i = 1; i <= BIG_TABLE_ROWS; i++) {
                String prefix = "row " + i + " ";
                big.bindLong(1, i);
                big.bindString(2, prefix + filler.substring(prefix.length()));
                big.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    private static String filler() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(PAYLOAD_LENGTH);
        while (sb.length() < PAYLOAD_LENGTH) {
            sb.append(alphabet);
        }
        sb.setLength(PAYLOAD_LENGTH);
        return sb.toString();
    }
}
