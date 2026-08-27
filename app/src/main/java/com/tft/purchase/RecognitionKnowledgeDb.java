package com.tft.purchase;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local, user-editable recognition knowledge base. */
public final class RecognitionKnowledgeDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "tft_recognition_knowledge.db";
    private static final int DB_VERSION = 1;

    public static final class Vendor {
        public long id;
        public String name = "";
        public String address = "";
        public String keywords = "";
        public String products = "";
    }

    public RecognitionKnowledgeDb(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE own_aliases (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE vendors (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,address TEXT DEFAULT '',keywords TEXT DEFAULT '',products TEXT DEFAULT '',updated_at INTEGER DEFAULT 0)");
        insertAlias(db, "全益畜牧器具公司");
        insertAlias(db, "全益台灣電扇");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}

    private static void insertAlias(SQLiteDatabase db, String name) {
        ContentValues v = new ContentValues(); v.put("name", name);
        db.insertWithOnConflict("own_aliases", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public List<String> listOwnAliases() {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("own_aliases", new String[]{"name"}, null, null, null, null, "id ASC");
        try { while (c.moveToNext()) out.add(safe(c.getString(0))); } finally { c.close(); }
        return out;
    }

    public void saveOwnAliases(List<String> aliases) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("own_aliases", null, null);
            if (aliases != null) {
                Set<String> seen = new LinkedHashSet<>();
                for (String a : aliases) {
                    String x = safe(a).trim();
                    if (x.length() >= 2 && seen.add(x)) insertAlias(db, x);
                }
            }
            insertAlias(db, "全益畜牧器具公司");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<Vendor> listVendors() {
        List<Vendor> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("vendors", null, null, null, null, null, "name COLLATE NOCASE ASC");
        try {
            while (c.moveToNext()) {
                Vendor v = new Vendor();
                v.id = c.getLong(c.getColumnIndexOrThrow("id"));
                v.name = safe(c.getString(c.getColumnIndexOrThrow("name")));
                v.address = safe(c.getString(c.getColumnIndexOrThrow("address")));
                v.keywords = safe(c.getString(c.getColumnIndexOrThrow("keywords")));
                v.products = safe(c.getString(c.getColumnIndexOrThrow("products")));
                out.add(v);
            }
        } finally { c.close(); }
        return out;
    }

    public long upsertVendor(Vendor vendor) {
        if (vendor == null || safe(vendor.name).trim().isEmpty()) return -1;
        ContentValues cv = new ContentValues();
        cv.put("name", vendor.name.trim()); cv.put("address", safe(vendor.address).trim());
        cv.put("keywords", safe(vendor.keywords).trim()); cv.put("products", safe(vendor.products).trim());
        cv.put("updated_at", System.currentTimeMillis());
        SQLiteDatabase db = getWritableDatabase();
        if (vendor.id > 0) {
            db.update("vendors", cv, "id=?", new String[]{String.valueOf(vendor.id)});
            return vendor.id;
        }
        long id = db.insertWithOnConflict("vendors", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
        if (id > 0) return id;
        Cursor c = db.query("vendors", new String[]{"id"}, "name=?", new String[]{vendor.name.trim()}, null, null, null);
        try {
            if (c.moveToFirst()) {
                id = c.getLong(0);
                db.update("vendors", cv, "id=?", new String[]{String.valueOf(id)});
                return id;
            }
        } finally { c.close(); }
        return -1;
    }

    public void deleteVendor(long id) {
        if (id > 0) getWritableDatabase().delete("vendors", "id=?", new String[]{String.valueOf(id)});
    }

    public boolean isOwnCompany(String text) {
        String n = norm(text); if (n.isEmpty()) return false;
        for (String alias : listOwnAliases()) {
            String a = norm(alias);
            if (a.length() >= 4 && (n.equals(a) || n.contains(a) || (a.contains(n) && n.length() >= 4))) return true;
        }
        return false;
    }

    /** Learn only from a user-confirmed/manual edit, never from raw AI output. */
    public void learnFromConfirmedPurchase(PurchaseDbHelper.Purchase p, List<PurchaseDbHelper.PurchaseItem> items) {
        if (p == null || safe(p.vendorName).trim().isEmpty() || isOwnCompany(p.vendorName)) return;
        Vendor existing = findByName(p.vendorName);
        Vendor v = existing == null ? new Vendor() : existing;
        v.name = p.vendorName.trim();
        if (!safe(p.location).trim().isEmpty()) v.address = p.location.trim();
        LinkedHashSet<String> products = new LinkedHashSet<>(splitLines(v.products));
        if (items != null) {
            for (PurchaseDbHelper.PurchaseItem item : items) {
                String d = safe(item.description).trim();
                if (!d.isEmpty() && !d.equals("需要人工確認")) products.add(d);
            }
        }
        v.products = joinLines(products, 40);
        upsertVendor(v);
    }

    private Vendor findByName(String name) {
        String q = safe(name).trim(); if (q.isEmpty()) return null;
        Cursor c = getReadableDatabase().query("vendors", null, "name=?", new String[]{q}, null, null, null);
        try {
            if (!c.moveToFirst()) return null;
            Vendor v = new Vendor();
            v.id = c.getLong(c.getColumnIndexOrThrow("id"));
            v.name = safe(c.getString(c.getColumnIndexOrThrow("name")));
            v.address = safe(c.getString(c.getColumnIndexOrThrow("address")));
            v.keywords = safe(c.getString(c.getColumnIndexOrThrow("keywords")));
            v.products = safe(c.getString(c.getColumnIndexOrThrow("products")));
            return v;
        } finally { c.close(); }
    }

    public String buildPromptEvidence() {
        StringBuilder sb = new StringBuilder();
        sb.append("[LOCAL_RECOGNITION_KNOWLEDGE]\n");
        sb.append("OWN_COMPANY_ALIASES (永遠不能當供應廠商):\n");
        for (String a : listOwnAliases()) sb.append("- ").append(a).append('\n');
        sb.append("KNOWN_VENDORS (只有 OCR/圖片出現名稱或關鍵字時才能採用):\n");
        int count = 0;
        for (Vendor v : listVendors()) {
            if (++count > 40) break;
            sb.append("- vendor=").append(v.name);
            if (!v.address.isEmpty()) sb.append(" | address=").append(v.address);
            if (!v.keywords.isEmpty()) sb.append(" | keywords=").append(v.keywords.replace('\n', ','));
            sb.append('\n');
            if (!v.products.isEmpty()) {
                int pCount = 0;
                for (String p : splitLines(v.products)) {
                    if (++pCount > 12) break;
                    sb.append("  product_hint=").append(p).append('\n');
                }
            }
        }
        sb.append("RULE: 已知資料只做比對/正規化；沒有圖片或 OCR 證據時禁止自行填入。\n");
        sb.append("[/LOCAL_RECOGNITION_KNOWLEDGE]\n");
        return sb.toString();
    }

    /** Reject own-company vendor and canonicalize a known vendor only when observed OCR contains it. */
    public void applyKnownRules(PurchaseOcrParser.ParsedPurchase parsed, String evidence) {
        if (parsed == null) return;
        if (isOwnCompany(parsed.vendor)) {
            parsed.rawText += "\n[KNOWLEDGE_RULE] vendor rejected because it matches own-company alias: " + parsed.vendor;
            parsed.vendor = "";
        }
        String observed = safe(evidence);
        int cut = observed.indexOf("[LOCAL_RECOGNITION_KNOWLEDGE]");
        if (cut >= 0) observed = observed.substring(0, cut);
        String e = norm(observed);
        if (e.isEmpty()) return;
        Vendor matched = null; int matches = 0;
        for (Vendor v : listVendors()) {
            boolean hit = !norm(v.name).isEmpty() && e.contains(norm(v.name));
            if (!hit) {
                for (String k : splitTokens(v.keywords)) {
                    String nk = norm(k);
                    if (nk.length() >= 2 && e.contains(nk)) { hit = true; break; }
                }
            }
            if (hit) { matched = v; matches++; }
        }
        if (matches == 1 && matched != null) {
            String pv = norm(parsed.vendor);
            if (safe(parsed.vendor).trim().isEmpty() || isOwnCompany(parsed.vendor) || (!pv.isEmpty() && !e.contains(pv))) {
                parsed.vendor = matched.name;
                parsed.rawText += "\n[KNOWLEDGE_MATCH] canonical vendor=" + matched.name;
            }
            if (safe(parsed.location).trim().isEmpty() && !matched.address.isEmpty()) {
                parsed.location = matched.address;
                parsed.rawText += "\n[KNOWLEDGE_MATCH] vendor address from local confirmed DB";
            }
        }
    }

    private static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        for (String x : safe(s).split("[\\r\\n]+")) { x=x.trim(); if(!x.isEmpty()) out.add(x); }
        return out;
    }

    private static List<String> splitTokens(String s) {
        List<String> out = new ArrayList<>();
        for (String x : safe(s).split("[,，;；\\r\\n]+")) { x=x.trim(); if(!x.isEmpty()) out.add(x); }
        return out;
    }

    private static String joinLines(Set<String> values, int max) {
        StringBuilder sb = new StringBuilder(); int n=0;
        for (String s : values) { if(++n>max) break; if(sb.length()>0) sb.append('\n'); sb.append(s); }
        return sb.toString();
    }

    private static String norm(String s) {
        return safe(s).toUpperCase(Locale.TAIWAN).replaceAll("[\\s　:：,，.。()（）/\\-＿_]+", "").trim();
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
