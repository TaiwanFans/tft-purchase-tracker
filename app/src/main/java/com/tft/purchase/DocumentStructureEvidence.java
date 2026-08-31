package com.tft.purchase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts loose OCR boxes into deterministic document-structure evidence.
 * v2.0.20 adds a fixed-template path for the company's recurring purchase-order form.
 */
public final class DocumentStructureEvidence {
    private DocumentStructureEvidence() {}

    private static final Pattern ENTRY = Pattern.compile(
            "\\[engine=([^ ]+) tile=([^ ]+) region=([^ ]+) gx=([0-9.]+) gy=([0-9.]+) gw=([0-9.]+) gh=([0-9.]+) conf=([0-9.]+) zoom=([^]]+)\\] (.*)");

    private static final String[] HEADER_WORDS = {
            "項次", "品名", "規格", "數量", "單位", "單價", "小計", "交貨日期", "備註"
    };

    private static final class E {
        String engine, tile, region, text;
        float x, y, w, h, conf;
        float cx() { return x + w / 2f; }
        float cy() { return y + h / 2f; }
    }

    public static String enrich(String correctedOcrPath, String evidence) {
        String raw = evidence == null ? "" : evidence;
        List<E> entries = parse(raw);
        if (entries.isEmpty()) return raw + "\n[DOCUMENT_STRUCTURE_V220]\nno_coordinate_entries=true\n";

        TableGridDetector.Grid grid = TableGridDetector.detect(correctedOcrPath);
        StringBuilder out = new StringBuilder(raw);
        appendHeaderRoles(out, entries);
        appendTableGrid(out, grid, entries);
        appendFixedTemplate(out, grid, entries);
        return out.toString();
    }

    private static void appendHeaderRoles(StringBuilder out, List<E> entries) {
        List<E> headers = new ArrayList<>();
        // This fixed form's actual header lives above ~14.5% of the corrected page.
        // Do not call first item rows "HEADER" merely because OCR came from H1/H2 tiles.
        for (E e : entries) if (e.cy() < 0.145f) headers.add(e);
        Collections.sort(headers, Comparator.comparingDouble(E::cy).thenComparingDouble(E::cx));

        List<List<E>> rows = clusterRows(headers, 0.015f);
        out.append("\n[HEADER_ROLE_V220]\n");
        out.append("rule=固定採購單：先辨識供應廠商/廠商地址與右側採購單號/採購日期；頁首我方公司永遠不是 vendor。\n");
        int rid = 0;
        for (List<E> row : rows) {
            rid++;
            String text = joinRow(row);
            if (text.isEmpty()) continue;
            String compact = compact(text);
            if (containsAny(compact, "供應廠商", "供應商")) {
                out.append("role=SUPPLIER_ANCHOR row=").append(rid).append(" text=").append(text).append('\n');
            }
            if (containsAny(compact, "廠商地址", "供應商地址")) {
                out.append("role=SUPPLIER_ADDRESS_ANCHOR row=").append(rid).append(" text=").append(text).append('\n');
            }
            if (containsAnySelfCompany(row)) {
                out.append("role=OWN_COMPANY_PRESENT row=").append(rid).append(" text=").append(text).append('\n');
            }
            if (containsAny(compact, "採購單號", "採購單號碼")) {
                out.append("role=DOCUMENT_NUMBER_ANCHOR row=").append(rid).append(" text=").append(text).append('\n');
            }
            if (containsAny(compact, "採購日期", "日期")) {
                out.append("role=DOCUMENT_DATE_ANCHOR row=").append(rid).append(" text=").append(text).append('\n');
            }
        }
        out.append("[/HEADER_ROLE_V220]\n");
    }

