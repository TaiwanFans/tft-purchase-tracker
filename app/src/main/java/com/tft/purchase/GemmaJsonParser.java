package com.tft.purchase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GemmaJsonParser {
    private GemmaJsonParser() {}

    public static PurchaseOcrParser.ParsedPurchase merge(PurchaseOcrParser.ParsedPurchase base, String response) {
        if (base == null) base = new PurchaseOcrParser.ParsedPurchase();
        JSONObject root = parseObject(response);
        if (root == null) return base;

        String vendor = clean(root.optString("vendor", ""));
        String location = clean(root.optString("location", ""));
        String orderNo = normalizeOrderNo(root.optString("order_no", ""));
        String purchaseDate = normalizeDate(root.optString("purchase_date", ""));

        if (!vendor.isEmpty()) base.vendor = vendor;
        if (!location.isEmpty()) base.location = location;
        if (!orderNo.isEmpty()) base.orderNo = orderNo;
        if (!purchaseDate.isEmpty()) base.purchaseDate = purchaseDate;

        JSONArray arr = root.optJSONArray("items");
        if (arr != null && arr.length() > 0) {
            List<PurchaseDbHelper.PurchaseItem> modelItems = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.optJSONObject(i);
                if (j == null) continue;
                PurchaseDbHelper.PurchaseItem item = new PurchaseDbHelper.PurchaseItem();
                item.lineNo = clean(j.optString("line_no", ""));
                item.description = clean(j.optString("description", ""));
                item.quantity = clean(j.optString("quantity", ""));
                item.unit = clean(j.optString("unit", ""));
                item.unitPrice = clean(j.optString("unit_price", ""));
                item.subtotal = clean(j.optString("subtotal", ""));
                item.deliveryDate = normalizeDate(j.optString("delivery_date", ""));
                item.note = clean(j.optString("note", ""));

                if (item.description.contains("以下空白") || item.description.contains("本頁小計") || item.description.equals("空白")) continue;
                if (item.description.isEmpty() && item.quantity.isEmpty() && item.deliveryDate.isEmpty()) continue;

                PurchaseDbHelper.PurchaseItem fallback = findFallback(base.items, item.lineNo, i);
                if (fallback != null) fillMissing(item, fallback);
                modelItems.add(item);
            }
            if (!modelItems.isEmpty()) {
                base.items.clear();
                base.items.addAll(modelItems);
            }
        }

        String raw = base.rawText == null ? "" : base.rawText;
        base.rawText = raw + "\n\n[Gemma AI JSON]\n" + response;
        return base;
    }

    private static JSONObject parseObject(String response) {
        if (response == null) return null;
        String s = response.trim();
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try { return new JSONObject(s.substring(a, b + 1)); }
        catch (Exception e) { return null; }
    }

    private static PurchaseDbHelper.PurchaseItem findFallback(List<PurchaseDbHelper.PurchaseItem> base, String lineNo, int index) {
        if (base == null || base.isEmpty()) return null;
        if (lineNo != null && !lineNo.isEmpty()) {
            String a = lineNo.replaceFirst("^0+", "");
            for (PurchaseDbHelper.PurchaseItem i : base) {
                String b = i.lineNo == null ? "" : i.lineNo.replaceFirst("^0+", "");
                if (!a.isEmpty() && a.equals(b)) return i;
            }
        }
        return index >= 0 && index < base.size() ? base.get(index) : null;
    }

    private static void fillMissing(PurchaseDbHelper.PurchaseItem x, PurchaseDbHelper.PurchaseItem f) {
        if (x.lineNo.isEmpty()) x.lineNo = f.lineNo;
        if (x.description.isEmpty()) x.description = f.description;
        if (x.quantity.isEmpty()) x.quantity = f.quantity;
        if (x.unit.isEmpty()) x.unit = f.unit;
        if (x.unitPrice.isEmpty()) x.unitPrice = f.unitPrice;
        if (x.subtotal.isEmpty()) x.subtotal = f.subtotal;
        if (x.deliveryDate.isEmpty()) x.deliveryDate = f.deliveryDate;
        if (x.note.isEmpty()) x.note = f.note;
    }

    private static String normalizeOrderNo(String s) {
        if (s == null) return "";
        String x = s.toUpperCase(Locale.TAIWAN).replaceAll("\\s+", "");
        x = x.replaceAll("(?<=\\d)O(?=\\d)", "0")
                .replaceAll("(?<=\\d)[IL](?=\\d)", "1")
                .replaceAll("(?<=\\d)S(?=\\d)", "5")
                .replaceAll("(?<=\\d)B(?=\\d)", "8")
                .replaceAll("[^A-Z0-9\\-]", "");
        int digits = x.replaceAll("\\D", "").length();
        if (digits < 6 || x.length() > 28) return "";
        return x;
    }

    private static String normalizeDate(String s) {
        if (s == null) return "";
        String d = PurchaseOcrParser.extractDate(s);
        if (!d.isEmpty()) return d;
        Matcher m = Pattern.compile("^(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})$").matcher(s.trim());
        if (!m.find()) return "";
        try {
            int y = Integer.parseInt(m.group(1)), mo = Integer.parseInt(m.group(2)), day = Integer.parseInt(m.group(3));
            if (mo < 1 || mo > 12 || day < 1 || day > 31) return "";
            return String.format(Locale.TAIWAN, "%04d-%02d-%02d", y, mo, day);
        } catch (Exception e) { return ""; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }
}
