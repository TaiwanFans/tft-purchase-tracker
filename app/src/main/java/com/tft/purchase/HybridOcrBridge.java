package com.tft.purchase;

import android.content.Context;

import java.io.File;

/**
 * High-accuracy OCR entry point.
 * V2.0.17: document correction -> H1/H2/R1-R4 enlarged tiles -> dual OCR -> global coordinates ->
 * representative high-resolution visual inspection sheet for the replaceable local AI provider.
 */
public final class HybridOcrBridge {
    private HybridOcrBridge() {}

    public interface Progress {
        void onProgress(int percent, String stage);
    }

    public static final class Result {
        public final String evidence;
        /** Representative high-resolution sheet plus OCR conflict/low-confidence close-ups. */
        public final String preparedImagePath;
        private final DocumentPreprocessor.Prepared prepared;
        private final String inspectionPath;

        Result(String evidence, DocumentPreprocessor.Prepared prepared, String inspectionPath) {
            this.evidence = evidence == null ? "" : evidence;
            this.prepared = prepared;
            this.inspectionPath = inspectionPath == null ? "" : inspectionPath;
            this.preparedImagePath = this.inspectionPath.isEmpty() && prepared != null
                    ? prepared.visionPath : this.inspectionPath;
        }

        public void cleanup() {
            if (prepared != null) prepared.cleanup();
            try {
                if (!inspectionPath.isEmpty() && (prepared == null ||
                        (!inspectionPath.equals(prepared.visionPath) && !inspectionPath.equals(prepared.ocrPath)))) {
                    new File(inspectionPath).delete();
                }
            } catch (Throwable ignored) {}
        }
    }

    public interface Callback {
        void onSuccess(Result result);
        void onFailure(String error);
    }

    public static void recognize(Context context, String sourcePath, Callback callback) {
        recognize(context, sourcePath, null, callback);
    }

    public static void recognize(Context context, String sourcePath, Progress progress, Callback callback) {
        final Context app = context.getApplicationContext();
        emitProgress(progress, 8, "正在讀取 EXIF 與校正照片方向");
        final DocumentPreprocessor.Prepared prepared;
        try {
            emitProgress(progress, 12, "正在找出紙張邊界、透視校正、去陰影與去噪");
            prepared = DocumentPreprocessor.prepare(app, sourcePath);
            emitProgress(progress, 20, "文件校正完成，準備切割 H1/H2/R1/R2/R3/R4");
        } catch (Throwable t) {
            callback.onFailure("採購單影像前處理失敗：" + safe(t));
            return;
        }

        ZoomTiledOcrBridge.recognize(app, prepared.ocrPath, new ZoomTiledOcrBridge.Callback() {
            @Override public void onProgress(int percent, String stage) {
                emitProgress(progress, percent, stage);
            }

            @Override public void onSuccess(String evidence) {
                emitProgress(progress, 87, "雙 OCR 座標與衝突資料已重組，正在準備 AI 高解析核對區");
                String enriched = evidence + "\n" + new RecognitionKnowledgeDb(app).buildPromptEvidence();
                String inspection = "";
                try {
                    inspection = AiInspectionImageBuilder.build(app, prepared.visionPath, evidence);
                } catch (Throwable t) {
                    // Safe fallback: AI may inspect the corrected page only if crop-sheet assembly fails.
                    enriched += "\n[AI_INSPECTION_FALLBACK] high-resolution crop builder failed: " + safe(t);
                    inspection = prepared.visionPath;
                }
                emitProgress(progress, 89, "OCR 完成；AI 正在核對固定高解析區＋衝突局部圖");
                callback.onSuccess(new Result(enriched, prepared, inspection));
            }

            @Override public void onFailure(String error) {
                fallbackFullPage(app, prepared, progress, callback, error);
            }
        });
    }

    private static void fallbackFullPage(Context context, DocumentPreprocessor.Prepared prepared,
                                         Progress progress, Callback callback, String tiledError) {
        emitProgress(progress, 70, "分區 OCR 未完成，啟用整頁雙 OCR 備援");
        final State state = new State();
        PaddleOcrBridge.recognize(context, prepared.ocrPath, new PaddleOcrCallback() {
            @Override public void onSuccess(String s) { synchronized (state) { state.pp=s==null?"":s; state.done++; finish(); } }
            @Override public void onFailure(String e) { synchronized (state) { state.ppErr=e==null?"":e; state.done++; finish(); } }
            private void finish(){ finishFallback(context, state, prepared, progress, callback, tiledError); }
        });
        MlKitOcrBridge.recognize(context, prepared.ocrPath, new MlKitOcrBridge.Callback() {
            @Override public void onSuccess(String s) { synchronized (state) { state.ml=s==null?"":s; state.done++; finishFallback(context, state, prepared, progress, callback, tiledError); } }
            @Override public void onFailure(String e) { synchronized (state) { state.mlErr=e==null?"":e; state.done++; finishFallback(context, state, prepared, progress, callback, tiledError); } }
        });
    }

    private static final class State {
        String pp="",ml="",ppErr="",mlErr=""; int done=0; boolean delivered=false;
    }

    private static void finishFallback(Context context, State s, DocumentPreprocessor.Prepared prepared,
                                       Progress progress, Callback callback, String tiledError) {
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
                .append("rule=此為備援結果；所有重要欄位若不清楚必須 NEEDS_CONFIRMATION，禁止猜。\n");
        if (!s.pp.isEmpty()) ev.append(s.pp).append('\n');
        if (!s.ml.isEmpty()) ev.append(s.ml).append('\n');
        ev.append(new RecognitionKnowledgeDb(context).buildPromptEvidence());
        emitProgress(progress, 86, "整頁雙 OCR 備援完成，重要欄位將強制人工確認");
        callback.onSuccess(new Result(ev.toString(), prepared, prepared.visionPath));
    }

    private static void emitProgress(Progress p, int percent, String stage) {
        if (p != null) p.onProgress(Math.max(1, Math.min(99, percent)), stage);
    }

    private static String safe(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