    private static void appendTableGrid(StringBuilder out, TableGridDetector.Grid grid, List<E> entries) {
        out.append("\n[TABLE_GRID_V220]\n").append(grid.summary()).append('\n');
        if (!grid.reliable) {
            out.append("rule=表格線不足，維持 OCR 座標證據；禁止跨列推測。\n[/TABLE_GRID_V220]\n");
            return;
        }

        Map<Integer, Map<Integer, List<E>>> cells = bindCells(grid, entries);
        int headerRow = findHeaderRow(cells);
        Map<Integer, String> columnMap = inferColumnMap(cells.get(headerRow), grid.columnCount());
        out.append("row_binding_strict=true header_row=").append(headerRow)
                .append(" column_count=").append(grid.columnCount())
                .append(" row_count=").append(grid.rowCount()).append('\n');
        out.append("rule=同一品項只能使用同一 grid_row；禁止從其他列借數量/單價/小計/交貨日期。\n");
        out.append("rule=數字若落在品名規格 cell，不得當 quantity；例如 220V、60HZ、IP55、6英吋都屬規格。\n");
        out.append("column_map=");
        for (int c = 0; c < grid.columnCount(); c++) {
            if (c > 0) out.append('|');
            out.append(c).append(':').append(columnMap.getOrDefault(c, "unknown"));
        }
        out.append('\n');

        List<Integer> rowIds = new ArrayList<>(cells.keySet());
        Collections.sort(rowIds);
        int emittedRows = 0;
        for (int r : rowIds) {
            if (emittedRows++ > 70) break;
            Map<Integer, List<E>> row = cells.get(r);
            String lineCandidate = findLineCandidate(row, columnMap);
            out.append("ROW grid_row=").append(r);
            if (!lineCandidate.isEmpty()) out.append(" line_candidate=").append(lineCandidate);
            for (int c = 0; c < grid.columnCount(); c++) {
                String semantic = columnMap.getOrDefault(c, "unknown");
                String cellText = scalarSemantic(semantic) ? bestScalarCell(row == null ? null : row.get(c), semantic)
                        : joinCell(row == null ? null : row.get(c));
                if (cellText.isEmpty()) continue;
                out.append(" | c").append(c).append('(').append(semantic).append(")=").append(cellText);
            }
            out.append('\n');
        }
        out.append("[/TABLE_GRID_V220]\n");
    }

    /** Strong deterministic evidence for the only form family the user says will be uploaded. */
    private static void appendFixedTemplate(StringBuilder out, TableGridDetector.Grid grid, List<E> entries) {
        out.append("\n[FIXED_PURCHASE_ORDER_V220]\n");
        if (!grid.fixedPurchaseTemplate || grid.columnCount() != 8) {
            out.append("matched=false\n[/FIXED_PURCHASE_ORDER_V220]\n");
            return;
        }

        List<List<E>> topRows = clusterRows(filter(entries, 0f, 0.22f), 0.015f);
        String vendor = "", address = "", orderNo = "", purchaseDate = "";
        int anchors = 0;
        for (List<E> row : topRows) {
            String text = clean(joinRow(row));
            String c = compact(text);
            if (containsAny(c, "供應廠商", "供應商")) {
                anchors++;
                String v = afterAnchor(text, "供應廠商", "供應商");
                if (!v.isEmpty() && !AiResultValidator.isSelfCompany(v)) vendor = cleanupVendor(v);
            } else if (vendor.isEmpty() && rowAvgY(row) > 0.055f && rowAvgY(row) < 0.135f) {
                // Anchor OCR can be imperfect; the supplier row still contains a company and usually a (CB/CD/CE...) code.
                String v = cleanupVendor(text);
                if (looksLikeCounterparty(v) && !AiResultValidator.isSelfCompany(v)) vendor = v;
            }
            if (containsAny(c, "廠商地址", "供應商地址")) {
                anchors++;
                address = cleanupAddress(afterAnchor(text, "廠商地址", "供應商地址"));
            }
            if (containsAny(c, "採購單號", "採購單號碼")) {
                anchors++;
                orderNo = findOrderNo(text);
            } else if (orderNo.isEmpty() && rowRightWeighted(row)) {
                orderNo = findOrderNo(text);
            }
            if (containsAny(c, "採購日期")) {
                anchors++;
                purchaseDate = findDate(text);
            }
        }
        float confidence = Math.min(0.99f, 0.72f + Math.min(4, anchors) * 0.055f - Math.min(0.08f, grid.templateError * 0.20f));
        out.append(String.format(Locale.US, "matched=true confidence=%.3f formula=0.72+anchor_score-grid_error\n", confidence));
        out.append("columns=line_no|description_specification|quantity|unit|unit_price|subtotal|delivery_date|note\n");
        out.append("FIXED_HEADER\tvendor=").append(tabSafe(vendor))
                .append("\tlocation=").append(tabSafe(address))
                .append("\torder_no=").append(tabSafe(orderNo))
                .append("\tpurchase_date=").append(tabSafe(purchaseDate)).append('\n');

        Map<Integer, Map<Integer, List<E>>> cells = bindCells(grid, entries);
        int headerRow = findHeaderRow(cells);
        List<Integer> ids = new ArrayList<>(cells.keySet());
        Collections.sort(ids);
        for (int r : ids) {
            if (headerRow >= 0 && r <= headerRow) continue;
            Map<Integer, List<E>> row = cells.get(r);
            String lineNo = digitsOnly(bestScalarCell(row == null ? null : row.get(0), "line_no"));
            if (lineNo.length() < 1 || lineNo.length() > 4) continue;
            String description = joinCell(row.get(1));
            if (description.isEmpty()) continue;
            String quantity = bestScalarCell(row.get(2), "quantity");
            String unit = bestScalarCell(row.get(3), "unit");
            String unitPrice = bestScalarCell(row.get(4), "unit_price");
            String subtotal = bestScalarCell(row.get(5), "subtotal");
            String delivery = bestScalarCell(row.get(6), "delivery_date");
            String note = bestScalarCell(row.get(7), "note");
            out.append("FIXED_ROW\tline_no=").append(tabSafe(lineNo))
                    .append("\tdescription=").append(tabSafe(description))
                    .append("\tquantity=").append(tabSafe(quantity))
                    .append("\tunit=").append(tabSafe(unit))
                    .append("\tunit_price=").append(tabSafe(unitPrice))
                    .append("\tsubtotal=").append(tabSafe(subtotal))
                    .append("\tdelivery_date=").append(tabSafe(delivery))
                    .append("\tnote=").append(tabSafe(note)).append('\n');
            if (compact(description).contains("以下空白")) break;
        }
        out.append("rule=FIXED_ROW 為固定版型格線硬綁結果；MiniCPM 不得跨列改寫數字。\n");
        out.append("[/FIXED_PURCHASE_ORDER_V220]\n");
    }

