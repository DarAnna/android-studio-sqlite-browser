package io.github.daranna.sqlitebrowser.sample;

import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;

/**
 * shop.db: a small table, a huge one, and one that refers to the small one.
 *
 * <p>{@code article} has a column {@code article_id} that contains the table name. debug-db's
 * {@code query} endpoint replaces every occurrence of the table name in the SQL with
 * {@code [article]}, so {@code ORDER BY article_id} turns into {@code ORDER BY [article]_id}.
 *
 * <p>{@code big_table} holds about 150 MB of text, so debug-db's {@code getAllDataFromTheTable}
 * runs the app out of memory. It is also wide, with groups of columns that share a word, to try
 * Find column on.
 *
 * <p>{@code purchase.article_id} is a foreign key to {@code article}, declared without a column, so
 * it refers to article's primary key. Every tenth purchase has NULL there.
 */
final class ShopDbHelper extends SQLiteOpenHelper {

    static final String NAME = "shop.db";
    static final int ARTICLE_ROWS = 50;
    static final int BIG_TABLE_ROWS = 150_000;
    static final int PAYLOAD_LENGTH = 1000;
    static final int PURCHASE_ROWS = 30;

    private static final String[] CITIES = {"Berlin", "Lisbon", "Oslo", "Porto", "Vienna"};
    private static final String[] COUNTRIES = {"DE", "PT", "NO", "PT", "AT"};
    private static final String[] STATUSES = {"new", "paid", "shipped", "delivered", "returned"};

    ShopDbHelper(Context context) {
        super(context, NAME, null, 3);
        // Lets debug-db read the old state while the seeding transaction runs, instead of
        // failing with "database is locked".
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE article (article_id INTEGER PRIMARY KEY, name TEXT, price REAL, note TEXT NULL)");
        createBigTable(db);
        createPurchase(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createPurchase(db);
        }
        if (oldVersion < 3) {
            // Empty again, so seedIfEmpty fills the new columns too.
            db.execSQL("DROP TABLE big_table");
            createBigTable(db);
        }
    }

    private static void createBigTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE big_table (id INTEGER PRIMARY KEY,"
                + " customer_name TEXT NOT NULL, customer_email TEXT, customer_phone TEXT,"
                + " shipping_street TEXT, shipping_city TEXT, shipping_zip TEXT, shipping_country TEXT,"
                + " billing_street TEXT, billing_city TEXT, billing_zip TEXT, billing_country TEXT,"
                + " order_status TEXT NOT NULL DEFAULT 'new', order_total REAL, order_currency TEXT DEFAULT 'EUR',"
                + " item_count INTEGER, discount_code TEXT, is_gift INTEGER NOT NULL DEFAULT 0,"
                + " created_at TEXT, updated_at TEXT, payload TEXT)");
    }

    private static void createPurchase(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE purchase (purchase_id INTEGER PRIMARY KEY,"
                + " article_id INTEGER REFERENCES article, quantity INTEGER)");
    }

    /** Fills the tables in one transaction, unless big_table and purchase already have their rows. */
    boolean seedIfEmpty() {
        SQLiteDatabase db = getWritableDatabase();
        if (DatabaseUtils.queryNumEntries(db, "big_table") == BIG_TABLE_ROWS
                && DatabaseUtils.queryNumEntries(db, "purchase") == PURCHASE_ROWS) {
            return false;
        }
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM purchase");
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

            SQLiteStatement purchase = db.compileStatement(
                    "INSERT INTO purchase (purchase_id, article_id, quantity) VALUES (?, ?, ?)");
            for (int i = 1; i <= PURCHASE_ROWS; i++) {
                purchase.bindLong(1, i);
                if (i % 10 == 0) {
                    purchase.bindNull(2);
                } else {
                    purchase.bindLong(2, (i * 7) % ARTICLE_ROWS + 1);
                }
                purchase.bindLong(3, i % 4 + 1);
                purchase.executeInsert();
            }

            String filler = filler();
            SQLiteStatement big = db.compileStatement("INSERT INTO big_table (id,"
                    + " customer_name, customer_email, customer_phone,"
                    + " shipping_street, shipping_city, shipping_zip, shipping_country,"
                    + " billing_street, billing_city, billing_zip, billing_country,"
                    + " order_status, order_total, order_currency,"
                    + " item_count, discount_code, is_gift,"
                    + " created_at, updated_at, payload)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
            for (int i = 1; i <= BIG_TABLE_ROWS; i++) {
                int shipping = i % CITIES.length;
                // Every seventh bills to another address than it ships to.
                int billing = i % 7 == 0 ? (shipping + 1) % CITIES.length : shipping;
                String prefix = "row " + i + " ";
                big.bindLong(1, i);
                big.bindString(2, "Customer " + i);
                big.bindString(3, "customer" + i + "@example.com");
                big.bindString(4, "+1555" + (1000000 + i));
                big.bindString(5, (i % 90 + 1) + " Sample Street");
                big.bindString(6, CITIES[shipping]);
                big.bindString(7, String.valueOf(10000 + shipping * 111));
                big.bindString(8, COUNTRIES[shipping]);
                big.bindString(9, (i % 90 + 1) + " Sample Street");
                big.bindString(10, CITIES[billing]);
                big.bindString(11, String.valueOf(10000 + billing * 111));
                big.bindString(12, COUNTRIES[billing]);
                big.bindString(13, STATUSES[i % STATUSES.length]);
                big.bindDouble(14, (i % 20000) / 100.0);
                big.bindString(15, "EUR");
                big.bindLong(16, i % 9 + 1);
                if (i % 5 == 0) {
                    big.bindString(17, "SAVE" + (i % 4 + 1) * 5);
                } else {
                    big.bindNull(17);
                }
                big.bindLong(18, i % 11 == 0 ? 1 : 0);
                big.bindString(19, date(i));
                big.bindString(20, date(i + 3));
                big.bindString(21, prefix + filler.substring(prefix.length()));
                big.executeInsert();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    private static String date(int day) {
        int month = day / 28 % 12 + 1;
        int dayOfMonth = day % 28 + 1;
        return "2026-" + (month < 10 ? "0" : "") + month + "-" + (dayOfMonth < 10 ? "0" : "") + dayOfMonth;
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
