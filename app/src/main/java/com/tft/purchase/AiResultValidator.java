package com.tft.purchase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic guardrail after Gemma vision.
 * V2.0.8 sanitizes hallucinations but keeps useful partial rows so the user always gets a visible AI result.
 */
public final class AiResultValidator {
    private AiResultValidator() {}

    public static Validation validateAndSanitize(PurchaseOcrParser.ParsedPurchase p) {
        Validation v = new Validation();
        if (p == null) {
            v.message = "AI 沒有產生資料";
            return v;
        }

        p.vendor = clean(p.vendor);
        p.location = clean(p.location);
        p.orderNo = digitsOnly(p.orderNo);
        p.purchaseDate = date(p.purchaseDate);

        if (p.vendor.length() < 2) v.problems.add("供應廠商不完整");
        if (!p.orderNo.matches("20\\d{10}")) v.problems.add("採購單號不是 12 碼");
        if (p.purchaseDate.isEmpty()) v.problems.add("採購日期無法確認");

        if (p.orderNo.matches("20\\d{10}") && !p.purchaseDate.isEmpty()) {
            String prefix = p.purchaseDate.replace("-", "");
            if (!p.orderNo.startsWith(prefix)) v.problems.add("採購單號日期前綴與採購日期不一致");
        }

        List<PurchaseDbHelper.PurchaseItem> kept = new ArrayList<>();
        if (p.items != null) {
            int rowIndex = 0;
            for (PurchaseDbHelper.PurchaseItem i : p.items) {
                rowIndex++;
                if (i == null) continue;
                i.lineNo = normalizeLine(i.lineNo);
                i.description = clean(i.description);
                i.quantity = numericText(i.quantity);
                i.unit = clean(i.unit);
                i.unitPrice = numericText(i.unitPrice);
                i.subtotal = numericText(i.subtotal);
                i.deliveryDate = date(i.deliveryDate);
                i.note = clean(i.note);

                // Hard filters: these are definitely not purchase items.
                if (i.description.isEmpty()) continue;
                if (containsFooterText(i.description)) continue;

                // Keep a plausible table row even when one required field is uncertain.
                // This lets the user see the AI result and re-run AI instead of losing the whole document.
                boolean hasRowEvidence = !i.quantity.isEmpty() || !i.unit.isEmpty() ||
                        !i.unitPrice.isEmpty() || !i.subtotal.isEmpty() || !i.deliveryDate.isEmpty();
                if (!hasRowEvidence) continue;

                if (i.lineNo.isEmpty()) v.problems.add("第 " + rowIndex + " 個品項項次不確定");
                Double q = number(i.quantity);
                if (q == null || q <= 0) v.problems.add("品項 " + rowName(i, rowIndex) + " 數量不確定");
                if (i.deliveryDate.isEmpty()) v.problems.add("品項 " + rowName(i, rowIndex) + " 交貨日期不確定");

                if (!p.purchaseDate.isEmpty() && !i.deliveryDate.isEmpty()) {
                    long diff = dayDiff(p.purchaseDate, i.deliveryDate);
                    if (diff < -1) {
                        // Do not trust a delivery date before the purchase date; blank it, but keep the row.
                        i.deliveryDate = "";
                        v.problems.add("品項 " + rowName(i, rowIndex) + " 交貨日期疑似錯誤");
                    }
                }

                // Money is secondary. If arithmetic disagrees, blank money rather than deleting the item.
                Double price = number(i.unitPrice);
                Double subtotal = number(i.subtotal);
                if (q != null && q > 0 && price != null && subtotal != null) {
                    double expected = q * price;
                    double tolerance = Math.max(2.0, Math.abs(expected) * 0.015);
                    if (Math.abs(expected - subtotal) > tolerance) {
                        i.unitPrice = "";
                        i.subtotal = "";
                        v.problems.add("品項 " + rowName(i, rowIndex) + " 金額欄位不一致");
                    }
                }
                kept.add(i);
            }
        }
        if (p.items != null) {
            p.items.clear();
            p.items.addAll(kept);
        }

        if (kept.isEmpty()) v.problems.add("沒有找到可靠的正式採購品項");

        v.reliable = v.problems.isEmpty();
        v.hasUsefulData = !p.vendor.isEmpty() || !p.orderNo.isEmpty() || !p.purchaseDate.isEmpty() || !kept.isEmpty();
        if (v.reliable) v.message = "AI 資料已通過一致性檢查";
        else v.message = join(v.problems);
        return v;
    }

    private static String rowName(PurchaseDbHelper.PurchaseItem i, int index) {
        return i.lineNo == null || i.lineNo.isEmpty() ? String.valueOf(index) : i.lineNo;
    }

    private static String normalizeLine(String s) {
        String x = digitsOnly(s);
        if (x.isEmpty() || x.length() > 3) return "";
        try {
            int n = Integer.parseInt(x);
            if (n <= 0 || n > 999) return "";
            return String.format(Locale.TAIWAN, "%03d", n);
        } catch (Exception e) { return ""; }
    }

    private static boolean containsFooterText(String s) {
        String x = s.replaceAll("\\s+", "");
        String[] bad = {
                "以下空白","本頁小計","頁小計","稅額","總計","合計","出貨時請標註","出貨時請標柱",
                "公司機密","禁止外洩","禁止外泄","24小時內回傳","收到本採購單後",
                "如內容文字及交期","無誤後蓋章","如發生糾紛","地方法院","審核","主管","經辦人","廠商確認",
                "FAXED","傳真","回傳04-823-5682"
        };
        for (String b : bad) if (x.contains(b)) return true;
        return false;
    }

    private static String date(String s) {
        if (s == null) return "";
        String d = PurchaseOcrParser.extractDate(s);
        return d == null ? "" : d;
    }

    private static long dayDiff(String from, String to) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
            f.setLenient(false);
            Date a = f.parse(from), b = f.parse(to);
            if (a == null || b == null) return 0;
            return (b.getTime() - a.getTime()) / 86400000L;
        } catch (Exception e) { return 0; }
    }

    private static String numericText(String s) {
        if (s == null) return "";
        String x = s.replace("，", ",").replace("。", ".").replaceAll("\\s+", "").trim();
        if (!x.matches("[-+]?\\d[\\d,]*(?:\\.\\d+)?")) return "";
        return x;
    }

    private static Double number(String s) {
        try {
            if (s == null || s.trim().isEmpty()) return null;
            return Double.parseDouble(s.replace(",", ""));
        } catch (Exception e) { return null; }
    }

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String join(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) {
            if (b.length() > 0) b.append("、");
            b.append(x);
        }
        return b.toString();
    }

    public static class Validation {
        public boolean reliable;
        public boolean hasUsefulData;
        public String message = "";
        public final List<String> problems = new ArrayList<>();
    }
}