    private static Map<Integer, Map<Integer, List<E>>> bindCells(TableGridDetector.Grid grid, List<E> entries) {
        Map<Integer, Map<Integer, List<E>>> cells = new LinkedHashMap<>();
        for (E e : entries) {
            int r = grid.row(e.cy());
            int c = grid.column(e.cx());
            if (r < 0 || c < 0) continue;
            cells.computeIfAbsent(r, k -> new LinkedHashMap<>())
                    .computeIfAbsent(c, k -> new ArrayList<>()).add(e);
        }
        return cells;
    }

    private static int findHeaderRow(Map<Integer, Map<Integer, List<E>>> cells) {
        int bestRow = -1, bestScore = 0;
        for (Map.Entry<Integer, Map<Integer, List<E>>> row : cells.entrySet()) {
            StringBuilder b = new StringBuilder();
            for (List<E> es : row.getValue().values()) b.append(joinCell(es));
            String s = compact(b.toString());
            int score = 0;
            for (String w : HEADER_WORDS) if (s.contains(w)) score++;
            if (score > bestScore) { bestScore = score; bestRow = row.getKey(); }
        }
        return bestScore >= 2 ? bestRow : -1;
    }

    private static Map<Integer, String> inferColumnMap(Map<Integer, List<E>> header, int count) {
        Map<Integer, String> out = new HashMap<>();
        if (header != null) {
            for (Map.Entry<Integer, List<E>> cell : header.entrySet()) {
                String s = compact(joinCell(cell.getValue()));
                String label = mapHeaderLabel(s);
                if (!label.isEmpty()) out.put(cell.getKey(), label);
            }
        }
        // User's fixed purchase order is exactly 8 semantic columns.
        if (count == 8) {
            putIfMissing(out, 0, "line_no");
            putIfMissing(out, 1, "description_specification");
            putIfMissing(out, 2, "quantity");
            putIfMissing(out, 3, "unit");
            putIfMissing(out, 4, "unit_price");
            putIfMissing(out, 5, "subtotal");
            putIfMissing(out, 6, "delivery_date");
            putIfMissing(out, 7, "note");
        }
        return out;
    }

