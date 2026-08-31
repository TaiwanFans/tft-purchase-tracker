package com.tft.purchase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Deterministic final guardrail. AI output is never written to the DB without passing here. */
public final class AiResultValidator {
    private AiResultValidator() {}

    private static final String[] SELF_COMPANY = {
            "全益畜牧器具有限公司", "全益畜牧器具公司", "全益",
            "台灣電扇科技有限公司", "台灣電扇", "全益台灣電扇"
    };

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

        // Final safety layer: own-company names are prohibited from vendor_name, even if legal suffixes
        // or OCR decorations differ (例如 全益畜牧器具有限公司 vs 全益畜牧器具公司).
        if (isSelfCompany(p.vendor)) {
            String rejected = p.vendor;
            p.vendor = "";
            v.problems.add("SELF_COMPANY：交易對象誤抓到我方公司，已只清空此欄等待確認");
            p.rawText = safe(p.rawText) + "\n[SELF_COMPANY_REJECTED] " + rejected;
        }

        if (p.vendor.length() < 2) v.problems.add("交易對象不完整");
        if (!p.orderNo.matches("20\\d{10}")) v.problems.add("單據號碼不是 12 碼");
        if (p.purchaseDate.isEmpty()) v.problems.add("文件日期無法確認");

        if (p.orderNo.matches("20\\d{10}") && !p.purchaseDate.isEmpty()) {
            String prefix = p.purchaseDate.replace("-", "");
            if (!p.orderNo.startsWith(prefix)) {
                v.problems.add("ORDER_NUMBER_DATE_CONFLICT：單據號碼日期前綴與文件日期不一致");
            }
        }

        List<PurchaseDbHelper.PurchaseItem> kept = new ArrayList<>();
        if (p.items != null) {
            int rowIndex = 0;
            for (PurchaseDbHelper.PurchaseItem i : p.items) {
                rowIndex++;
                if (i == null) continue;
                i.lineNo = normalizeLine(i.lineNo);
                i.description = clean(i.description);
                i.specification = clean(i.specification);
                i.quantity = numericText(i.quantity);
                i.unit = clean(i.unit);
                i.unitPrice = numericText(i.unitPrice);
                i.subtotal = numericText(i.subtotal);
                i.deliveryDate = date(i.deliveryDate);
                i.note = clean(i.note);

                String combined = i.description + " " + i.specification;
                if (combined.trim().isEmpty()) continue;
                if (containsFooterText(combined)) continue;

                boolean hasRowEvidence = !i.quantity.isEmpty() || !i.unit.isEmpty() ||
                        !i.unitPrice.isEmpty() || !i.subtotal.isEmpty() || !i.deliveryDate.isEmpty();
                if (!hasRowEvidence) continue;

                if (i.lineNo.isEmpty()) v.problems.add("第 " + rowIndex + " 個品項項次不確定");
                Double q = number(i.quantity);
                if (q == null || q <= 0) v.problems.add("品項 " + rowName(i, rowIndex) + " 數量不確定");

                // Missing delivery date is NOT automatically an error. Quotations and many purchase forms
                // legitimately omit item delivery dates. Only validate a delivery date when one is present.
                if (!p.purchaseDate.isEmpty() && !i.deliveryDate.isEmpty()) {
                    long diff = dayDiff(p.purchaseDate, i.deliveryDate);
                    if (diff < -1) {
                        i.deliveryDate = "";
                        addNote(i, "DATE_CONFLICT：交貨日期早於文件日期，請只確認交貨日欄位");
                        v.problems.add("品項 " + rowName(i, rowIndex) + " 交貨日期疑似錯誤");
                    }
                }

                Double price = number(i.unitPrice);
                Double subtotal = number(i.subtotal);
                if (q != null && q > 0 && price != null && price > 0 && subtotal != null && subtotal >= 0) {
                    double expected = q * price;
                    double tolerance = Math.max(2.0, Math.abs(expected) * 0.015);
                    if (Math.abs(expected - subtotal) > tolerance) {
                        String originalQuantity = i.quantity;
                        boolean specLeak = looksLikeSpecificationLeak(i, originalQuantity);
                        if (specLeak) {
                            // Example caught by the golden document: "6英吋型" must stay in specification,
                            // not become quantity=6 / unit=英吋型.
                            i.quantity = "";
                            if (looksLikeSpecificationUnit(i.unit)) i.unit = "";
                            addNote(i, "NUMBER_CONFLICT：疑似把規格中的數字／文字誤塞到數量或單位欄；已只清空可疑欄位");
                        } else {
                            // Do NOT invent subtotal/price-derived candidates. Keep observed values visible,
                            // mark only this row for review, and let cell-level OCR/visual rescue re-check it.
                            addNote(i, "NUMBER_CONFLICT：數量 " + originalQuantity + " × 單價 " + i.unitPrice +
                                    " ≠ 小計 " + i.subtotal + "；禁止反推新數字，請只核對此列金額欄位");
                        }
                        v.problems.add("品項 " + rowName(i, rowIndex) + " NUMBER_CONFLICT");
                    }
                }
                kept.add(i);
            }
        }
        if (p.items != null) {
            p.items.clear();
            p.items.addAll(kept);
        }

