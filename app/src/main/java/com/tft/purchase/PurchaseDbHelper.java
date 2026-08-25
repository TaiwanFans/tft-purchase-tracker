package com.tft.purchase;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public class PurchaseDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "tft_purchase.db";
    private static final int DB_VERSION = 1;

    public static class Purchase {
        public long id;
        public String vendorName = "";
        public String location = "";
        public String items = "";
        public String deliveryDate = "";
        public String purchaseDate = "";
        public String signatureReturnDate = "";
        public String notes = "";
        public String imagePath = "";
        public String rawOcr = "";
        public boolean completed;
        public long createdAt;
    }

    public PurchaseDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE purchases (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vendor_name TEXT," +
                "location TEXT," +
                "items TEXT," +
                "delivery_date TEXT," +
                "purchase_date TEXT," +
                "signature_return_date TEXT," +
                "notes TEXT," +
                "image_path TEXT," +
                "raw_ocr TEXT," +
                "completed INTEGER DEFAULT 0," +
                "created_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS purchases");
        onCreate(db);
    }

    private ContentValues values(Purchase p) {
        ContentValues v = new ContentValues();
        v.put("vendor_name", p.vendorName);
        v.put("location", p.location);
        v.put("items", p.items);
        v.put("delivery_date", p.deliveryDate);
        v.put("purchase_date", p.purchaseDate);
        v.put("signature_return_date", p.signatureReturnDate);
        v.put("notes", p.notes);
        v.put("image_path", p.imagePath);
        v.put("raw_ocr", p.rawOcr);
        v.put("completed", p.completed ? 1 : 0);
        v.put("created_at", p.createdAt == 0 ? System.currentTimeMillis() : p.createdAt);
        return v;
    }

    public long insert(Purchase p) {
        return getWritableDatabase().insert("purchases", null, values(p));
    }

    public void update(Purchase p) {
        getWritableDatabase().update("purchases", values(p), "id=?", new String[]{String.valueOf(p.id)});
    }

    public Purchase get(long id) {
        Cursor c = getReadableDatabase().query("purchases", null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        try {
            if (c.moveToFirst()) return fromCursor(c);
            return null;
        } finally {
            c.close();
        }
    }

    public List<Purchase> list(String search) {
        List<Purchase> out = new ArrayList<>();
        String where = null;
        String[] args = null;
        if (search != null && !search.trim().isEmpty()) {
            where = "vendor_name LIKE ? OR location LIKE ? OR items LIKE ?";
            String q = "%" + search.trim() + "%";
            args = new String[]{q, q, q};
        }
        Cursor c = getReadableDatabase().query("purchases", null, where, args, null, null, "completed ASC, delivery_date ASC, created_at DESC");
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    public void delete(long id) {
        getWritableDatabase().delete("purchases", "id=?", new String[]{String.valueOf(id)});
    }

    private Purchase fromCursor(Cursor c) {
        Purchase p = new Purchase();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.vendorName = c.getString(c.getColumnIndexOrThrow("vendor_name"));
        p.location = c.getString(c.getColumnIndexOrThrow("location"));
        p.items = c.getString(c.getColumnIndexOrThrow("items"));
        p.deliveryDate = c.getString(c.getColumnIndexOrThrow("delivery_date"));
        p.purchaseDate = c.getString(c.getColumnIndexOrThrow("purchase_date"));
        p.signatureReturnDate = c.getString(c.getColumnIndexOrThrow("signature_return_date"));
        p.notes = c.getString(c.getColumnIndexOrThrow("notes"));
        p.imagePath = c.getString(c.getColumnIndexOrThrow("image_path"));
        p.rawOcr = c.getString(c.getColumnIndexOrThrow("raw_ocr"));
        p.completed = c.getInt(c.getColumnIndexOrThrow("completed")) == 1;
        p.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return p;
    }
}
