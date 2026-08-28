package com.tft.purchase;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Local, user-editable recognition knowledge base. Historical data is evidence/hints only. */
public final class RecognitionKnowledgeDb extends SQLiteOpenHelper {
    private static final String DB_NAME = "tft_recognition_knowledge.db";
    private static final int DB_VERSION = 2;

    private static final String[] REQUIRED_OWN_ALIASES = {
            "全益畜牧器具公司", "全益", "台灣電扇科技有限公司", "台灣電扇", "全益台灣電扇"
    };

    public static final class Vendor {
        public long id;
        public String name = "";
        public String address = "";
        public String keywords = "";
        /** Backward-compatible editable line list used by the current knowledge UI. */
        public String products = "";
    }

    public static final class VendorProduct {
        public long id;
        public long vendorId;
        public String productName = "";
        public String specification = "";
        public String aliases = "";
        public int timesSeen;
    }

    public RecognitionKnowledgeDb(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE own_aliases (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE vendors (id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,address TEXT DEFAULT '',keywords TEXT DEFAULT '',products TEXT DEFAULT '',updated_at INTEGER DEFAULT 0)");
        createVendorProducts(db);
        insertRequiredAliases(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createVendorProducts(db);
            migrateLegacyProducts(db);
            insertRequiredAliases(db);
        }
    }

    private static void createVendorProducts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS vendor_products (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "vendor_id INTEGER NOT NULL," +
                "product_name TEXT NOT NULL," +
                "specification TEXT DEFAULT ''," +
                "aliases TEXT DEFAULT ''," +
                "times_seen INTEGER DEFAULT 1," +
                "updated_at INTEGER DEFAULT 0," +
                "UNIQUE(vendor_id,product_name,specification))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_vendor_products_vendor ON vendor_products(vendor_id)");
    }

