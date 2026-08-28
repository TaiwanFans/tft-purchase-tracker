package com.tft.purchase;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public final class BackupFileHelper {
    public static final String PREF_URI = "backup_file_uri";
    private BackupFileHelper() {}

    public static boolean isConfigured(Context context) {
        return !context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE).getString(PREF_URI, "").isEmpty();
    }

    public static String backupNow(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE);
        String uriString = prefs.getString(PREF_URI, "");
        if (uriString == null || uriString.isEmpty()) throw new Exception("尚未設定 Google Drive 備份檔");
        Uri uri = Uri.parse(uriString);
        PurchaseDbHelper db = new PurchaseDbHelper(context);
        JSONObject root = new JSONObject();
        root.put("backup_version", 4);
        root.put("created_at", System.currentTimeMillis());
        root.put("reminder_days", prefs.getInt("reminder_days", 5));
        root.put("recognition_knowledge", new RecognitionKnowledgeDb(context).exportJson());
        JSONArray orders = new JSONArray();
        Map<Long, String> imageEntries = new HashMap<>();
        for (PurchaseDbHelper.Purchase p : db.list("")) {
            JSONObject o = new JSONObject();
            o.put("vendor_name", p.vendorName);
            o.put("location", p.location);
            o.put("order_no", p.orderNo);
            o.put("purchase_date", p.purchaseDate);
            o.put("signature_return_date", p.signatureReturnDate);
            o.put("notes", p.notes);
            o.put("raw_ocr", p.rawOcr);
            o.put("completed", p.completed);
            o.put("created_at", p.createdAt);
            o.put("legacy_items", p.legacyItems);
            o.put("legacy_delivery_date", p.legacyDeliveryDate);
            File image = p.imagePath == null ? null : new File(p.imagePath);
            if (image != null && image.exists()) {
                String entry = "images/" + p.id + "_" + image.getName();
                imageEntries.put(p.id, entry);
                o.put("image_entry", entry);
            } else o.put("image_entry", "");
            JSONArray items = new JSONArray();
            for (PurchaseDbHelper.PurchaseItem i : db.listItems(p.id)) {
                JSONObject j = new JSONObject();
                j.put("line_no", i.lineNo);
                j.put("description", i.description);
                j.put("specification", i.specification);
                j.put("quantity", i.quantity);
                j.put("unit", i.unit);
                j.put("unit_price", i.unitPrice);
                j.put("subtotal", i.subtotal);
                j.put("delivery_date", i.deliveryDate);
                j.put("note", i.note);
                j.put("completed", i.completed);
                items.put(j);
            }
            o.put("items", items);
            orders.put(o);
        }
        root.put("orders", orders);
        OutputStream raw;
        try { raw = context.getContentResolver().openOutputStream(uri, "rwt"); }
        catch (Exception e) { raw = context.getContentResolver().openOutputStream(uri, "w"); }
        if (raw == null) throw new Exception("無法寫入 Google Drive 備份檔，請重新設定備份位置");
        try (OutputStream closeRaw = raw; ZipOutputStream zip = new ZipOutputStream(closeRaw)) {
            zip.putNextEntry(new ZipEntry("data.json"));
            zip.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (PurchaseDbHelper.Purchase p : db.list("")) {
                String entry = imageEntries.get(p.id);
                if (entry == null) continue;
                File f = new File(p.imagePath);
                if (!f.exists()) continue;
                zip.putNextEntry(new ZipEntry(entry));
                try (InputStream in = new java.io.FileInputStream(f)) {
                    byte[] buf = new byte[16384]; int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
            }
        }
        String label = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.TAIWAN).format(new Date());
        prefs.edit().putLong("last_backup_ms", System.currentTimeMillis()).putString("last_backup_label", label).apply();
        return label;
    }

    public static int restore(Context context, Uri zipUri) throws Exception {
        File restoreDir = new File(context.getFilesDir(), "purchase_images");
        if (!restoreDir.exists() && !restoreDir.mkdirs()) throw new Exception("無法建立還原圖片資料夾");
        Map<String, String> extracted = new HashMap<>();
        String json = null;
        InputStream raw = context.getContentResolver().openInputStream(zipUri);
        if (raw == null) throw new Exception("無法讀取備份檔");
        try (InputStream closeRaw = raw; ZipInputStream zip = new ZipInputStream(closeRaw)) {
            ZipEntry e; byte[] buf = new byte[16384];
            while ((e = zip.getNextEntry()) != null) {
                String name = e.getName();
                if ("data.json".equals(name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); int n;
                    while ((n = zip.read(buf)) > 0) out.write(buf, 0, n);
                    json = out.toString(StandardCharsets.UTF_8.name());
                } else if (name.startsWith("images/") && !e.isDirectory()) {
                    File out = new File(restoreDir, "restore_" + System.nanoTime() + "_" + new File(name).getName());
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int n; while ((n = zip.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    extracted.put(name, out.getAbsolutePath());
                }
                zip.closeEntry();
            }
        }
        if (json == null) throw new Exception("備份檔缺少 data.json");
        JSONObject root = new JSONObject(json);
        JSONArray orders = root.optJSONArray("orders");
        if (orders == null) throw new Exception("備份格式不正確");
        PurchaseDbHelper db = new PurchaseDbHelper(context);
        db.clearAll();
        int count = 0;
        for (int x = 0; x < orders.length(); x++) {
            JSONObject o = orders.getJSONObject(x);
            PurchaseDbHelper.Purchase p = new PurchaseDbHelper.Purchase();
            p.vendorName = o.optString("vendor_name", ""); p.location = o.optString("location", "");
            p.orderNo = o.optString("order_no", ""); p.purchaseDate = o.optString("purchase_date", "");
            p.signatureReturnDate = o.optString("signature_return_date", ""); p.notes = o.optString("notes", "");
            p.rawOcr = o.optString("raw_ocr", ""); p.completed = o.optBoolean("completed", false);
            p.createdAt = o.optLong("created_at", System.currentTimeMillis()); p.legacyItems = o.optString("legacy_items", "");
            p.legacyDeliveryDate = o.optString("legacy_delivery_date", ""); p.imagePath = extracted.getOrDefault(o.optString("image_entry", ""), "");
            long newId = db.insert(p);
            JSONArray items = o.optJSONArray("items");
            if (items != null) for (int y = 0; y < items.length(); y++) {
                JSONObject j = items.getJSONObject(y);
                PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
                i.purchaseId = newId; i.lineNo = j.optString("line_no", ""); i.description = j.optString("description", "");
                i.specification = j.optString("specification", "");
                i.quantity = j.optString("quantity", ""); i.unit = j.optString("unit", ""); i.unitPrice = j.optString("unit_price", "");
                i.subtotal = j.optString("subtotal", ""); i.deliveryDate = j.optString("delivery_date", ""); i.note = j.optString("note", "");
                i.completed = j.optBoolean("completed", false); db.insertItem(i);
            }
            count++;
        }
        JSONObject knowledge = root.optJSONObject("recognition_knowledge");
        if (knowledge != null) new RecognitionKnowledgeDb(context).importJson(knowledge);
        context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE).edit()
                .putInt("reminder_days", root.optInt("reminder_days", 5)).apply();
        return count;
    }
}