        if (kept.isEmpty()) v.problems.add("沒有找到可靠的正式品項列");

        v.reliable = v.problems.isEmpty();
        v.hasUsefulData = !p.vendor.isEmpty() || !p.orderNo.isEmpty() || !p.purchaseDate.isEmpty() || !kept.isEmpty();
        if (v.reliable) v.message = "AI 資料已通過一致性檢查";
        else v.message = join(v.problems);
        return v;
    }

    public static boolean isSelfCompany(String text) {
        String n = normCompany(text);
        if (n.isEmpty()) return false;
        for (String self : SELF_COMPANY) {
            String s = normCompany(self);
            if (n.equals(s)) return true;
            // Long company cores may safely match inside document-title decorations, e.g. "全益畜牧器具報價單".
            if (s.length() >= 6 && (n.contains(s) || s.contains(n))) return true;
        }
        return false;
    }

    private static boolean looksLikeSpecificationLeak(PurchaseDbHelper.PurchaseItem i, String quantity) {
        String spec = compact(i.specification);
        String q = digitsOnly(quantity);
        if (!q.isEmpty()) {
            if (spec.contains(q + "英吋") || spec.contains(q + "吋") || spec.contains(q + "MM") || spec.contains(q + "CM")) return true;
        }
        return looksLikeSpecificationUnit(i.unit);
    }

    private static boolean looksLikeSpecificationUnit(String unit) {
        String u = compact(unit).toUpperCase(Locale.TAIWAN);
        if (u.isEmpty()) return false;
        String[] bad = {"英吋", "吋型", "型", "含電解", "含螺絲", "IP55", "220V", "380V", "MM", "CM"};
        for (String b : bad) if (u.contains(b)) return true;
        return false;
    }

    private static String rowName(PurchaseDbHelper.PurchaseItem i, int index) {
        return i.lineNo == null || i.lineNo.isEmpty() ? String.valueOf(index) : i.lineNo;
    }

    /** Preserve 4-digit source row numbers (0001) instead of destructively converting to 001. */
    private static String normalizeLine(String s) {
        String x = digitsOnly(s);
        if (x.isEmpty() || x.length() > 4) return "";
        try {
            int n = Integer.parseInt(x);
            if (n <= 0 || n > 9999) return "";
            int width = x.length() >= 4 ? 4 : 3;
            return String.format(Locale.TAIWAN, "%0" + width + "d", n);
        } catch (Exception e) { return ""; }
    }

    private static boolean containsFooterText(String s) {
        String x = compact(s);
        String[] bad = {
                "以下空白","本頁小計","頁小計","稅額","總計","合計","注意事項","出貨說明","回傳說明","簽章",
                "出貨時請標註","出貨時請標柱","公司機密","禁止外洩","禁止外泄","24小時內回傳","收到本採購單後",
                "如內容文字及交期","無誤後蓋章","如發生糾紛","地方法院","審核","主管","經辦人","廠商確認",
                "三相與單相同價","原物料持續調漲","訂貨交貨及付款方式","以上報價為","不可扣%","不含安裝",
                "若有任何疑問","業務聯絡","進口產品","每批購買數量越多","FAXED","傳真","回傳04-823-5682"
        };
        for (String b : bad) if (x.contains(compact(b))) return true;
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

    private static void addNote(PurchaseDbHelper.PurchaseItem i, String note) {
        if (i.note == null || i.note.trim().isEmpty()) i.note = note;
        else if (!i.note.contains(note)) i.note += "｜" + note;
    }

    private static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String compact(String s) {
        return clean(s).replaceAll("\\s+", "").replace("：", ":");
    }

    private static String normCompany(String s) {
        String n = safe(s).toUpperCase(Locale.TAIWAN)
                .replaceAll("[\\s　:：,，.。()（）/\\-＿_]+", "")
                .replace("股份有限公司", "")
                .replace("有限公司", "")
                .replace("有限責任", "")
                .replace("公司", "")
                .trim();
        return n;
    }

    private static String safe(String s) { return s == null ? "" : s; }

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
