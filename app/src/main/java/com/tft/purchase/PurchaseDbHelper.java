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
    private static final int DB_VERSION = 3;

    public static class Purchase {
        public long id;
        public String vendorName = "";
        public String location = "";
        public String orderNo = "";
        public String purchaseDate = "";
        public String signatureReturnDate = "";
        public String notes = "";
        public String imagePath = "";
        public String rawOcr = "";
        public String legacyItems = "";
        public String legacyDeliveryDate = "";
        public boolean completed;
        public long createdAt;
    }

    public static class PurchaseItem {
        public long id;
        public long purchaseId;
        public String lineNo = "";
        public String description = "";
        /** Kept only for backward-compatible reads. New/updated rows merge this into description. */
        public String specification = "";
        public String quantity = "";
        public String unit = "";
        public String unitPrice = "";
        public String subtotal = "";
        public String deliveryDate = "";
        public String note = "";
        public boolean completed;
    }

    public static class DueItem {
        public long purchaseId;
        public long itemId;
        public String vendorName = "";
        public String orderNo = "";
        public String description = "";
        public String specification = "";
        public String quantity = "";
        public String unit = "";
        public String subtotal = "";
        public String deliveryDate = "";
        public boolean purchaseCompleted;
        public boolean itemCompleted;
    }

    public PurchaseDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createPurchaseTable(db);
        createItemTable(db);
    }

    private void createPurchaseTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS purchases (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vendor_name TEXT," +
                "location TEXT," +
                "order_no TEXT DEFAULT ''," +
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

    private void createItemTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS purchase_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "purchase_id INTEGER NOT NULL," +
                "line_no TEXT," +
                "description TEXT," +
                "specification TEXT DEFAULT ''," +
                "quantity TEXT," +
                "unit TEXT," +
                "unit_price TEXT," +
                "subtotal TEXT," +
                "delivery_date TEXT," +
                "note TEXT," +
                "completed INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_items_purchase ON purchase_items(purchase_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purchase_items_delivery ON purchase_items(delivery_date)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE purchases ADD COLUMN order_no TEXT DEFAULT ''"); } catch (Exception ignored) {}
            createItemTable(db);
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE purchase_items ADD COLUMN specification TEXT DEFAULT ''"); } catch (Exception ignored) {}
        }
    }

    private ContentValues purchaseValues(Purchase p) {
        ContentValues v = new ContentValues();
        v.put("vendor_name", p.vendorName);
        v.put("location", p.location);
        v.put("order_no", p.orderNo);
        v.put("items", p.legacyItems);
        v.put("delivery_date", p.legacyDeliveryDate);
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
        return getWritableDatabase().insert("purchases", null, purchaseValues(p));
    }

    public void update(Purchase p) {
        getWritableDatabase().update("purchases", purchaseValues(p), "id=?", new String[]{String.valueOf(p.id)});
    }

    public Purchase get(long id) {
        Cursor c = getReadableDatabase().query("purchases", null, "id=?", new String[]{String.valueOf(id)}, null, null, null);
        try {
            return c.moveToFirst() ? purchaseFromCursor(c) : null;
        } finally { c.close(); }
    }

    public List<Purchase> list(String search) {
        List<Purchase> out = new ArrayList<>();
        String where = null;
        String[] args = null;
        if (search != null && !search.trim().isEmpty()) {
            where = "vendor_name LIKE ? OR location LIKE ? OR order_no LIKE ? OR items LIKE ?";
            String q = "%" + search.trim() + "%";
            args = new String[]{q, q, q, q};
        }
        Cursor c = getReadableDatabase().query("purchases", null, where, args, null, null,
                "completed ASC, purchase_date DESC, created_at DESC");
        try {
            while (c.moveToNext()) out.add(purchaseFromCursor(c));
        } finally { c.close(); }
        return out;
    }

    public List<PurchaseItem> listItems(long purchaseId) {
        List<PurchaseItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("purchase_items", null, "purchase_id=?",
                new String[]{String.valueOf(purchaseId)}, null, null, "id ASC");
        try {
            while (c.moveToNext()) out.add(itemFromCursor(c));
        } finally { c.close(); }
        return out;
    }

    public void replaceItems(long purchaseId, List<PurchaseItem> items) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("purchase_items", "purchase_id=?", new String[]{String.valueOf(purchaseId)});
            if (items != null) {
                for (PurchaseItem i : items) {
                    i.purchaseId = purchaseId;
                    i.description = displayItemName(i.description, i.specification);
                    i.specification = "";
                    db.insert("purchase_items", null, itemValues(i));
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public long insertItem(PurchaseItem i) {
        if (i != null) {
            i.description = displayItemName(i.description, i.specification);
            i.specification = "";
        }
        return getWritableDatabase().insert("purchase_items", null, itemValues(i));
    }

    private ContentValues itemValues(PurchaseItem i) {
        String mergedName = displayItemName(i == null ? "" : i.description, i == null ? "" : i.specification);
        ContentValues v = new ContentValues();
        v.put("purchase_id", i == null ? 0 : i.purchaseId);
        v.put("line_no", i == null ? "" : i.lineNo);
        v.put("description", mergedName);
        v.put("specification", "");
        v.put("quantity", i == null ? "" : i.quantity);
        v.put("unit", i == null ? "" : i.unit);
        v.put("unit_price", i == null ? "" : i.unitPrice);
        v.put("subtotal", i == null ? "" : i.subtotal);
        v.put("delivery_date", i == null ? "" : i.deliveryDate);
        v.put("note", i == null ? "" : i.note);
        v.put("completed", i != null && i.completed ? 1 : 0);
        return v;
    }

    public List<DueItem> listDueItems() {
        List<DueItem> out = new ArrayList<>();
        String sql = "SELECT p.id AS purchase_id,p.vendor_name,p.order_no,p.completed AS p_completed," +
                "i.id AS item_id,i.description,i.specification,i.quantity,i.unit,i.subtotal,i.delivery_date,i.completed AS i_completed " +
                "FROM purchase_items i JOIN purchases p ON p.id=i.purchase_id " +
                "WHERE i.delivery_date IS NOT NULL AND i.delivery_date<>'' " +
                "ORDER BY i.delivery_date ASC,p.vendor_name ASC";
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                DueItem d = new DueItem();
                d.purchaseId = c.getLong(c.getColumnIndexOrThrow("purchase_id"));
                d.itemId = c.getLong(c.getColumnIndexOrThrow("item_id"));
                d.vendorName = safe(c.getString(c.getColumnIndexOrThrow("vendor_name")));
                d.orderNo = safe(c.getString(c.getColumnIndexOrThrow("order_no")));
                String rawDescription = safe(c.getString(c.getColumnIndexOrThrow("description")));
                String rawSpecification = safe(c.getString(c.getColumnIndexOrThrow("specification")));
                d.description = displayItemName(rawDescription, rawSpecification);
                d.specification = "";
                d.quantity = safe(c.getString(c.getColumnIndexOrThrow("quantity")));
                d.unit = safe(c.getString(c.getColumnIndexOrThrow("unit")));
                d.subtotal = safe(c.getString(c.getColumnIndexOrThrow("subtotal")));
                d.deliveryDate = safe(c.getString(c.getColumnIndexOrThrow("delivery_date")));
                d.purchaseCompleted = c.getInt(c.getColumnIndexOrThrow("p_completed")) == 1;
                d.itemCompleted = c.getInt(c.getColumnIndexOrThrow("i_completed")) == 1;
                out.add(d);
            }
        } finally { c.close(); }

        for (Purchase p : list("")) {
            if (p.legacyDeliveryDate == null || p.legacyDeliveryDate.trim().isEmpty()) continue;
            if (!listItems(p.id).isEmpty()) continue;
            DueItem d = new DueItem();
            d.purchaseId = p.id;
            d.vendorName = p.vendorName;
            d.orderNo = p.orderNo;
            d.description = p.legacyItems;
            d.deliveryDate = p.legacyDeliveryDate;
            d.purchaseCompleted = p.completed;
            out.add(d);
        }
        return out;
    }

    public int itemCount(long purchaseId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM purchase_items WHERE purchase_id=?",
                new String[]{String.valueOf(purchaseId)});
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    public void delete(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("purchase_items", "purchase_id=?", new String[]{String.valueOf(id)});
            db.delete("purchases", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("purchase_items", null, null);
            db.delete("purchases", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private Purchase purchaseFromCursor(Cursor c) {
        Purchase p = new Purchase();
        p.id = c.getLong(c.getColumnIndexOrThrow("id"));
        p.vendorName = safe(c.getString(c.getColumnIndexOrThrow("vendor_name")));
        p.location = safe(c.getString(c.getColumnIndexOrThrow("location")));
        int orderIdx = c.getColumnIndex("order_no");
        p.orderNo = orderIdx >= 0 ? safe(c.getString(orderIdx)) : "";
        p.legacyItems = safe(c.getString(c.getColumnIndexOrThrow("items")));
        p.legacyDeliveryDate = safe(c.getString(c.getColumnIndexOrThrow("delivery_date")));
        p.purchaseDate = safe(c.getString(c.getColumnIndexOrThrow("purchase_date")));
        p.signatureReturnDate = safe(c.getString(c.getColumnIndexOrThrow("signature_return_date")));
        p.notes = safe(c.getString(c.getColumnIndexOrThrow("notes")));
        p.imagePath = safe(c.getString(c.getColumnIndexOrThrow("image_path")));
        p.rawOcr = safe(c.getString(c.getColumnIndexOrThrow("raw_ocr")));
        p.completed = c.getInt(c.getColumnIndexOrThrow("completed")) == 1;
        p.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return p;
    }

    private PurchaseItem itemFromCursor(Cursor c) {
        PurchaseItem i = new PurchaseItem();
        i.id = c.getLong(c.getColumnIndexOrThrow("id"));
        i.purchaseId = c.getLong(c.getColumnIndexOrThrow("purchase_id"));
        i.lineNo = safe(c.getString(c.getColumnIndexOrThrow("line_no")));
        String rawDescription = safe(c.getString(c.getColumnIndexOrThrow("description")));
        int specIdx = c.getColumnIndex("specification");
        String rawSpecification = specIdx >= 0 ? safe(c.getString(specIdx)) : "";
        i.description = displayItemName(rawDescription, rawSpecification);
        i.specification = "";
        i.quantity = safe(c.getString(c.getColumnIndexOrThrow("quantity")));
        i.unit = safe(c.getString(c.getColumnIndexOrThrow("unit")));
        i.unitPrice = safe(c.getString(c.getColumnIndexOrThrow("unit_price")));
        i.subtotal = safe(c.getString(c.getColumnIndexOrThrow("subtotal")));
        i.deliveryDate = safe(c.getString(c.getColumnIndexOrThrow("delivery_date")));
        i.note = safe(c.getString(c.getColumnIndexOrThrow("note")));
        i.completed = c.getInt(c.getColumnIndexOrThrow("completed")) == 1;
        return i;
    }

    /**
     * Tracking UI uses one complete item name. Old rows may still have description/specification split;
     * this merges them without changing the DB schema so existing installations remain compatible.
     * Example: description=鐵框, specification=42寸 -> 42寸鐵框.
     */
    public static String displayItemName(String description, String specification) {
        String desc = compactItemText(description);
        String spec = compactItemText(specification);
        if (desc.isEmpty()) return spec;
        if (spec.isEmpty()) return desc;

        String descKey = itemKey(desc);
        String specKey = itemKey(spec);
        if (!specKey.isEmpty() && descKey.contains(specKey)) return desc;
        if (!descKey.isEmpty() && specKey.contains(descKey)) return spec;

        return spec + desc;
    }

    private static String compactItemText(String s) {
        if (s == null) return "";
        String x = s.replace('\r', ' ').replace('\n', ' ').trim();
        x = x.replaceFirst("^(規格|尺寸)\\s*[:：]\\s*", "");
        return x.replaceAll("\\s+", " ").trim();
    }

    private static String itemKey(String s) {
        return s == null ? "" : s.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s｜|,，、;；:：/\\-]+", "");
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