    private static String mapHeaderLabel(String s) {
        if (containsAny(s, "項次", "項號", "序號")) return "line_no";
        if (containsAny(s, "品名", "規格", "明細")) return "description_specification";
        if (s.contains("數量")) return "quantity";
        if (s.contains("單位")) return "unit";
        if (s.contains("單價")) return "unit_price";
        if (containsAny(s, "小計", "金額")) return "subtotal";
        if (containsAny(s, "交貨日期", "交期")) return "delivery_date";
        if (s.contains("備註")) return "note";
        return "";
    }

    private static boolean scalarSemantic(String s) {
        return "line_no".equals(s) || "quantity".equals(s) || "unit".equals(s)
                || "unit_price".equals(s) || "subtotal".equals(s)
                || "delivery_date".equals(s) || "note".equals(s);
    }

    /** Choose one scalar candidate instead of concatenating conflicting OCR values in the same cell. */
    private static String bestScalarCell(List<E> es, String semantic) {
        if (es == null || es.isEmpty()) return "";
        Map<String, Float> scores = new LinkedHashMap<>();
        Map<String, String> originals = new LinkedHashMap<>();
        for (E e : es) {
            String v = clean(e.text);
            if (v.isEmpty()) continue;
            if ("delivery_date".equals(semantic)) {
                String d = findDate(v);
                if (!d.isEmpty()) v = d;
            }
            String key = scalarNorm(v);
            if (key.isEmpty()) continue;
            float score = Math.max(0.05f, e.conf);
            if (e.engine != null && e.engine.contains("PP") && e.engine.contains("ML")) score += 0.42f;
            if ("quantity".equals(semantic) || "unit_price".equals(semantic) || "subtotal".equals(semantic)) {
                if (containsDigit(v)) score += 0.12f;
            }
            if ("delivery_date".equals(semantic) && !findDate(v).isEmpty()) score += 0.30f;
            scores.put(key, scores.getOrDefault(key, 0f) + score);
            originals.put(key, v);
        }
        String best = ""; float bestScore = -1f;
        for (Map.Entry<String, Float> x : scores.entrySet()) {
            if (x.getValue() > bestScore) { bestScore = x.getValue(); best = originals.get(x.getKey()); }
        }
        return best == null ? "" : best;
    }

    private static String findLineCandidate(Map<Integer, List<E>> row, Map<Integer, String> colMap) {
        if (row == null) return "";
        for (Map.Entry<Integer, String> m : colMap.entrySet()) {
            if (!"line_no".equals(m.getValue())) continue;
            String s = digitsOnly(bestScalarCell(row.get(m.getKey()), "line_no"));
            if (s.length() >= 1 && s.length() <= 4) return s;
        }
        return "";
    }

