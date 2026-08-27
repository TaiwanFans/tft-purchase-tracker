package com.tft.purchase;

import android.content.Context;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.0.14 high-accuracy OCR pipeline:
 * document correction -> PP-OCRv6 Medium on enhanced document
 * + Google ML Kit Chinese on corrected colour document
 * + Google ML Kit Chinese on enhanced document -> layout-aware evidence.
 */
public final class HybridOcrBridge {
    private HybridOcrBridge() {}

    public static final class Result {
        public final String evidence;
        public final String preparedImagePath;
        private final DocumentPreprocessor.Prepared prepared;

        Result(String evidence, DocumentPreprocessor.Prepared prepared) {
            this.evidence = evidence == null ? "" : evidence;
            this.prepared = prepared;
            this.preparedImagePath = prepared == null ? "" : prepared.visionPath;
        }

        public void cleanup() {
            if (prepared != null) prepared.cleanup();
        }
    }

    public interface Callback {
        void onSuccess(Result result);
        void onFailure(String error);
    }

    private static final class State {
        String paddle = "";
        String mlColor = "";
        String mlEnhanced = "";
        String paddleError = "";
        String mlColorError = "";
        String mlEnhancedError = "";
        int done = 0;
        boolean delivered = false;
    }

    public static void recognize(Context context, String sourcePath, Callback callback) {
        final DocumentPreprocessor.Prepared prepared;
        try {
            prepared = DocumentPreprocessor.prepare(context.getApplicationContext(), sourcePath);
        } catch (Throwable t) {
            callback.onFailure("採購單影像前處理失敗：" + safe(t));
            return;
        }

        final State state = new State();

        PaddleOcrBridge.recognize(context, prepared.ocrPath, new PaddleOcrCallback() {
            @Override public void onSuccess(String structuredEvidence) {
                synchronized (state) {
                    state.paddle = structuredEvidence == null ? "" : structuredEvidence;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }

            @Override public void onFailure(String error) {
                synchronized (state) {
                    state.paddleError = error == null ? "PP-OCR 未知錯誤" : error;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }
        });

        // Use a corrected colour image as an independent second opinion.
        MlKitOcrBridge.recognize(context, prepared.visionPath, new MlKitOcrBridge.Callback() {
            @Override public void onSuccess(String text) {
                synchronized (state) {
                    state.mlColor = text == null ? "" : text;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }

            @Override public void onFailure(String error) {
                synchronized (state) {
                    state.mlColorError = error == null ? "ML Kit 彩色版未知錯誤" : error;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }
        });

        // Run ML Kit once more on the OCR-enhanced image. This often recovers small digits/Chinese glyphs
        // that the colour pass misses; disagreements are explicitly surfaced to MiniCPM.
        MlKitOcrBridge.recognize(context, prepared.ocrPath, new MlKitOcrBridge.Callback() {
            @Override public void onSuccess(String text) {
                synchronized (state) {
                    state.mlEnhanced = text == null ? "" : text;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }

            @Override public void onFailure(String error) {
                synchronized (state) {
                    state.mlEnhancedError = error == null ? "ML Kit 強化版未知錯誤" : error;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }
        });
    }

    private static void finishIfReady(State state, DocumentPreprocessor.Prepared prepared, Callback callback) {
        if (state.done < 3 || state.delivered) return;
        state.delivered = true;

        boolean ppOk = !state.paddle.trim().isEmpty();
        boolean mlColorOk = !state.mlColor.trim().isEmpty();
        boolean mlEnhancedOk = !state.mlEnhanced.trim().isEmpty();
        boolean anyMl = mlColorOk || mlEnhancedOk;
        if (!ppOk && !anyMl) {
            prepared.cleanup();
            callback.onFailure("所有 OCR 都失敗。PP-OCR：" + state.paddleError
                    + "；ML Kit 彩色：" + state.mlColorError
                    + "；ML Kit 強化：" + state.mlEnhancedError);
            return;
        }

        StringBuilder evidence = new StringBuilder();
        evidence.append("[OCR_PIPELINE]\n")
                .append("primary=PP-OCRv6-medium; secondary=Google-ML-Kit-Chinese-color; tertiary=Google-ML-Kit-Chinese-enhanced; layout_coordinates=normalized\n")
                .append("rule=PP-OCR是主要字元證據；兩次ML Kit是不同影像版本的交叉核對。重要欄位若衝突，必須回看圖片，不得猜測。\n\n");

        if (ppOk) evidence.append(state.paddle).append('\n');
        else evidence.append("[PP-OCR_ERROR] ").append(state.paddleError).append("\n\n");

        if (mlColorOk) evidence.append("[MLKIT_COLOR_PASS]\n").append(state.mlColor).append('\n');
        else evidence.append("[MLKIT_COLOR_ERROR] ").append(state.mlColorError).append("\n\n");

        if (mlEnhancedOk) evidence.append("[MLKIT_ENHANCED_PASS]\n").append(state.mlEnhanced).append('\n');
        else evidence.append("[MLKIT_ENHANCED_ERROR] ").append(state.mlEnhancedError).append("\n\n");

        Set<String> ppNums = extractNumbers(visibleText(state.paddle));
        Set<String> mlColorNums = extractNumbers(visibleText(state.mlColor));
        Set<String> mlEnhancedNums = extractNumbers(visibleText(state.mlEnhanced));
        Set<String> mlUnion = new LinkedHashSet<>(mlColorNums);
        mlUnion.addAll(mlEnhancedNums);

        Set<String> ppAndMl = new LinkedHashSet<>(ppNums);
        ppAndMl.retainAll(mlUnion);
        Set<String> mlBoth = new LinkedHashSet<>(mlColorNums);
        mlBoth.retainAll(mlEnhancedNums);
        Set<String> ppOnly = new LinkedHashSet<>(ppNums);
        ppOnly.removeAll(mlUnion);
        Set<String> mlOnly = new LinkedHashSet<>(mlUnion);
        mlOnly.removeAll(ppNums);

        evidence.append("[OCR_NUMBER_CROSSCHECK]\n")
                .append("pp_and_any_mlkit=").append(ppAndMl).append('\n')
                .append("both_mlkit_passes=").append(mlBoth).append('\n')
                .append("pp_only=").append(ppOnly).append('\n')
                .append("mlkit_only=").append(mlOnly).append('\n')
                .append("重要數字若獲得PP-OCR與至少一個ML Kit一致結果，可信度較高；若只出現在單一路徑，必須看圖片再決定，無法確認就 needs_confirmation。\n");

        callback.onSuccess(new Result(evidence.toString(), prepared));
    }

    private static String visibleText(String structured) {
        if (structured == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : structured.split("\\n")) {
            int close = line.indexOf("] ");
            if (close >= 0 && close + 2 < line.length()) sb.append(line.substring(close + 2)).append(' ');
            else if (!line.startsWith("[") && !line.startsWith("engine=")) sb.append(line).append(' ');
        }
        return sb.toString();
    }

    private static Set<String> extractNumbers(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        Matcher m = Pattern.compile("(?<![A-Za-z0-9])\\d[\\d,./-]{0,17}(?![A-Za-z0-9])").matcher(text);
        while (m.find() && out.size() < 100) {
            String v = m.group().replace(",", "").trim();
            if (v.length() >= 2) out.add(v);
        }
        return out;
    }

    private static String safe(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
