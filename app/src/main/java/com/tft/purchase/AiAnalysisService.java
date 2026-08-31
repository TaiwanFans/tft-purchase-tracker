package com.tft.purchase;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.util.ArrayList;

/** Foreground service for document/OCR -> Firebase Gemini -> deterministic validation pipeline. */
public class AiAnalysisService extends Service {
    public static final String ACTION_UPDATE = "com.tft.purchase.AI_JOB_UPDATE";
    private static final String CHANNEL = "cloud_ai_analysis_v210";
    private static final int NOTIFICATION_ID = 8235708;
    private volatile boolean processing = false;
    private int created = 0;
    private int warnings = 0;

    /** Start a fresh job or append these photos to the currently-running persistent queue. */
    public static void start(Context context, ArrayList<String> imagePaths, long replaceId) {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        AiJobStore.enqueue(context, imagePaths, replaceId);
        Intent i = new Intent(context, AiAnalysisService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("準備 Gemini 分析排隊工作", 1, false));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (processing) return START_STICKY;
        AiJobStore.Snapshot snapshot = AiJobStore.get(this);
        if (!snapshot.running() || snapshot.queue.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        AiModelProvider provider = AiModelRegistry.active(this);
        if (!provider.isReady(this)) {
            String msg = provider.displayName() + " 尚未完成 Firebase 初始化，請確認 Firebase 設定";
            AiJobStore.error(this, msg);
            publish(msg, 100, true);
            stopSelf();
            return START_NOT_STICKY;
        }

        processing = true;
        created = snapshot.success;
        warnings = snapshot.warningCount;
        int resumeIndex = Math.max(0, Math.min(snapshot.queue.size(), snapshot.nextIndex));
        if (resumeIndex > 0) publish("背景服務已恢復，從排隊第 " + (resumeIndex + 1) + " 張繼續", overallFor(resumeIndex, 1), false);
        processNext(resumeIndex);
        return START_STICKY;
    }

    /** Always reload the queue before starting the next item, so newly-added/reordered waiting items take effect. */
    private void processNext(int index) {
        AiJobStore.Snapshot snapshot = AiJobStore.get(this);
        if (!snapshot.running()) {
            processing = false;
            stopSelf();
            return;
        }

        if (index >= snapshot.queue.size()) {
            AiJobStore.Snapshot verify = AiJobStore.get(this);
            if (index < verify.queue.size()) {
                processNext(index);
                return;
            }
            processing = false;
            AiJobStore.done(this, created, warnings);
            String msg;
            if (created <= 0) msg = "AI 分析結束，但沒有建立採購單";
            else if (warnings > 0) msg = "排隊完成：已建立 " + created + " 張；" + warnings + " 張有局部欄位需確認";
            else msg = "排隊完成：已建立 " + created + " 張採購單";
            publish(msg, 100, true);
            sendUpdate();
            stopSelf();
            return;
        }

        final AiJobStore.QueueItem item = snapshot.queue.get(index);
        final String originalPath = item.path;
        final int total = snapshot.queue.size();
        updateJob(index, 3, "第 " + (index + 1) + "/" + total + " 張｜準備文件辨識");

        HybridOcrBridge.recognize(this, originalPath, (percent, stage) -> {
            int itemProgress = 5 + Math.max(0, Math.min(75, percent * 75 / 100));
            String text = positionText(index) + "｜" + stage;
            updateJob(index, itemProgress, text);
        }, new HybridOcrBridge.Callback() {
            @Override public void onSuccess(HybridOcrBridge.Result result) {
                String modelImage = result.fullPageImagePath == null || result.fullPageImagePath.isEmpty()
                        ? originalPath : result.fullPageImagePath;
                runAiModel(index, item, originalPath, modelImage, result.evidence, result);
            }

            @Override public void onFailure(String error) {
                String evidence = "[OCR_ERROR] " + (error == null ? "雙 OCR 未完成" : error);
                updateJob(index, 78, positionText(index) + "｜OCR 未完成，改由 Gemini 讀取原圖並保留待確認標記");
                runAiModel(index, item, originalPath, originalPath, evidence, null);
            }
        });
    }

    private void runAiModel(int index, AiJobStore.QueueItem item,
                            String originalPath, String modelImagePath, String ocrEvidence,
                            HybridOcrBridge.Result hybridResult) {
        AiModelProvider provider = AiModelRegistry.active(this);
        provider.analyze(this, modelImagePath, ocrEvidence, (percent, stage) -> {
            int itemProgress = 82 + Math.max(0, Math.min(13, percent * 13 / 100));
            updateJob(index, itemProgress, positionText(index) + "｜" + stage);
        }, new AiModelCallback() {
            @Override public void onSuccess(String response) {
                try {
                    updateJob(index, 97, positionText(index) + "｜固定版型驗證：交易角色、8 欄表格、日期與金額");
                    PurchaseOcrParser.ParsedPurchase parsed = AiJsonParser.parse(response, ocrEvidence);
                    AiResultValidator.Validation validation = AiResultValidator.validateAndSanitize(parsed);
                    boolean reliable = validation.reliable;
                    long id = saveParsed(parsed, originalPath, item.replaceId, reliable, validation.message);
                    created++;
                    if (!reliable) warnings++;
                    AiJobStore.appendId(AiAnalysisService.this, id);
                    AiJobStore.checkpointNextIndex(AiAnalysisService.this, index + 1, created, warnings);
                    String stage = reliable
                            ? "第 " + (index + 1) + " 張已建立採購單 ✓"
                            : "第 " + (index + 1) + " 張已建立；只有標記欄位需要確認";
                    updateJob(index, 99, stage);
                } catch (Throwable t) {
                    try {
                        long id = savePlaceholder(originalPath, item.replaceId,
                                "Gemini 結果處理失敗：" + safeMessage(t), ocrEvidence);
                        created++;
                        warnings++;
                        AiJobStore.appendId(AiAnalysisService.this, id);
                        AiJobStore.checkpointNextIndex(AiAnalysisService.this, index + 1, created, warnings);
                    } catch (Throwable persistError) {
                        AiJobStore.error(AiAnalysisService.this, "無法保存 AI 失敗紀錄：" + safeMessage(persistError));
                    }
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                }
                processNext(index + 1);
            }

            @Override public void onFailure(String error) {
                try {
                    long id = savePlaceholder(originalPath, item.replaceId,
                            error == null ? "Gemini 雲端分析失敗" : error, ocrEvidence);
                    created++;
                    warnings++;
                    AiJobStore.appendId(AiAnalysisService.this, id);
                    AiJobStore.checkpointNextIndex(AiAnalysisService.this, index + 1, created, warnings);
                    updateJob(index, 99, "第 " + (index + 1) + " 張已留下原圖＋OCR 原始資料，可在有網路時重新分析");
                } catch (Throwable persistError) {
                    AiJobStore.error(AiAnalysisService.this, "AI 失敗且無法建立重試紀錄：" + safeMessage(persistError));
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                }
                processNext(index + 1);
            }
        });
    }

    private String positionText(int index) {
        int total = Math.max(index + 1, AiJobStore.get(this).queue.size());
        return "第 " + (index + 1) + "/" + total + " 張";
    }

    private int overallFor(int index, int itemProgress) {
        int total = Math.max(index + 1, AiJobStore.get(this).queue.size());
        float doneUnits = index + Math.max(0, Math.min(100, itemProgress)) / 100f;
        int overall = Math.round(doneUnits * 100f / Math.max(1, total));
        return Math.max(1, Math.min(99, overall));
    }

    private void updateJob(int index, int itemProgress, String text) {
        AiJobStore.Snapshot live = AiJobStore.get(this);
        int total = Math.max(index + 1, live.queue.size());
        int p = overallFor(index, itemProgress);
        AiJobStore.progress(this, p, text, index + 1, total, created, warnings, itemProgress);
        publish(text, p, false);
        sendUpdate();
    }

    private long savePlaceholder(String imagePath, long replaceId, String reason, String ocrEvidence) {
        PurchaseOcrParser.ParsedPurchase p = new PurchaseOcrParser.ParsedPurchase();
        p.rawText = "[AI_STATUS:RETRY]\n[AI_VALIDATION:" + safe(reason) + "]\n[OCR_RAW_EVIDENCE]\n" +
                safe(ocrEvidence) + "\n[/OCR_RAW_EVIDENCE]";
        return saveParsed(p, imagePath, replaceId, false, reason);
    }

    private long saveParsed(PurchaseOcrParser.ParsedPurchase parsed, String imagePath,
                            long replaceId, boolean reliable, String validationMessage) {
        PurchaseDbHelper db = new PurchaseDbHelper(this);
        PurchaseDbHelper.Purchase p;
        if (replaceId > 0) {
            p = db.get(replaceId);
            if (p == null) p = new PurchaseDbHelper.Purchase();
        } else {
            p = new PurchaseDbHelper.Purchase();
        }

        String preservedSignature = p.signatureReturnDate;
        String preservedNotes = p.notes;
        boolean preservedCompleted = p.completed;

        p.vendorName = parsed.vendor == null ? "" : parsed.vendor;
        if (AiResultValidator.isSelfCompany(p.vendorName)) {
            p.vendorName = "";
            reliable = false;
            validationMessage = "SELF_COMPANY：交易對象誤抓到我方公司，已只清空此欄等待確認";
        }
        p.location = parsed.location == null ? "" : parsed.location;
        p.orderNo = parsed.orderNo == null ? "" : parsed.orderNo;
        p.purchaseDate = parsed.purchaseDate == null ? "" : parsed.purchaseDate;
        p.imagePath = imagePath == null ? "" : imagePath;

        boolean hardFailure = parsed.rawText != null && parsed.rawText.contains("[AI_STATUS:RETRY]");
        String aiStatus = hardFailure ? "RETRY" : (reliable ? "OK" : "PARTIAL");
        p.rawOcr = "[AI_STATUS:" + aiStatus + "]" +
                "\n[AI_VALIDATION:" + (validationMessage == null ? "" : validationMessage) + "]\n" +
                (parsed.rawText == null ? "" : parsed.rawText);
        p.signatureReturnDate = preservedSignature == null ? "" : preservedSignature;
        p.notes = preservedNotes == null ? "" : preservedNotes;
        p.completed = preservedCompleted;

        StringBuilder legacy = new StringBuilder();
        String earliest = "";
        if (parsed.items != null) {
            for (PurchaseDbHelper.PurchaseItem i : parsed.items) {
                if (i == null) continue;
                if (legacy.length() > 0) legacy.append("\n");
                legacy.append(i.description == null ? "" : i.description);
                if (i.specification != null && !i.specification.isEmpty()) legacy.append("｜規格：").append(i.specification);
                if (i.quantity != null && !i.quantity.isEmpty()) {
                    legacy.append(" × ").append(i.quantity).append(i.unit == null ? "" : i.unit);
                }
                if (i.deliveryDate != null && !i.deliveryDate.isEmpty() &&
                        (earliest.isEmpty() || i.deliveryDate.compareTo(earliest) < 0)) {
                    earliest = i.deliveryDate;
                }
            }
        }
        p.legacyItems = legacy.toString();
        p.legacyDeliveryDate = earliest;

        long id;
        if (replaceId > 0 && p.id > 0) {
            db.update(p);
            id = p.id;
        } else {
            id = db.insert(p);
        }
        if (id <= 0) throw new IllegalStateException("SQLite 無法建立採購單紀錄");
        db.replaceItems(id, parsed.items == null ? new ArrayList<>() : parsed.items);
        try { DriveBackupHelper.backupIfDueAsync(this); } catch (Throwable ignored) {}
        return id;
    }

    private String safeMessage(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "採購單追蹤｜Gemini AI 排隊", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("排隊處理文件校正、PP-OCRv6 Small、OpenCV 固定 8 欄、ML Kit、Gemini 雲端視覺與欄位驗證");
        c.setSound(null, null);
        nm.createNotificationChannel(c);
    }

    private Notification notification(String text, int progress, boolean finished) {
        Intent open = new Intent(this, EnhancedAiMainActivity.class);
        open.putExtra("screen", finished ? "purchases" : "analysis");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 71, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(finished ? "採購單追蹤｜AI 排隊完成" : "採購單追蹤｜Gemini 分析排隊中")
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(!finished)
                .setAutoCancel(finished)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (!finished) b.setProgress(100, Math.max(1, Math.min(99, progress)), false);
        return b.build();
    }

    private void publish(String text, int progress, boolean finished) {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, notification(text, progress, finished));
    }

    private void sendUpdate() {
        Intent i = new Intent(ACTION_UPDATE);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
