package com.tft.purchase;

import android.content.Context;

/**
 * High-accuracy OCR entry point.
 * V2.0.15: document correction -> enlarged numbered tiles -> dual OCR -> global-coordinate reassembly.
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

    public static void recognize(Context context, String sourcePath, Callback callback) {
        final DocumentPreprocessor.Prepared prepared;
        try {
            prepared = DocumentPreprocessor.prepare(context.getApplicationContext(), sourcePath);
        } catch (Throwable t) {
            callback.onFailure("採購單影像前處理失敗：" + safe(t));
            return;
        }

        ZoomTiledOcrBridge.recognize(context, prepared.ocrPath, new ZoomTiledOcrBridge.Callback() {
            @Override public void onSuccess(String evidence) {
                callback.onSuccess(new Result(evidence, prepared));
            }

            @Override public void onFailure(String error) {
                fallbackFullPage(context, prepared, callback, error);
            }
        });
    }

    private static void fallbackFullPage(Context context, DocumentPreprocessor.Prepared prepared,
                                         Callback callback, String tiledError) {
        final State state = new State();
        PaddleOcrBridge.recognize(context, prepared.ocrPath, new PaddleOcrCallback() {
            @Override public void onSuccess(String s) { synchronized (state) { state.pp=s==null?"":s; state.done++; finish(); } }
            @Override public void onFailure(String e) { synchronized (state) { state.ppErr=e==null?"":e; state.done++; finish(); } }
            private void finish(){ finishFallback(state, prepared, callback, tiledError); }
        });
        MlKitOcrBridge.recognize(context, prepared.ocrPath, new MlKitOcrBridge.Callback() {
            @Override public void onSuccess(String s) { synchronized (state) { state.ml=s==null?"":s; state.done++; finishFallback(state, prepared, callback, tiledError); } }
            @Override public void onFailure(String e) { synchronized (state) { state.mlErr=e==null?"":e; state.done++; finishFallback(state, prepared, callback, tiledError); } }
        });
    }

    private static final class State {
        String pp="",ml="",ppErr="",mlErr=""; int done=0; boolean delivered=false;
    }

    private static void finishFallback(State s, DocumentPreprocessor.Prepared prepared, Callback callback, String tiledError) {
        if (s.done < 2 || s.delivered) return;
        s.delivered = true;
        if (s.pp.trim().isEmpty() && s.ml.trim().isEmpty()) {
            prepared.cleanup();
            callback.onFailure("分區 OCR 與整頁 OCR 都失敗。分區：" + tiledError + "；PP：" + s.ppErr + "；ML：" + s.mlErr);
            return;
        }
        StringBuilder ev = new StringBuilder();
        ev.append("[OCR_FALLBACK_FULL_PAGE]\n")
                .append("reason=").append(tiledError == null ? "分區 OCR 未完成" : tiledError).append('\n')
                .append("rule=此為備援結果，重要欄位若不清楚必須人工確認。\n");
        if (!s.pp.isEmpty()) ev.append(s.pp).append('\n');
        if (!s.ml.isEmpty()) ev.append(s.ml).append('\n');
        callback.onSuccess(new Result(ev.toString(), prepared));
    }

    private static String safe(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
