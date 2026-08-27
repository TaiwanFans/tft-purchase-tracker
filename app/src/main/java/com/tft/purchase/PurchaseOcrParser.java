package com.tft.purchase;

import android.graphics.Rect;

import com.google.mlkit.vision.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parser tuned for TFT/Quan-Yi purchase order sheets shown by the user. */
public final class PurchaseOcrParser {
    private PurchaseOcrParser() {}

    public static class ParsedPurchase {
        public String vendor = "";
        public String location = "";
        public String orderNo = "";
        public String purchaseDate = "";
        public String rawText = "";
        public final List<PurchaseDbHelper.PurchaseItem> items = new ArrayList<>();
    }

    private static class Token {
        String text;
        Rect box;
        Token(String text, Rect box) { this.text = text; this.box = box; }
        float cx() { return (box.left + box.right) / 2f; }
        float cy() { return (box.top + box.bottom) / 2f; }
    }

    private static class RowAnchor {
        float cy;
        String lineNo = "";
        RowAnchor(float cy, String lineNo) { this.cy = cy; this.lineNo = lineNo; }
    }

    public static ParsedPurchase parse(Text result, int width, int height) {
        ParsedPurchase out = new ParsedPurchase();
        out.rawText = result == null ? "" : result.getText();
        if (result == null || width <= 0 || height <= 0) return out;

        List<Text.Line> lines = new ArrayList<>();
        List<Token> tokens = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                if (line.getBoundingBox() != null) lines.add(line);
                for (Text.Element e : line.getElements()) {
                    if (e.getBoundingBox() != null) tokens.add(new Token(clean(e.getText()), e.getBoundingBox()));
                }
                if (line.getElements().isEmpty() && line.getBoundingBox() != null) {
                    tokens.add(new Token(clean(line.getText()), line.getBoundingBox()));
                }
            }
        }

        out.vendor = findLabelValue(lines, width, height, "供應廠商", "供應商");
        out.location = findLabelValue(lines, width, height, "廠商地址", "地址");

        String orderCandidate = findLabelValue(lines, width, height, "採購單號", "採購單號碼");
        out.orderNo = extractOrderNo(orderCandidate);
        if (out.orderNo.isEmpty()) {
            for (Text.Line line : lines) {
                Rect b = line.getBoundingBox();
                if (b == null || b.top > height * 0.34f) continue;
                String s = clean(line.getText());
                if (s.contains("採購單號")) {
                    out.orderNo = extractOrderNo(s);
                    if (!out.orderNo.isEmpty()) break;
                }
            }
        }

        String dateCandidate = findLabelValue(lines, width, height, "採購日期", "採購日");
        out.purchaseDate = extractDate(dateCandidate);
        if (out.purchaseDate.isEmpty()) {
            for (Text.Line line : lines) {
                Rect b = line.getBoundingBox();
                if (b == null || b.top > height * 0.34f) continue;
                String s = clean(line.getText());
                if (s.contains("採購日期") || s.contains("採購日")) {
                    out.purchaseDate = extractDate(s);
                    if (!out.purchaseDate.isEmpty()) break;
                }
            }
        }

        int headerBottom = findTableHeaderBottom(lines, height);
        int tableBottom = findTableBottom(lines, height, headerBottom);
        ColumnLayout cols = detectColumns(lines, width, headerBottom);
        List<RowAnchor> anchors = detectRowAnchors(lines, tokens, cols, width, height, headerBottom, tableBottom);

        for (int i = 0; i < anchors.size(); i++) {
            float top = i == 0 ? headerBottom : (anchors.get(i - 1).cy + anchors.get(i).cy) / 2f;
            float bottom = i == anchors.size() - 1 ? tableBottom : (anchors.get(i).cy + anchors.get(i + 1).cy) / 2f;
            PurchaseDbHelper.PurchaseItem item = parseRow(tokens, cols, width, top, bottom, anchors.get(i).lineNo);
            if (item == null) continue;
            String desc = compactSpaces(item.description);
            if (desc.contains("以下空白") || desc.equals("空白")) continue;
            if (desc.isEmpty() && item.deliveryDate.isEmpty() && item.quantity.isEmpty()) continue;
            out.items.add(item);
        }

        if (out.items.isEmpty()) {
            List<RowAnchor> byDates = detectDateOnlyAnchors(lines, cols, headerBottom, tableBottom);
            for (int i = 0; i < byDates.size(); i++) {
                float top = i == 0 ? headerBottom : (byDates.get(i - 1).cy + byDates.get(i).cy) / 2f;
                float bottom = i == byDates.size() - 1 ? tableBottom : (byDates.get(i).cy + byDates.get(i + 1).cy) / 2f;
                PurchaseDbHelper.PurchaseItem item = parseRow(tokens, cols, width, top, bottom, "");
                if (item == null || compactSpaces(item.description).contains("以下空白")) continue;
                out.items.add(item);
            }
        }
        return out;
    }

    private static String findLabelValue(List<Text.Line> lines, int width, int height, String... labels) {
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.top > height * 0.36f) continue;
            String s = clean(line.getText());
            for (String label : labels) {
                int idx = s.indexOf(label);
                if (idx < 0) continue;
                String value = s.substring(idx + label.length()).replaceFirst("^[：:;；\\s]+", "").trim();
                if (!value.isEmpty()) return value;
                Text.Line best = null;
                int bestGap = Integer.MAX_VALUE;
                for (Text.Line other : lines) {
                    if (other == line || other.getBoundingBox() == null) continue;
                    Rect o = other.getBoundingBox();
                    int cy1 = (b.top + b.bottom) / 2;
                    int cy2 = (o.top + o.bottom) / 2;
                    if (Math.abs(cy1 - cy2) > Math.max(b.height(), o.height()) * 2) continue;
                    if (o.left < b.right - width * 0.02f) continue;
                    int gap = o.left - b.right;
                    if (gap < bestGap) { bestGap = gap; best = other; }
                }
                if (best != null && bestGap < width * 0.45f) return clean(best.getText());
            }
        }
        return "";
    }

    private static String extractOrderNo(String s) {
        String x = normalizeNumeric(s);
        Matcher m = Pattern.compile("20\\d{8,14}").matcher(x);
        return m.find() ? m.group() : "";
    }

    public static String extractDate(String s) {
        if (s == null) return "";
        String x = normalizeNumeric(s).replaceAll("\\s+", "");
        Matcher m = Pattern.compile("(?<!\\d)(20\\d{2})[./\\-年](\\d{1,2})[./\\-月](\\d{1,2})(?:日)?(?!\\d)").matcher(x);
        if (!m.find()) {
            Matcher compact = Pattern.compile("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)").matcher(x);
            if (!compact.find()) return "";
            m = compact;
        }
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            if (mo < 1 || mo > 12 || d < 1 || d > 31) return "";
            return String.format(Locale.TAIWAN, "%04d-%02d-%02d", y, mo, d);
        } catch (Exception e) { return ""; }
    }

    private static int findTableHeaderBottom(List<Text.Line> lines, int height) {
        int best = (int) (height * 0.19f);
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.top > height * 0.45f) continue;
            String s = compactSpaces(clean(line.getText()));
            if (s.contains("品名") || s.contains("規格明細") || s.contains("交貨日期") || s.equals("數量")) {
                best = Math.max(best, b.bottom + Math.max(2, b.height() / 4));
            }
        }
        return best;
    }

    private static int findTableBottom(List<Text.Line> lines, int height, int headerBottom) {
        int best = (int) (height * 0.79f);
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.top <= headerBottom) continue;
            String s = compactSpaces(clean(line.getText()));
            if (s.contains("本頁小計")) best = Math.min(best, b.top - 2);
        }
        return Math.max(headerBottom + 50, best);
    }

    private static class ColumnLayout {
        float descStart, qtyStart, qtyEnd, unitEnd, priceEnd, subtotalEnd, deliveryEnd, noteEnd;
        float deliveryStart;
    }

    private static ColumnLayout detectColumns(List<Text.Line> lines, int width, int headerBottom) {
        float qty = width * 0.525f, unit = width * 0.575f, price = width * 0.635f,
                subtotal = width * 0.710f, delivery = width * 0.820f, note = width * 0.940f;
        int yMin = Math.max(0, headerBottom - Math.round(width * 0.06f));
        int yMax = headerBottom + Math.round(width * 0.025f);
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.bottom < yMin || b.top > yMax) continue;
            String s = compactSpaces(clean(line.getText()));
            float cx = (b.left + b.right) / 2f;
            if (s.equals("數量") || s.contains("數量")) qty = cx;
            else if (s.equals("單位") || s.contains("單位")) unit = cx;
            else if (s.equals("單價") || s.contains("單價")) price = cx;
            else if (s.equals("小計") || s.contains("小計")) subtotal = cx;
            else if (s.contains("交貨日期") || (s.contains("交貨") && s.contains("日期"))) delivery = cx;
            else if (s.equals("備註") || s.contains("備註")) note = cx;
        }
        if (!(qty < unit && unit < price && price < subtotal && subtotal < delivery && delivery < note)) {
            qty = width * 0.525f; unit = width * 0.575f; price = width * 0.635f;
            subtotal = width * 0.710f; delivery = width * 0.820f; note = width * 0.940f;
        }
        ColumnLayout c = new ColumnLayout();
        c.descStart = width * 0.065f;
        c.qtyStart = Math.max(width * 0.46f, qty - Math.min(width * 0.038f, (unit - qty) * 0.72f));
        c.qtyEnd = (qty + unit) / 2f;
        c.unitEnd = (unit + price) / 2f;
        c.priceEnd = (price + subtotal) / 2f;
        c.subtotalEnd = (subtotal + delivery) / 2f;
        c.deliveryStart = c.subtotalEnd;
        c.deliveryEnd = (delivery + note) / 2f;
        c.noteEnd = width * 0.995f;
        return c;
    }

    private static List<RowAnchor> detectRowAnchors(List<Text.Line> lines, List<Token> tokens, ColumnLayout cols,
                                                     int width, int height, int headerBottom, int tableBottom) {
        List<RowAnchor> a = new ArrayList<>();
        Pattern row = Pattern.compile("^0{0,2}(\\d{1,2})(?:$|\\D.*)");
        for (Token t : tokens) {
            if (t.box.top <= headerBottom || t.box.bottom >= tableBottom || t.box.left > width * 0.14f) continue;
            Matcher m = row.matcher(compactSpaces(t.text));
            if (!m.matches()) continue;
            try {
                int n = Integer.parseInt(m.group(1));
                if (n < 1 || n > 99) continue;
                a.add(new RowAnchor(t.cy(), String.format(Locale.TAIWAN, "%03d", n)));
            } catch (Exception ignored) {}
        }
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.top <= headerBottom || b.bottom >= tableBottom) continue;
            float cx = (b.left + b.right) / 2f;
            if (cx < cols.deliveryStart - width * 0.03f || cx > cols.deliveryEnd + width * 0.04f) continue;
            if (!extractDate(line.getText()).isEmpty()) a.add(new RowAnchor((b.top + b.bottom) / 2f, ""));
        }
        return mergeAnchors(a, Math.max(12f, height * 0.009f));
    }

    private static List<RowAnchor> detectDateOnlyAnchors(List<Text.Line> lines, ColumnLayout cols, int headerBottom, int tableBottom) {
        List<RowAnchor> a = new ArrayList<>();
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.top <= headerBottom || b.bottom >= tableBottom) continue;
            float cx = (b.left + b.right) / 2f;
            if (cx < cols.deliveryStart || cx > cols.deliveryEnd) continue;
            if (!extractDate(line.getText()).isEmpty()) a.add(new RowAnchor((b.top + b.bottom) / 2f, ""));
        }
        return mergeAnchors(a, 18f);
    }

    private static List<RowAnchor> mergeAnchors(List<RowAnchor> in, float threshold) {
        Collections.sort(in, Comparator.comparingDouble(x -> x.cy));
        List<RowAnchor> out = new ArrayList<>();
        for (RowAnchor x : in) {
            if (out.isEmpty() || Math.abs(out.get(out.size() - 1).cy - x.cy) > threshold) {
                out.add(new RowAnchor(x.cy, x.lineNo));
            } else {
                RowAnchor last = out.get(out.size() - 1);
                last.cy = (last.cy + x.cy) / 2f;
                if (last.lineNo.isEmpty() && !x.lineNo.isEmpty()) last.lineNo = x.lineNo;
            }
        }
        return out;
    }

    private static PurchaseDbHelper.PurchaseItem parseRow(List<Token> all, ColumnLayout c, int width,
                                                           float top, float bottom, String anchorLineNo) {
        List<Token> row = new ArrayList<>();
        for (Token t : all) if (t.cy() >= top && t.cy() < bottom) row.add(t);
        if (row.isEmpty()) return null;
        Collections.sort(row, (a, b) -> {
            int dy = a.box.top - b.box.top;
            if (Math.abs(dy) > Math.max(8, Math.min(a.box.height(), b.box.height()) / 2)) return dy;
            return a.box.left - b.box.left;
        });

        List<Token> desc = new ArrayList<>(), qty = new ArrayList<>(), unit = new ArrayList<>(), price = new ArrayList<>(),
                subtotal = new ArrayList<>(), delivery = new ArrayList<>(), note = new ArrayList<>(), firstCol = new ArrayList<>();
        for (Token t : row) {
            if (t.text.isEmpty()) continue;
            float x = t.cx();
            if (x < c.descStart) firstCol.add(t);
            else if (x < c.qtyStart) desc.add(t);
            else if (x < c.qtyEnd) qty.add(t);
            else if (x < c.unitEnd) unit.add(t);
            else if (x < c.priceEnd) price.add(t);
            else if (x < c.subtotalEnd) subtotal.add(t);
            else if (x < c.deliveryEnd) delivery.add(t);
            else if (x < c.noteEnd) note.add(t);
        }

        PurchaseDbHelper.PurchaseItem item = new PurchaseDbHelper.PurchaseItem();
        item.lineNo = !anchorLineNo.isEmpty() ? anchorLineNo : findLineNo(firstCol);
        item.description = stripLeadingLineNo(join(desc));
        item.quantity = firstNumber(qty);
        item.unit = firstUnit(unit);
        item.unitPrice = firstNumber(price);
        item.subtotal = firstNumber(subtotal);
        item.deliveryDate = extractDate(join(delivery));
        if (item.deliveryDate.isEmpty()) item.deliveryDate = extractDate(join(row));
        item.note = join(note);

        if (item.quantity.isEmpty()) {
            for (Token t : row) {
                float x = t.cx() / width;
                if (x >= 0.47f && x <= 0.57f && isNumber(t.text)) { item.quantity = cleanNumber(t.text); break; }
            }
        }
        return item;
    }

    private static String findLineNo(List<Token> xs) {
        Pattern p = Pattern.compile("^0{0,2}(\\d{1,2})$");
        for (Token t : xs) {
            Matcher m = p.matcher(compactSpaces(t.text));
            if (m.matches()) {
                try { return String.format(Locale.TAIWAN, "%03d", Integer.parseInt(m.group(1))); }
                catch (Exception ignored) {}
            }
        }
        return "";
    }

    private static String firstNumber(List<Token> xs) {
        for (Token t : xs) if (isNumber(t.text)) return cleanNumber(t.text);
        String joined = join(xs);
        Matcher m = Pattern.compile("[-+]?\\d[\\d,]*(?:\\.\\d+)?").matcher(normalizeNumeric(joined));
        return m.find() ? m.group() : "";
    }

    private static String firstUnit(List<Token> xs) {
        for (Token t : xs) {
            String s = compactSpaces(t.text);
            if (s.matches("(個|顆|台|支|組|件|箱|包|只|片|條|式|粒|盒|套|張|卷|桶|公斤|KG|PCS|PC|SET)") ) return s;
        }
        return xs.isEmpty() ? "" : compactSpaces(xs.get(0).text);
    }

    private static boolean isNumber(String s) {
        return normalizeNumeric(s).replace(" ", "").matches("[-+]?\\d[\\d,]*(?:\\.\\d+)?");
    }

    private static String cleanNumber(String s) { return normalizeNumeric(s).replaceAll("\\s+", "").trim(); }

    private static String join(List<Token> xs) {
        if (xs == null || xs.isEmpty()) return "";
        List<Token> copy = new ArrayList<>(xs);
        Collections.sort(copy, (a, b) -> {
            int dy = a.box.top - b.box.top;
            if (Math.abs(dy) > Math.max(8, Math.min(a.box.height(), b.box.height()) / 2)) return dy;
            return a.box.left - b.box.left;
        });
        StringBuilder s = new StringBuilder();
        int lastY = Integer.MIN_VALUE;
        for (Token t : copy) {
            String x = clean(t.text);
            if (x.isEmpty()) continue;
            if (s.length() > 0) {
                if (lastY != Integer.MIN_VALUE && Math.abs(t.box.top - lastY) > Math.max(10, t.box.height())) s.append('\n');
                else s.append(' ');
            }
            s.append(x);
            lastY = t.box.top;
        }
        return s.toString().trim();
    }

    private static String stripLeadingLineNo(String s) {
        return s == null ? "" : s.replaceFirst("^\\s*0{0,2}\\d{1,2}[.、:]?\\s*", "").trim();
    }

    private static String clean(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '０' && ch <= '９') ch = (char) ('0' + (ch - '０'));
            else if (ch == '／') ch = '/';
            else if (ch == '－' || ch == '—' || ch == '–') ch = '-';
            else if (ch == '：') ch = ':';
            else if (ch == '，') ch = ',';
            out.append(ch);
        }
        return out.toString().replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String normalizeNumeric(String s) {
        String x = clean(s);
        x = x.replaceAll("(?<=\\d)[Oo](?=\\d)", "0");
        x = x.replaceAll("(?<=\\d)[lI](?=\\d)", "1");
        return x;
    }

    private static String compactSpaces(String s) { return s == null ? "" : s.replaceAll("\\s+", "").trim(); }
}
