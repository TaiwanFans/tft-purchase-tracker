package com.tft.purchase;

import android.content.Context;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V2.0.13 dual-OCR pipeline:
 * document correction -> PP-OCRv6 Small (primary) + ML Kit Chinese (secondary) -> layout evidence.
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
        String mlkit = "";
        String paddleError = "";
        String mlkitError = "";
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

        MlKitOcrBridge.recognize(context, prepared.ocrPath, new MlKitOcrBridge.Callback() {
            @Override public void onSuccess(String text) {
                synchronized (state) {
                    state.mlkit = text == null ? "" : text;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }

            @Override public void onFailure(String error) {
                synchronized (state) {
                    state.mlkitError = error == null ? "ML Kit 未知錯誤" : error;
                    state.done++;
                    finishIfReady(state, prepared, callback);
                }
            }
        });
    }

    private static void finishIfReady(State state, DocumentPreprocessor.Prepared prepared, Callback callback) {
        if (state.done < 2 || state.delivered) return;
        state.delivered = true;
        boolean ppOk = !state.paddle.trim().isEmpty();
        boolean mlOk = !state.mlkit.trim().isEmpty();
        if (!ppOk && !mlOk) {
            prepared.cleanup();
            callback.onFailure("雙 OCR 都失敗。PP-OCR：" + state.paddleError + "；ML Kit：" + state.mlkitError);
            return;
        }

        StringBuilder evidence = new StringBuilder();
        evidence.append("[OCR_PIPELINE]\n")
                .append("primary=PP-OCRv6-small; secondary=Google-ML-Kit-Chinese; layout_coordinates=normalized\n")
                .append("rule=PP-OCR是主要文字證據；ML Kit只用於交叉核對。兩者衝突時必須看圖片，不能猜。\n\n");
        if (ppOk) evidence.append(state.paddle).append('\n');
        else evidence.append("[PP-OCR_ERROR] ").append(state.paddleError).append("\n\n");
        if (mlOk) evidence.append(state.mlkit).append('\n');
        else evidence.append("[MLKIT_ERROR] ").append(state.mlkitError).append("\n\n");

        Set<String> ppNums = extractNumbers(visibleText(state.paddle));
        Set<String> mlNums = extractNumbers(visibleText(state.mlkit));
        Set<String> both = new LinkedHashSet<>(ppNums);
        both.retainAll(mlNums);
        Set<String> ppOnly = new LinkedHashSet<>(ppNums);
        ppOnly.removeAll(mlNums);
        Set<String> mlOnly = new LinkedHashSet<>(mlNums);
        mlOnly.removeAll(ppNums);

        evidence.append("[OCR_NUMBER_CROSSCHECK]\n")
                .append("both=").append(both).append('\n')
                .append("pp_only=").append(ppOnly).append('\n')
                .append("mlkit_only=").append(mlOnly).append('\n')
                .append("如果重要數字只出現在單一 OCR，必須重新看圖片；看不清楚就 needs_confirmation。\n");
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
        while (m.find() && out.size() < 80) {
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