    private static void insertAlias(SQLiteDatabase db, String name) {
        ContentValues v = new ContentValues(); v.put("name", name);
        db.insertWithOnConflict("own_aliases", null, v, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void insertRequiredAliases(SQLiteDatabase db) {
        for (String alias : REQUIRED_OWN_ALIASES) insertAlias(db, alias);
    }

    private static void migrateLegacyProducts(SQLiteDatabase db) {
        Cursor c = db.query("vendors", new String[]{"id","products"}, null, null, null, null, null);
        try {
            while (c.moveToNext()) {
                long vendorId = c.getLong(0);
                String products = safe(c.getString(1));
                for (String line : splitLines(products)) insertProduct(db, vendorId, line, "", "", 1, false);
            }
        } finally { c.close(); }
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
            insertRequiredAliases(db);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<Vendor> listVendors() {
        List<Vendor> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("vendors", null, null, null, null, null, "name COLLATE NOCASE ASC");
        try {
            while (c.moveToNext()) {
                Vendor v = vendorFromCursor(c);
                List<VendorProduct> normalized = listVendorProducts(v.id);
                if (!normalized.isEmpty()) {
                    StringBuilder p = new StringBuilder();
                    for (VendorProduct vp : normalized) {
                        if (p.length() > 0) p.append('\n');
                        p.append(vp.productName);
                        if (!vp.specification.isEmpty()) p.append("｜規格：").append(vp.specification);
                    }
                    v.products = p.toString();
                }
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
        long id = vendor.id;
        if (id > 0) {
            db.update("vendors", cv, "id=?", new String[]{String.valueOf(id)});
        } else {
            id = db.insertWithOnConflict("vendors", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
            if (id <= 0) {
                Cursor c = db.query("vendors", new String[]{"id"}, "name=?", new String[]{vendor.name.trim()}, null, null, null);
                try { if (c.moveToFirst()) id = c.getLong(0); } finally { c.close(); }
                if (id > 0) db.update("vendors", cv, "id=?", new String[]{String.valueOf(id)});
            }
        }
        if (id > 0) syncEditableProductLines(db, id, safe(vendor.products));
        return id;
    }

    private static void syncEditableProductLines(SQLiteDatabase db, long vendorId, String products) {
        List<String> requested = splitLines(products);
        Set<String> normalized = new LinkedHashSet<>();
        for (String line : requested) normalized.add(norm(line));

        Cursor c = db.query("vendor_products", new String[]{"id","product_name","specification"},
                "vendor_id=?", new String[]{String.valueOf(vendorId)}, null, null, null);
        List<Long> delete = new ArrayList<>();
        try {
            while (c.moveToNext()) {
                String display = safe(c.getString(1));
                String spec = safe(c.getString(2));
                if (!spec.isEmpty()) display += "｜規格：" + spec;
                if (!normalized.contains(norm(display))) delete.add(c.getLong(0));
            }
        } finally { c.close(); }
        for (Long id : delete) db.delete("vendor_products", "id=?", new String[]{String.valueOf(id)});
        for (String line : requested) {
            String[] parsed = splitProductLine(line);
            insertProduct(db, vendorId, parsed[0], parsed[1], "", 1, false);
        }
    }

    public void deleteVendor(long id) {
        if (id <= 0) return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("vendor_products", "vendor_id=?", new String[]{String.valueOf(id)});
            db.delete("vendors", "id=?", new String[]{String.valueOf(id)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public List<VendorProduct> listVendorProducts(long vendorId) {
        List<VendorProduct> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query("vendor_products", null, "vendor_id=?",
                new String[]{String.valueOf(vendorId)}, null, null, "times_seen DESC,product_name ASC");
        try {
            while (c.moveToNext()) {
                VendorProduct p = new VendorProduct();
                p.id = c.getLong(c.getColumnIndexOrThrow("id"));
                p.vendorId = vendorId;
                p.productName = safe(c.getString(c.getColumnIndexOrThrow("product_name")));
                p.specification = safe(c.getString(c.getColumnIndexOrThrow("specification")));
                p.aliases = safe(c.getString(c.getColumnIndexOrThrow("aliases")));
                p.timesSeen = c.getInt(c.getColumnIndexOrThrow("times_seen"));
                out.add(p);
            }
        } finally { c.close(); }
        return out;
    }

    private static void insertProduct(SQLiteDatabase db, long vendorId, String productName,
                                      String specification, String aliases, int timesSeen, boolean increment) {
        String name = safe(productName).trim();
        String spec = safe(specification).trim();
        if (vendorId <= 0 || name.isEmpty()) return;
        Cursor c = db.query("vendor_products", new String[]{"id","times_seen","aliases"},
                "vendor_id=? AND product_name=? AND specification=?",
                new String[]{String.valueOf(vendorId), name, spec}, null, null, null);
        try {
            if (c.moveToFirst()) {
                ContentValues cv = new ContentValues();
                int old = c.getInt(1);
                cv.put("times_seen", increment ? Math.max(1, old + 1) : Math.max(old, timesSeen));
                String mergedAliases = safe(aliases).trim().isEmpty() ? safe(c.getString(2)) : safe(aliases).trim();
                cv.put("aliases", mergedAliases);
                cv.put("updated_at", System.currentTimeMillis());
                db.update("vendor_products", cv, "id=?", new String[]{String.valueOf(c.getLong(0))});
                return;
            }
        } finally { c.close(); }
        ContentValues cv = new ContentValues();
        cv.put("vendor_id", vendorId); cv.put("product_name", name); cv.put("specification", spec);
        cv.put("aliases", safe(aliases).trim()); cv.put("times_seen", Math.max(1, timesSeen));
        cv.put("updated_at", System.currentTimeMillis());
        db.insertWithOnConflict("vendor_products", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public boolean isOwnCompany(String text) {
        String n = norm(text); if (n.isEmpty()) return false;
        for (String alias : listOwnAliases()) {
            String a = norm(alias);
            if (n.equals(a)) return true;
            if (a.length() >= 6 && n.contains(a)) return true;
        }
        return AiResultValidator.isSelfCompany(text);
    }

    /** Learn only from a user-confirmed/manual edit, never from raw AI output. */
    public void learnFromConfirmedPurchase(PurchaseDbHelper.Purchase p, List<PurchaseDbHelper.PurchaseItem> items) {
        if (p == null || safe(p.vendorName).trim().isEmpty() || isOwnCompany(p.vendorName)) return;
        Vendor existing = findByName(p.vendorName);
        Vendor v = existing == null ? new Vendor() : existing;
        v.name = p.vendorName.trim();
        if (!safe(p.location).trim().isEmpty()) v.address = p.location.trim();
        long vendorId = upsertVendor(v);
        if (vendorId <= 0) return;

        LinkedHashSet<String> products = new LinkedHashSet<>(splitLines(v.products));
        if (items != null) {
            SQLiteDatabase db = getWritableDatabase();
            for (PurchaseDbHelper.PurchaseItem item : items) {
                String d = safe(item.description).trim();
                String spec = safe(item.specification).trim();
                if (d.isEmpty() || d.equals("需要人工確認")) continue;
                String display = d + (spec.isEmpty() ? "" : "｜規格：" + spec);
                products.add(display);
                insertProduct(db, vendorId, d, spec, "", 1, true);
            }
        }
        v.id = vendorId;
        v.products = joinLines(products, 60);
        ContentValues cv = new ContentValues(); cv.put("products", v.products); cv.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("vendors", cv, "id=?", new String[]{String.valueOf(vendorId)});
    }

    private Vendor findByName(String name) {
        String q = safe(name).trim(); if (q.isEmpty()) return null;
        Cursor c = getReadableDatabase().query("vendors", null, "name=?", new String[]{q}, null, null, null);
        try { return c.moveToFirst() ? vendorFromCursor(c) : null; }
        finally { c.close(); }
    }

    private static Vendor vendorFromCursor(Cursor c) {
        Vendor v = new Vendor();
        v.id = c.getLong(c.getColumnIndexOrThrow("id"));
        v.name = safe(c.getString(c.getColumnIndexOrThrow("name")));
        v.address = safe(c.getString(c.getColumnIndexOrThrow("address")));
        v.keywords = safe(c.getString(c.getColumnIndexOrThrow("keywords")));
        v.products = safe(c.getString(c.getColumnIndexOrThrow("products")));
        return v;
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
            int pCount = 0;
            List<VendorProduct> normalized = listVendorProducts(v.id);
            if (!normalized.isEmpty()) {
                for (VendorProduct p : normalized) {
                    if (++pCount > 12) break;
                    sb.append("  product_hint=").append(p.productName);
                    if (!p.specification.isEmpty()) sb.append(" | spec_hint=").append(p.specification);
                    sb.append(" | confirmed_times=").append(Math.max(1, p.timesSeen)).append('\n');
                }
            } else {
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
            parsed.rawText = safe(parsed.rawText) + "\n[KNOWLEDGE_RULE] vendor rejected because it matches own-company alias: " + safe(parsed.vendor);
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
                parsed.rawText = safe(parsed.rawText) + "\n[KNOWLEDGE_MATCH] canonical vendor=" + matched.name;
            }
            if (safe(parsed.location).trim().isEmpty() && !matched.address.isEmpty()) {
                parsed.location = matched.address;
                parsed.rawText = safe(parsed.rawText) + "\n[KNOWLEDGE_MATCH] vendor address from local confirmed DB";
            }
        }
    }

    public JSONObject exportJson() {
        JSONObject root = new JSONObject();
        try {
            JSONArray own = new JSONArray();
            for (String s : listOwnAliases()) own.put(s);
            root.put("own_aliases", own);
            JSONArray vendors = new JSONArray();
            for (Vendor v : listVendors()) {
                JSONObject j = new JSONObject();
                j.put("name", v.name); j.put("address", v.address); j.put("keywords", v.keywords);
                JSONArray products = new JSONArray();
                for (VendorProduct p : listVendorProducts(v.id)) {
                    JSONObject pj = new JSONObject();
                    pj.put("product_name", p.productName); pj.put("specification", p.specification);
                    pj.put("aliases", p.aliases); pj.put("times_seen", p.timesSeen);
                    products.put(pj);
                }
                j.put("products", products);
                vendors.put(j);
            }
            root.put("vendors", vendors);
        } catch (Throwable ignored) {}
        return root;
    }

    public void importJson(JSONObject root) {
        if (root == null) return;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("vendor_products", null, null);
            db.delete("vendors", null, null);
            db.delete("own_aliases", null, null);
            JSONArray own = root.optJSONArray("own_aliases");
            if (own != null) for (int i=0;i<own.length();i++) {
                String s = own.optString(i, "").trim(); if (s.length() >= 2) insertAlias(db, s);
            }
            insertRequiredAliases(db);
            JSONArray vendors = root.optJSONArray("vendors");
            if (vendors != null) for (int i=0;i<vendors.length();i++) {
                JSONObject j = vendors.optJSONObject(i); if (j == null) continue;
                String name = j.optString("name", "").trim(); if (name.isEmpty()) continue;
                ContentValues cv = new ContentValues(); cv.put("name", name); cv.put("address", j.optString("address", ""));
                cv.put("keywords", j.optString("keywords", "")); cv.put("products", ""); cv.put("updated_at", System.currentTimeMillis());
                long vendorId = db.insertWithOnConflict("vendors", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                if (vendorId <= 0) {
                    Cursor c = db.query("vendors", new String[]{"id"}, "name=?", new String[]{name}, null, null, null);
                    try { if (c.moveToFirst()) vendorId = c.getLong(0); } finally { c.close(); }
                }
                JSONArray products = j.optJSONArray("products");
                LinkedHashSet<String> lines = new LinkedHashSet<>();
                if (products != null && vendorId > 0) for (int k=0;k<products.length();k++) {
                    JSONObject pj = products.optJSONObject(k); if (pj == null) continue;
                    String productName = pj.optString("product_name", "");
                    String spec = pj.optString("specification", "");
                    insertProduct(db, vendorId, productName, spec, pj.optString("aliases", ""), pj.optInt("times_seen", 1), false);
                    if (!productName.trim().isEmpty()) lines.add(productName + (spec.trim().isEmpty()?"":"｜規格："+spec));
                }
                if (vendorId > 0) {
                    ContentValues pv = new ContentValues(); pv.put("products", joinLines(lines, 60));
                    db.update("vendors", pv, "id=?", new String[]{String.valueOf(vendorId)});
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private static String[] splitProductLine(String line) {
        String x = safe(line).trim();
        String[] markers = {"｜規格：", "|規格:", " 規格:"};
        for (String marker : markers) {
            int p = x.indexOf(marker);
            if (p > 0) return new String[]{x.substring(0,p).trim(), x.substring(p+marker.length()).trim()};
        }
        return new String[]{x, ""};
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
