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
 * Header role hints and wired-table row/cell bindings are appended without replacing raw OCR.
 */
public final class DocumentStructureEvidence {
    private DocumentStructureEvidence() {}

    private static final Pattern ENTRY = Pattern.compile(
            "\\[engine=([^ ]+) tile=([^ ]+) region=([^ ]+) gx=([0-9.]+) gy=([0-9.]+) gw=([0-9.]+) gh=([0-9.]+) conf=([0-9.]+) zoom=([^]]+)\\] (.*)");

    private static final String[] HEADER_WORDS = {
            "項次", "品名", "規格", "數量", "單位", "單價", "小計", "備註"
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
        if (entries.isEmpty()) return raw + "\n[DOCUMENT_STRUCTURE_V219]\nno_coordinate_entries=true\n";

        StringBuilder out = new StringBuilder(raw);
        appendHeaderRoles(out, entries);
        appendTableGrid(out, correctedOcrPath, entries);
        return out.toString();
    }

    private static void appendHeaderRoles(StringBuilder out, List<E> entries) {
        List<E> headers = new ArrayList<>();
        for (E e : entries) if (e.region != null && e.region.startsWith("HEADER")) headers.add(e);
        Collections.sort(headers, Comparator.comparingDouble(E::cy).thenComparingDouble(E::cx));

        List<List<E>> rows = clusterRows(headers, 0.018f);
        out.append("\n[HEADER_ROLE_V219]\n");
        out.append("rule=先辨識開立方/收件方/TO，再決定 counterparty；不得把我方公司直接當 vendor。\n");
        int rid = 0;
        for (List<E> row : rows) {
            rid++;
            String text = joinRow(row);
            if (text.isEmpty()) continue;
            String compact = compact(text);
            if (containsToAnchor(compact)) {
                out.append("role=COUNTERPARTY_ANCHOR anchor=TO row=").append(rid)
                        .append(" text=").append(text).append('\n');
            }
            if (containsAnySelfCompany(row)) {
                out.append("role=OWN_COMPANY_PRESENT row=").append(rid)
                        .append(" text=").append(text).append('\n');
            }
            if (containsAny(compact, "單據號碼", "採購單號", "訂單號碼", "報價單號")) {
                out.append("role=DOCUMENT_NUMBER_ANCHOR row=").append(rid)
                        .append(" text=").append(text).append('\n');
            }
            if (containsAny(compact, "有效日期", "報價有效")) {
                out.append("role=VALID_UNTIL_ANCHOR row=").append(rid)
                        .append(" text=").append(text).append('\n');
            } else if (containsAny(compact, "日期", "採購日期", "報價日期")) {
                out.append("role=DOCUMENT_DATE_ANCHOR row=").append(rid)
                        .append(" text=").append(text).append('\n');
            }
        }
        out.append("[/HEADER_ROLE_V219]\n");
    }

    private static void appendTableGrid(StringBuilder out, String correctedOcrPath, List<E> entries) {
        TableGridDetector.Grid grid = TableGridDetector.detect(correctedOcrPath);
        out.append("\n[TABLE_GRID_V219]\n").append(grid.summary()).append('\n');
        if (!grid.reliable) {
            out.append("rule=表格線不足，維持 OCR 座標證據；禁止跨列推測。\n[/TABLE_GRID_V219]\n");
            return;
        }

        Map<Integer, Map<Integer, List<E>>> cells = new LinkedHashMap<>();
        for (E e : entries) {
            int r = grid.row(e.cy());
            int c = grid.column(e.cx());
            if (r < 0 || c < 0) continue;
            cells.computeIfAbsent(r, k -> new LinkedHashMap<>())
                    .computeIfAbsent(c, k -> new ArrayList<>()).add(e);
        }

        int headerRow = findHeaderRow(cells);
        Map<Integer, String> columnMap = inferColumnMap(cells.get(headerRow), grid.columnCount());
        out.append("row_binding_strict=true header_row=").append(headerRow)
                .append(" column_count=").append(grid.columnCount())
                .append(" row_count=").append(grid.rowCount()).append('\n');
        out.append("rule=同一品項只能使用同一 grid_row 的 cell；禁止從其他 grid_row 借數量/單價/小計。\n");
        out.append("rule=數字若落在品名/規格 cell，不得當成 quantity；例如『6英吋』中的 6 仍屬規格。\n");
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
                String cellText = joinCell(row == null ? null : row.get(c));
                if (cellText.isEmpty()) continue;
                out.append(" | c").append(c).append('(')
                        .append(columnMap.getOrDefault(c, "unknown")).append(")=")
                        .append(cellText);
            }
            out.append('\n');
        }
        out.append("[/TABLE_GRID_V219]\n");
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
        // Common 7-column purchase/quotation table: 項次 | 品名/規格 | 數量 | 單位 | 單價 | 小計 | 備註.
        if (count == 7) {
            putIfMissing(out, 0, "line_no");
            putIfMissing(out, 1, "description_specification");
            putIfMissing(out, 2, "quantity");
            putIfMissing(out, 3, "unit");
            putIfMissing(out, 4, "unit_price");
            putIfMissing(out, 5, "subtotal");
            putIfMissing(out, 6, "note");
        }
        return out;
    }

    private static String mapHeaderLabel(String s) {
        if (containsAny(s, "項次", "項號", "序號")) return "line_no";
        if (containsAny(s, "品名", "規格")) return "description_specification";
        if (s.contains("數量")) return "quantity";
        if (s.contains("單位")) return "unit";
        if (s.contains("單價")) return "unit_price";
        if (containsAny(s, "小計", "金額")) return "subtotal";
        if (s.contains("備註")) return "note";
        return "";
    }

    private static String findLineCandidate(Map<Integer, List<E>> row, Map<Integer, String> colMap) {
        if (row == null) return "";
        for (Map.Entry<Integer, String> m : colMap.entrySet()) {
            if (!"line_no".equals(m.getValue())) continue;
            String s = joinCell(row.get(m.getKey())).replaceAll("[^0-9]", "");
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

    private static List<List<E>> clusterRows(List<E> entries, float tolerance) {
        List<List<E>> out = new ArrayList<>();
        for (E e : entries) {
            List<E> row = null;
            for (List<E> r : out) {
                float avg = 0f;
                for (E x : r) avg += x.cy();
                avg /= Math.max(1, r.size());
                if (Math.abs(avg - e.cy()) <= tolerance) { row = r; break; }
            }
            if (row == null) { row = new ArrayList<>(); out.add(row); }
            row.add(e);
        }
        for (List<E> row : out) Collections.sort(row, Comparator.comparingDouble(E::cx));
        return out;
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

    private static boolean containsAnySelfCompany(List<E> row) {
        for (E e : row) if (AiResultValidator.isSelfCompany(e.text)) return true;
        return false;
    }

    private static boolean containsToAnchor(String s) {
        String x = s.toUpperCase(Locale.TAIWAN);
        return x.startsWith("TO:") || x.startsWith("TO：") || x.equals("TO")
                || x.contains("|TO:") || x.contains("|TO：") || x.contains("TO: ");
    }

    private static boolean containsAny(String s, String... words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    private static void putIfMissing(Map<Integer, String> map, int k, String v) {
        if (!map.containsKey(k)) map.put(k, v);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\u3000', ' ').replaceAll("[ \\t]+", " ").trim();
    }

    private static String compact(String s) {
        return clean(s).replaceAll("\\s+", "").replace("：", ":");
    }

    private static float f(String s) {
        try { return Float.parseFloat(s); } catch (Throwable t) { return 0f; }
    }
}
