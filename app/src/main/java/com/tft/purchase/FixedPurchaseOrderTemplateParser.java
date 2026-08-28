package com.tft.purchase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic second-pass parser for the user's fixed company purchase-order form.
 * It consumes FIXED_PURCHASE_ORDER_V220 evidence produced from actual grid cells and
 * overrides model guesses only when the fixed template was confidently matched.
 */
public final class FixedPurchaseOrderTemplateParser {
    private FixedPurchaseOrderTemplateParser() {}

    public static boolean apply(PurchaseOcrParser.ParsedPurchase out, String evidence) {
        if (out == null || evidence == null || !evidence.contains("[FIXED_PURCHASE_ORDER_V220]")) return false;
        float confidence = templateConfidence(evidence);
        if (confidence < 0.78f || !evidence.contains("matched=true")) return false;

        Map<String, String> header = new LinkedHashMap<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : evidence.split("\\n")) {
            if (line.startsWith("FIXED_HEADER\t")) header = parseTabs(line);
            else if (line.startsWith("FIXED_ROW\t")) rows.add(parseTabs(line));
        }

        String vendor = clean(header.get("vendor"));
        if (!vendor.isEmpty() && !AiResultValidator.isSelfCompany(vendor)) out.vendor = vendor;
        String location = clean(header.get("location"));
        if (!location.isEmpty()) out.location = location;
        String orderNo = normalizeOrderNo(header.get("order_no"));
        if (!orderNo.isEmpty()) out.orderNo = orderNo;
        String purchaseDate = AiJsonParser.normalizeDate(header.get("purchase_date"));
        if (!purchaseDate.isEmpty()) out.purchaseDate = purchaseDate;

        Map<String, PurchaseDbHelper.PurchaseItem> modelByLine = new LinkedHashMap<>();
        if (out.items != null) {
            for (PurchaseDbHelper.PurchaseItem i : out.items) {
                if (i != null && i.lineNo != null && !i.lineNo.trim().isEmpty()) modelByLine.put(i.lineNo.trim(), i);
            }
        }

        List<PurchaseDbHelper.PurchaseItem> fixed = new ArrayList<>();
        for (Map<String, String> r : rows) {
            String lineNo = normalizeLineNo(r.get("line_no"));
            String description = clean(r.get("description"));
            if (lineNo.isEmpty() || description.isEmpty()) continue;
            if (isStopRow(description)) break;
            if (isAdministrativeRow(description, r)) continue;

            PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
            i.lineNo = lineNo;
            i.description = description;
            i.specification = "";
            i.quantity = cleanNumber(r.get("quantity"));
            i.unit = clean(r.get("unit"));
            i.unitPrice = cleanNumber(r.get("unit_price"));
            i.subtotal = cleanNumber(r.get("subtotal"));
            i.deliveryDate = AiJsonParser.normalizeDate(r.get("delivery_date"));
            i.note = clean(r.get("note"));

            // Keep an AI split specification only if it belongs to the exact same row and
            // the fixed cell text contains the same key words. Numeric/date fields remain grid-locked.
            PurchaseDbHelper.PurchaseItem model = modelByLine.get(lineNo);
            if (model != null && model.specification != null && !model.specification.trim().isEmpty()) {
                String spec = model.specification.trim();
                if (overlapEnough(description, spec)) i.specification = spec;
            }
            fixed.add(i);
        }

        if (!fixed.isEmpty()) {
            out.items.clear();
            out.items.addAll(fixed);
        }
        out.rawText = (out.rawText == null ? "" : out.rawText)
                + "\n[FIXED_TEMPLATE_APPLIED:v2.0.20 confidence=" + String.format(Locale.US, "%.3f", confidence)
                + " rows=" + fixed.size() + "]";
        return true;
    }

    private static float templateConfidence(String evidence) {
        int p = evidence.indexOf("[FIXED_PURCHASE_ORDER_V220]");
        if (p < 0) return 0f;
        int end = evidence.indexOf("[/FIXED_PURCHASE_ORDER_V220]", p);
        String part = end > p ? evidence.substring(p, end) : evidence.substring(p);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("confidence=([0-9.]+)").matcher(part);
        if (!m.find()) return 0f;
        try { return Float.parseFloat(m.group(1)); } catch (Throwable ignored) { return 0f; }
    }

    private static Map<String, String> parseTabs(String line) {
        Map<String, String> out = new LinkedHashMap<>();
        String[] parts = line.split("\\t");
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq <= 0) continue;
            out.put(parts[i].substring(0, eq).trim(), parts[i].substring(eq + 1).trim());
        }
        return out;
    }

    private static boolean isStopRow(String description) {
        String x = compact(description);
        return x.contains("以下空白") || x.contains("本頁小計") || x.contains("注意事項");
    }

    private static boolean isAdministrativeRow(String description, Map<String, String> r) {
        String x = compact(description);
        if (x.contains("前單取消") || x.contains("以此單為準") || x.contains("已備好電線")
                || x.contains("請報價") || x.contains("數量修改") || x.contains("取消")) {
            return nonPositive(r.get("unit_price")) && nonPositive(r.get("subtotal"));
        }
        return false;
    }

    private static boolean nonPositive(String s) {
        try {
            String x = cleanNumber(s).replace(",", "");
            return x.isEmpty() || Double.parseDouble(x) <= 0d;
        } catch (Throwable ignored) { return true; }
    }

    private static boolean overlapEnough(String descriptionCell, String modelSpec) {
        String a = compact(descriptionCell), b = compact(modelSpec);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.contains(b)) return true;
        int hits = 0, total = 0;
        for (int i = 0; i + 1 < b.length(); i += 2) {
            String token = b.substring(i, Math.min(b.length(), i + 2));
            if (token.matches("[0-9A-Za-z]+") || token.trim().isEmpty()) continue;
            total++;
            if (a.contains(token)) hits++;
        }
        return total > 0 && hits * 2 >= total;
    }

    private static String normalizeLineNo(String s) {
        String x = clean(s).replaceAll("[^0-9]", "");
        if (x.isEmpty() || x.length() > 4) return "";
        try {
            int n = Integer.parseInt(x);
            if (n <= 0 || n > 9999) return "";
            int width = x.length();
            if (width < 3) width = 3;
            return String.format(Locale.TAIWAN, "%0" + width + "d", n);
        } catch (Throwable ignored) { return ""; }
    }

    private static String normalizeOrderNo(String s) {
        if (s == null) return "";
        String x = s.toUpperCase(Locale.TAIWAN).replaceAll("\\s+", "")
                .replaceAll("(?<=\\d)O(?=\\d)", "0")
                .replaceAll("(?<=\\d)[IL](?=\\d)", "1")
                .replaceAll("(?<=\\d)S(?=\\d)", "5")
                .replaceAll("(?<=\\d)B(?=\\d)", "8")
                .replaceAll("[^A-Z0-9\\-]", "");
        return x.matches("20\\d{10}") ? x : "";
    }

    private static String cleanNumber(String s) {
        if (s == null) return "";
        String x = clean(s).replace("，", ",").replace("。", ".");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d[\\d,]*(?:\\.\\d+)?").matcher(x);
        return m.find() ? m.group() : "";
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String compact(String s) {
        return clean(s).replaceAll("\\s+", "").replace("：", ":");
    }
}