    private static List<E> parse(String raw) {
        List<E> out = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            Matcher m = ENTRY.matcher(line.trim());
            if (!m.matches()) continue;
            E e = new E();
            e.engine = m.group(1); e.tile = m.group(2); e.region = m.group(3);
            e.x = f(m.group(4)); e.y = f(m.group(5)); e.w = f(m.group(6)); e.h = f(m.group(7));
            e.conf = f(m.group(8)); e.text = clean(m.group(10));
            if (!e.text.isEmpty()) out.add(e);
        }
        return out;
    }

    private static List<E> filter(List<E> in, float minY, float maxY) {
        List<E> out = new ArrayList<>();
        for (E e : in) if (e.cy() >= minY && e.cy() <= maxY) out.add(e);
        Collections.sort(out, Comparator.comparingDouble(E::cy).thenComparingDouble(E::cx));
        return out;
    }

    private static List<List<E>> clusterRows(List<E> entries, float tolerance) {
        List<List<E>> out = new ArrayList<>();
        for (E e : entries) {
            List<E> row = null;
            for (List<E> r : out) {
                float avg = rowAvgY(r);
                if (Math.abs(avg - e.cy()) <= tolerance) { row = r; break; }
            }
            if (row == null) { row = new ArrayList<>(); out.add(row); }
            row.add(e);
        }
        for (List<E> row : out) Collections.sort(row, Comparator.comparingDouble(E::cx));
        return out;
    }

    private static float rowAvgY(List<E> row) {
        float avg = 0f;
        if (row == null || row.isEmpty()) return 0f;
        for (E x : row) avg += x.cy();
        return avg / row.size();
    }

    private static boolean rowRightWeighted(List<E> row) {
        if (row == null || row.isEmpty()) return false;
        float x = 0f;
        for (E e : row) x += e.cx();
        return x / row.size() > 0.58f;
    }

    private static String joinRow(List<E> row) {
        StringBuilder b = new StringBuilder();
        for (E e : row) {
            if (b.length() > 0) b.append(" | ");
            b.append(e.text);
        }
        return b.toString();
    }

    private static String joinCell(List<E> es) {
        if (es == null || es.isEmpty()) return "";
        List<E> sorted = new ArrayList<>(es);
        Collections.sort(sorted, Comparator.comparingDouble(E::cy).thenComparingDouble(E::cx));
        Set<String> seen = new LinkedHashSet<>();
        for (E e : sorted) {
            String s = clean(e.text);
            if (!s.isEmpty()) seen.add(s);
        }
        StringBuilder b = new StringBuilder();
        for (String s : seen) {
            if (b.length() > 0) b.append(' ');
            b.append(s);
        }
        return b.toString();
    }

    private static String afterAnchor(String row, String... anchors) {
        String s = clean(row).replace(" | ", " ");
        for (String a : anchors) {
            int p = compact(s).indexOf(compact(a));
            if (p < 0) continue;
            // Use the first colon if present; fixed form prints label:value.
            int colon = Math.max(s.indexOf('：'), s.indexOf(':'));
            if (colon >= 0 && colon + 1 < s.length()) return clean(s.substring(colon + 1));
            int literal = s.indexOf(a);
            if (literal >= 0 && literal + a.length() < s.length()) return clean(s.substring(literal + a.length()));
        }
        return "";
    }

    private static String cleanupVendor(String s) {
        String x = clean(s).replace(" | ", " ");
        x = x.replaceFirst("^.*?(供應廠商|供應商)\\s*[：:]?", "");
        x = x.replaceFirst("\\s+(廠商地址|電話號碼|電話|FAX).*", "");
        return clean(x);
    }

    private static String cleanupAddress(String s) {
        String x = clean(s).replace(" | ", " ");
        x = x.replaceFirst("^.*?(廠商地址|供應商地址)\\s*[：:]?", "");
        x = x.replaceFirst("\\s+(電話號碼|電話|FAX).*", "");
        return clean(x);
    }

    private static boolean looksLikeCounterparty(String s) {
        String c = compact(s);
        return (c.contains("公司") || c.contains("企業社") || c.contains("工業社") || c.contains("工作處")
                || c.matches(".*\\(C[A-Z0-9]{2,6}\\).*")) && !containsAny(c, "TEL", "Email", "採購單");
    }

    private static String findOrderNo(String s) {
        Matcher m = Pattern.compile("(?<!\\d)(20\\d{10})(?!\\d)").matcher(s == null ? "" : s.replaceAll("[ OIlSB]", ""));
        return m.find() ? m.group(1) : "";
    }

    private static String findDate(String s) {
        if (s == null) return "";
        Matcher m = Pattern.compile("(?<!\\d)(20\\d{2})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)").matcher(s);
        if (m.find()) {
            try { return String.format(Locale.TAIWAN, "%04d-%02d-%02d", Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))); }
            catch (Throwable ignored) {}
        }
        Matcher compact = Pattern.compile("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})(?!\\d)").matcher(s.replaceAll("\\s+", ""));
        if (compact.find()) {
            try { return String.format(Locale.TAIWAN, "%04d-%02d-%02d", Integer.parseInt(compact.group(1)), Integer.parseInt(compact.group(2)), Integer.parseInt(compact.group(3))); }
            catch (Throwable ignored) {}
        }
        return "";
    }

    private static String scalarNorm(String s) {
        return clean(s).toUpperCase(Locale.TAIWAN).replaceAll("[\\s　,，]", "").replace('。', '.');
    }

    private static String digitsOnly(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }
    private static String tabSafe(String s) { return clean(s).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '); }

    private static boolean containsAnySelfCompany(List<E> row) {
        for (E e : row) if (AiResultValidator.isSelfCompany(e.text)) return true;
        return false;
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static void putIfMissing(Map<Integer, String> map, int k, String v) {
        if (!map.containsKey(k)) map.put(k, v);
    }

    private static boolean containsDigit(String s) { return s != null && s.matches(".*\\d.*"); }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String compact(String s) {
        return clean(s).replaceAll("\\s+", "").replace("：", ":").replace("|", "");
    }

    private static float f(String s) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return 0f; }
    }
}
