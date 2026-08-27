package com.tft.purchase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generic structured-output parser shared by replaceable AI models. */
public final class AiJsonParser {
    private AiJsonParser() {}

    public static PurchaseOcrParser.ParsedPurchase parse(String response, String ocrEvidence) {
        PurchaseOcrParser.ParsedPurchase out = new PurchaseOcrParser.ParsedPurchase();
        StringBuilder raw = new StringBuilder();
        raw.append("[AI_MODEL:MiniCPM-V 4.6 + PP-OCRv6 Medium tiled + ML Kit + local knowledge]\n");
        if (ocrEvidence != null && !ocrEvidence.trim().isEmpty()) {
            String o = ocrEvidence.trim();
            if (o.length() > 16000) o = o.substring(0, 16000);
            raw.append("[OCR_EVIDENCE]\n").append(o).append("\n[/OCR_EVIDENCE]\n");
        }
        raw.append("[MODEL_JSON]\n").append(response == null ? "" : response);
        out.rawText = raw.toString();

        JSONObject root = parseObject(response);
        if (root == null) return out;

        out.vendor = clean(root.optString("vendor", ""));
        out.location = clean(root.optString("location", ""));
        out.orderNo = normalizeOrderNo(root.optString("order_no", ""));
        out.purchaseDate = normalizeDate(root.optString("purchase_date", ""));

        StringBuilder confirm = new StringBuilder();
        appendConfirm(confirm, root.optJSONArray("needs_confirmation"), "");

        JSONArray arr = root.optJSONArray("items");
        if (arr != null) {
            Set<String> seen = new HashSet<>();
            for (int n = 0; n < arr.length() && out.items.size() < 40; n++) {
                JSONObject j = arr.optJSONObject(n);
                if (j == null) continue;

                PurchaseDbHelper.PurchaseItem item = new PurchaseDbHelper.PurchaseItem();
                item.lineNo = normalizeLineNo(j.optString("line_no", ""));
                String product = clean(j.optString("description", ""));
                String spec = clean(j.optString("specification", ""));
                if (product.isEmpty() && !spec.isEmpty()) product = spec;
                else if (!spec.isEmpty() && !product.contains(spec)) product = product + "｜規格：" + spec;
                item.description = product;
                item.quantity = normalizeNumberText(j.optString("quantity", ""));
                item.unit = clean(j.optString("unit", ""));
                item.unitPrice = normalizeNumberText(j.optString("unit_price", ""));
                item.subtotal = normalizeNumberText(j.optString("subtotal", ""));
                item.deliveryDate = normalizeDate(j.optString("delivery_date", ""));
                item.note = clean(j.optString("note", ""));

                if (!isRealRow(item)) continue;
                if (!item.lineNo.isEmpty() && !seen.add(item.lineNo)) continue;
                if (item.description.isEmpty()) item.description = "需要人工確認";

                JSONArray itemConfirm = j.optJSONArray("needs_confirmation");
                if (itemConfirm != null && itemConfirm.length() > 0) {
                    String prefix = item.lineNo.isEmpty() ? "品項" : "品項" + item.lineNo;
                    appendConfirm(confirm, itemConfirm, prefix + "：");
                    if (item.note.isEmpty()) item.note = "需要人工確認";
                    else if (!item.note.contains("需要人工確認")) item.note += "｜需要人工確認";
                }
                out.items.add(item);
            }
        }

        if (confirm.length() > 0) out.rawText += "\n[NEEDS_CONFIRMATION]\n" + confirm;
        try {
            if (TftApplication.appContext() != null) {
                new RecognitionKnowledgeDb(TftApplication.appContext()).applyKnownRules(out, ocrEvidence);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static void appendConfirm(StringBuilder out, JSONArray arr, String prefix) {
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            String s = clean(arr.optString(i, ""));
            if (s.isEmpty()) continue;
            if (out.length() > 0) out.append("、");
            out.append(prefix).append(s);
        }
    }

    private static JSONObject parseObject(String response) {
        if (response == null) return null;
        int a = response.indexOf('{');
        int b = response.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try { return new JSONObject(response.substring(a, b + 1)); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean isRealRow(PurchaseDbHelper.PurchaseItem item) {
        String d = compact(item.description);
        String[] banned = {
                "以下空白", "本頁小計", "頁小計", "稅額", "總計", "合計", "FAXED", "FAX",
                "公司機密", "禁止外洩", "禁止外泄", "24小時內回傳", "彰化地方法院",
                "審核", "主管", "經辦人", "廠商確認"
        };
        for (String s : banned) if (d.contains(compact(s))) return false;
        if (!item.lineNo.isEmpty() && !item.lineNo.matches("\\d{3}")) return false;
        boolean evidence = !item.lineNo.isEmpty() || !d.isEmpty() || !item.quantity.isEmpty()
                || !item.unit.isEmpty() || !item.deliveryDate.isEmpty();
        if (!evidence) return false;
        Double q = parseNumber(item.quantity);
        return q == null || Math.abs(q) > 0.0000001d;
    }

    private static String normalizeLineNo(String s) {
        String x = clean(s).replaceAll("[^0-9]", "");
        if (x.isEmpty() || x.length() > 3) return "";
        try {
            int n = Integer.parseInt(x);
            if (n <= 0 || n > 999) return "";
            return String.format(Locale.TAIWAN, "%03d", n);
        } catch (Throwable ignored) { return ""; }
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
        if (digits < 6 || x.length() > 30) return "";
        return x;
    }

    /** Supports Gregorian dates and Taiwan ROC years such as 115/08/27 -> 2026-08-27. */
    public static String normalizeDate(String s) {
        if (s == null) return "";
        String x = s.trim().replace('年', '/').replace('月', '/').replace("日", "")
                .replace('.', '/').replace('-', '/').replaceAll("\\s+", "");

        Matcher m = Pattern.compile("(?<!\\d)(\\d{2,4})/(\\d{1,2})/(\\d{1,2})(?!\\d)").matcher(x);
        if (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                if (y < 1912) y += 1911;
                if (y < 1900 || y > 2200 || mo < 1 || mo > 12 || d < 1 || d > 31) return "";
                return String.format(Locale.TAIWAN, "%04d-%02d-%02d", y, mo, d);
            } catch (Throwable ignored) {}
        }

        Matcher compact = Pattern.compile("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)").matcher(x);
        if (compact.find()) {
            try {
                int y = Integer.parseInt(compact.group(1));
                int mo = Integer.parseInt(compact.group(2));
                int d = Integer.parseInt(compact.group(3));
                if (mo >= 1 && mo <= 12 && d >= 1 && d <= 31)
                    return String.format(Locale.TAIWAN, "%04d-%02d-%02d", y, mo, d);
            } catch (Throwable ignored) {}
        }
        return "";
    }

    private static String normalizeNumberText(String s) {
        if (s == null) return "";
        String x = clean(s).replace("，", ",").replace("。", ".")
                .replaceAll("(?<=\\d)\\s+(?=\\d)", "");
        return x.length() > 40 ? "" : x;
    }

    private static Double parseNumber(String s) {
        try {
            String x = (s == null ? "" : s).replace(",", "").replaceAll("[^0-9.\\-]", "");
            if (x.isEmpty() || x.equals("-") || x.equals(".")) return null;
            return Double.parseDouble(x);
        } catch (Throwable ignored) { return null; }
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String compact(String s) {
        return clean(s).replaceAll("\\s+", "").replace("：", ":");
    }
}
