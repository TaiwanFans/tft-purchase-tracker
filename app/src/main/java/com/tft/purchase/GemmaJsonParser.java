package com.tft.purchase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses Gemma vision JSON as the single source of truth. */
public final class GemmaJsonParser {
    private GemmaJsonParser() {}

    /**
     * Legacy entry point kept for callers compiled against older versions.
     * OCR/base data is deliberately NOT used to fill missing Gemma values.
     */
    public static PurchaseOcrParser.ParsedPurchase merge(PurchaseOcrParser.ParsedPurchase ignoredBase, String response) {
        return parseGemmaOnly(response);
    }

    public static PurchaseOcrParser.ParsedPurchase parseGemmaOnly(String response) {
        PurchaseOcrParser.ParsedPurchase out = new PurchaseOcrParser.ParsedPurchase();
        out.rawText = "[Gemma Vision JSON]\n" + (response == null ? "" : response);

        JSONObject root = parseObject(response);
        if (root == null) return out;

        out.vendor = clean(root.optString("vendor", ""));
        out.location = clean(root.optString("location", ""));
        out.orderNo = normalizeOrderNo(root.optString("order_no", ""));
        out.purchaseDate = normalizeDate(root.optString("purchase_date", ""));

        JSONArray arr = root.optJSONArray("items");
        if (arr == null) return out;

        Set<String> seenLineNos = new HashSet<>();
        int accepted = 0;
        for (int i = 0; i < arr.length() && accepted < 30; i++) {
            JSONObject j = arr.optJSONObject(i);
            if (j == null) continue;

            PurchaseDbHelper.PurchaseItem item = new PurchaseDbHelper.PurchaseItem();
            item.lineNo = normalizeLineNo(j.optString("line_no", ""));
            item.description = clean(j.optString("description", ""));
            item.quantity = normalizeNumberText(j.optString("quantity", ""));
            item.unit = clean(j.optString("unit", ""));
            item.unitPrice = normalizeNumberText(j.optString("unit_price", ""));
            item.subtotal = normalizeNumberText(j.optString("subtotal", ""));
            item.deliveryDate = normalizeDate(j.optString("delivery_date", ""));
            item.note = clean(j.optString("note", ""));

            if (!isRealPurchaseItem(item)) continue;
            if (!item.lineNo.isEmpty() && !seenLineNos.add(item.lineNo)) continue;

            out.items.add(item);
            accepted++;
        }
        return out;
    }

    public static boolean hasUsefulData(PurchaseOcrParser.ParsedPurchase p) {
        if (p == null) return false;
        return !safe(p.orderNo).isEmpty()
                || !safe(p.vendor).isEmpty()
                || !safe(p.purchaseDate).isEmpty()
                || (p.items != null && !p.items.isEmpty());
    }

    private static boolean isRealPurchaseItem(PurchaseDbHelper.PurchaseItem item) {
        String d = compact(item.description);
        if (d.isEmpty()) return false;

        String[] banned = new String[] {
                "以下空白", "本頁小計", "頁小計", "稅額", "總計", "合計",
                "出貨時請標柱", "出貨時請標註", "採購單編號", "送貨前一天",
                "本公司採購資料為公司機密", "公司機密", "禁止外洩", "禁止外泄",
                "收到本採購單後", "24小時內回傳", "24小時內回傳",
                "如內容文字及交期無法配合", "如內容文字", "無誤後蓋章或簽名",
                "如發生糾紛", "彰化地方法院", "第一審管轄法院",
                "審核", "主管", "經辦人", "廠商確認", "FAXED", "FAX",
                "傳真", "回傳04-823-5682", "回傳04－823－5682"
        };
        for (String s : banned) {
            if (d.contains(compact(s))) return false;
        }

        // Standard purchase-order lines use numeric item numbers. If Gemma emitted a non-numeric
        // footer bullet or sentence as line_no, reject it rather than inventing a row.
        if (!item.lineNo.isEmpty() && !item.lineNo.matches("\\d{1,3}")) return false;

        // A zero-quantity row in this form is generally the "以下空白" sentinel. Do not show it.
        Double q = parseNumber(item.quantity);
        if (q != null && Math.abs(q) < 0.0000001d) return false;

        // Require at least one piece of row evidence besides the description. This prevents footer
        // paragraphs from becoming items even if the model accidentally assigns a line number.
        boolean hasRowValue = !safe(item.quantity).isEmpty()
                || !safe(item.unit).isEmpty()
                || !safe(item.unitPrice).isEmpty()
                || !safe(item.subtotal).isEmpty()
                || !safe(item.deliveryDate).isEmpty();
        return hasRowValue;
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

    private static String normalizeLineNo(String s) {
        String x = clean(s).replaceAll("[^0-9]", "");
        if (x.isEmpty() || x.length() > 3) return "";
        try {
            int n = Integer.parseInt(x);
            if (n <= 0 || n > 999) return "";
            return String.format(Locale.TAIWAN, "%03d", n);
        } catch (Exception e) { return ""; }
    }

    private static String normalizeNumberText(String s) {
        if (s == null) return "";
        String x = clean(s)
                .replace("，", ",")
                .replace("。", ".")
                .replaceAll("(?<=\\d)\\s+(?=\\d)", "")
                .trim();
        // Keep commas/decimal point for readability, but reject long prose accidentally placed here.
        if (x.length() > 40) return "";
        return x;
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
        if (digits < 8 || x.length() > 28) return "";
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

    private static Double parseNumber(String s) {
        try {
            String x = safe(s).replace(",", "").replaceAll("[^0-9.\\-]", "");
            if (x.isEmpty() || x.equals("-") || x.equals(".")) return null;
            return Double.parseDouble(x);
        } catch (Exception e) { return null; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String compact(String s) {
        return safe(s).replaceAll("\\s+", "").replace("：", ":").trim();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
