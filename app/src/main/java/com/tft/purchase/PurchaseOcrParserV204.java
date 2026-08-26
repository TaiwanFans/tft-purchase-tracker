package com.tft.purchase;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.0.4 semantic clean-up layer on top of the fixed-layout parser.
 * It focuses on the fields that were still frequently missed on the user's
 * purchase orders: order number, purchase date and row values.
 */
public final class PurchaseOcrParserV204 {
    private PurchaseOcrParserV204() {}

    public static PurchaseOcrParser.ParsedPurchase parse(Text result, int width, int height) {
        PurchaseOcrParser.ParsedPurchase out = PurchaseOcrParser.parse(result, width, height);
        if (result == null) return out;

        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() != null) lines.add(line);
            }
        }

        String strongerOrder = findOrderNumber(lines, width, height);
        if (!strongerOrder.isEmpty()) out.orderNo = strongerOrder;

        if (out.purchaseDate == null || out.purchaseDate.isEmpty()) {
            String d = findPurchaseDate(lines, height);
            if (!d.isEmpty()) out.purchaseDate = d;
        }

        if (out.vendor == null || out.vendor.isEmpty()) {
            out.vendor = findHeaderValue(lines, width, height, "供應廠商", "供應商", "廠商名稱");
        }
        if (out.location == null || out.location.isEmpty()) {
            out.location = findHeaderValue(lines, width, height, "廠商地址", "地址");
        }

        for (PurchaseDbHelper.PurchaseItem item : out.items) {
            item.lineNo = normalizeDigits(item.lineNo);
            item.quantity = normalizeNumericField(item.quantity);
            item.unitPrice = normalizeNumericField(item.unitPrice);
            item.subtotal = normalizeNumericField(item.subtotal);
            if (item.deliveryDate == null || item.deliveryDate.isEmpty()) {
                String d = PurchaseOcrParser.extractDate(item.description + " " + item.note);
                if (!d.isEmpty()) item.deliveryDate = d;
            }
            if (item.description != null) {
                item.description = item.description
                        .replaceAll("(?i)^(品名規格明細|品名規格|品名)[:：\\s]*", "")
                        .replaceAll("\\s{2,}", " ")
                        .trim();
            }
        }
        return out;
    }

    public static int qualityScore(PurchaseOcrParser.ParsedPurchase p) {
        if (p == null) return 0;
        int score = 0;
        if (p.vendor != null && p.vendor.length() >= 2) score += 12;
        if (p.location != null && p.location.length() >= 4) score += 8;
        if (p.orderNo != null && p.orderNo.matches("20\\d{8,16}")) score += 24;
        if (p.purchaseDate != null && p.purchaseDate.matches("20\\d{2}-\\d{2}-\\d{2}")) score += 16;
        for (PurchaseDbHelper.PurchaseItem i : p.items) {
            if (i.description != null && i.description.trim().length() >= 2) score += 7;
            if (i.quantity != null && !i.quantity.trim().isEmpty()) score += 5;
            if (i.deliveryDate != null && i.deliveryDate.matches("20\\d{2}-\\d{2}-\\d{2}")) score += 9;
        }
        if (p.rawText != null) score += Math.min(10, p.rawText.length() / 120);
        return score;
    }

    public static PurchaseOcrParser.ParsedPurchase merge(PurchaseOcrParser.ParsedPurchase a,
                                                          PurchaseOcrParser.ParsedPurchase b) {
        if (a == null) return b;
        if (b == null) return a;
        PurchaseOcrParser.ParsedPurchase primary = qualityScore(a) >= qualityScore(b) ? a : b;
        PurchaseOcrParser.ParsedPurchase other = primary == a ? b : a;
        if (empty(primary.vendor)) primary.vendor = other.vendor;
        if (empty(primary.location)) primary.location = other.location;
        if (empty(primary.orderNo)) primary.orderNo = other.orderNo;
        if (empty(primary.purchaseDate)) primary.purchaseDate = other.purchaseDate;
        if (primary.items.isEmpty() || itemCompleteness(other.items) > itemCompleteness(primary.items)) {
            primary.items.clear();
            primary.items.addAll(other.items);
        }
        if (primary.rawText == null || primary.rawText.length() < (other.rawText == null ? 0 : other.rawText.length())) {
            primary.rawText = other.rawText;
        }
        return primary;
    }

    private static int itemCompleteness(List<PurchaseDbHelper.PurchaseItem> items) {
        int n = 0;
        for (PurchaseDbHelper.PurchaseItem i : items) {
            if (!empty(i.description)) n += 3;
            if (!empty(i.quantity)) n += 2;
            if (!empty(i.deliveryDate)) n += 4;
        }
        return n;
    }

    private static String findOrderNumber(List<Text.Line> lines, int width, int height) {
        Text.Line labelLine = null;
        for (Text.Line line : lines) {
            Rect r = line.getBoundingBox();
            if (r == null || r.top > height * 0.38f) continue;
            String s = clean(line.getText());
            if (containsOrderLabel(s)) {
                labelLine = line;
                String direct = extractOrderCandidate(s);
                if (!direct.isEmpty()) return direct;
            }
        }

        String best = "";
        int bestScore = Integer.MIN_VALUE;
        for (Text.Line line : lines) {
            Rect r = line.getBoundingBox();
            if (r == null || r.top > height * 0.38f) continue;
            String c = extractOrderCandidate(line.getText());
            if (c.isEmpty()) continue;
            int score = 0;
            float cx = (r.left + r.right) / 2f / Math.max(1, width);
            if (cx > 0.52f) score += 6;
            if (c.length() >= 12) score += 4;
            if (labelLine != null && labelLine.getBoundingBox() != null) {
                Rect l = labelLine.getBoundingBox();
                int cy1 = (l.top + l.bottom) / 2;
                int cy2 = (r.top + r.bottom) / 2;
                if (Math.abs(cy1 - cy2) <= Math.max(l.height(), r.height()) * 3) score += 12;
                if (r.left >= l.left) score += 3;
            }
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    private static boolean containsOrderLabel(String s) {
        String x = s.replaceAll("\\s+", "");
        return x.contains("採購單號") || x.contains("採購單號碼") || x.contains("採購單号") || x.contains("購單號");
    }

    private static String extractOrderCandidate(String s) {
        if (s == null) return "";
        String x = normalizeLikelyDigits(s);
        Matcher separated = Pattern.compile("20[0-9OoQDlI|SBZ\\-./ ]{8,22}").matcher(x);
        while (separated.find()) {
            String digits = normalizeDigits(separated.group()).replaceAll("[^0-9]", "");
            if (digits.startsWith("20") && digits.length() >= 10 && digits.length() <= 18) return digits;
        }
        String digitsOnly = normalizeDigits(x).replaceAll("[^0-9]", " ");
        Matcher m = Pattern.compile("20\\d{8,16}").matcher(digitsOnly.replace(" ", ""));
        return m.find() ? m.group() : "";
    }

    private static String findPurchaseDate(List<Text.Line> lines, int height) {
        for (Text.Line line : lines) {
            Rect r = line.getBoundingBox();
            if (r == null || r.top > height * 0.40f) continue;
            String s = clean(line.getText());
            if (s.replaceAll("\\s+", "").contains("採購日期") || s.replaceAll("\\s+", "").contains("採購日")) {
                String d = PurchaseOcrParser.extractDate(normalizeLikelyDigits(s));
                if (!d.isEmpty()) return d;
            }
        }
        return "";
    }

    private static String findHeaderValue(List<Text.Line> lines, int width, int height, String... labels) {
        for (Text.Line line : lines) {
            Rect r = line.getBoundingBox();
            if (r == null || r.top > height * 0.42f) continue;
            String s = clean(line.getText());
            for (String label : labels) {
                int idx = s.replace(" ", "").indexOf(label);
                if (idx < 0) continue;
                String noSpace = s.replace(" ", "");
                String value = noSpace.substring(Math.min(noSpace.length(), idx + label.length()))
                        .replaceFirst("^[：:;；]+", "").trim();
                if (value.length() >= 2) return value;
                Text.Line nearest = null;
                int gap = Integer.MAX_VALUE;
                for (Text.Line other : lines) {
                    if (other == line || other.getBoundingBox() == null) continue;
                    Rect o = other.getBoundingBox();
                    if (Math.abs(((r.top+r.bottom)/2) - ((o.top+o.bottom)/2)) > Math.max(r.height(), o.height()) * 3) continue;
                    if (o.left < r.right) continue;
                    int g = o.left - r.right;
                    if (g < gap && g < width * 0.50f) { gap = g; nearest = other; }
                }
                if (nearest != null) return clean(nearest.getText());
            }
        }
        return "";
    }

    private static String normalizeLikelyDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= '０' && c <= '９') c = (char)('0' + (c - '０'));
            if (c == '／') c = '/';
            if (c == '－' || c == '—' || c == '–') c = '-';
            b.append(c);
        }
        return b.toString();
    }

    private static String normalizeDigits(String s) {
        if (s == null) return "";
        String x = normalizeLikelyDigits(s);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < x.length(); i++) {
            char c = x.charAt(i);
            if (c == 'O' || c == 'o' || c == 'Q' || c == 'D') c = '0';
            else if (c == 'I' || c == 'l' || c == '|' || c == 'L') c = '1';
            else if (c == 'S' || c == 's') c = '5';
            else if (c == 'B') c = '8';
            else if (c == 'Z' || c == 'z') c = '2';
            b.append(c);
        }
        return b.toString();
    }

    private static String normalizeNumericField(String s) {
        if (s == null) return "";
        String x = normalizeDigits(s).replaceAll("[^0-9,.-]", "").trim();
        return x;
    }

    private static String clean(String s) {
        return s == null ? "" : normalizeLikelyDigits(s).replace('\u3000', ' ').replaceAll("\\s+", " ").trim();
    }

    private static boolean empty(String s) { return s == null || s.trim().isEmpty(); }
}
