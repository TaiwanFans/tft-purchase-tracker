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

/**
 * Foreground service for the replaceable local AI pipeline:
 * document correction -> PP-OCRv6 Small + ML Kit -> active AiModelProvider -> validation -> existing DB.
 */
public class AiAnalysisService extends Service {
    public static final String ACTION_UPDATE = "com.tft.purchase.AI_JOB_UPDATE";
    private static final String CHANNEL = "local_ai_analysis_v213";
    private static final int NOTIFICATION_ID = 8235708;
    private volatile boolean processing = false;
    private int created = 0;
    private int warnings = 0;

    public static void start(Context context, ArrayList<String> imagePaths, long replaceId) {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        AiJobStore.begin(context, imagePaths, replaceId);
        Intent i = new Intent(context, AiAnalysisService.class);
        i.putStringArrayListExtra("paths", imagePaths);
        i.putExtra("replace_id", replaceId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("準備文件校正與雙 OCR", 1, false));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (processing) return START_STICKY;
        ArrayList<String> paths = intent == null ? null : intent.getStringArrayListExtra("paths");
        long replaceId = intent == null ? -1 : intent.getLongExtra("replace_id", -1);
        if (paths == null || paths.isEmpty()) {
            AiJobStore.Snapshot s = AiJobStore.get(this);
            if (s.running() && s.paths != null && !s.paths.isEmpty()) {
                paths = new ArrayList<>(s.paths);
                replaceId = s.replaceId;
            }
        }
        if (paths == null || paths.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        AiModelProvider provider = AiModelRegistry.active(this);
        if (!provider.isReady(this)) {
            String msg = provider.displayName() + " 模型尚未下載並驗證完成";
            AiJobStore.error(this, msg);
            publish(msg, 100, true);
            stopSelf();
            return START_NOT_STICKY;
        }

        processing = true;
        created = 0;
        warnings = 0;
        processNext(paths, 0, replaceId);
        return START_STICKY;
    }

    private void processNext(ArrayList<String> paths, int index, long replaceId) {
        if (index >= paths.size()) {
            processing = false;
            AiJobStore.done(this, created, warnings);
            String msg;
            if (created <= 0) msg = "AI 分析結束，但沒有建立採購單";
            else if (warnings > 0) msg = "已建立 " + created + " 張採購單；" + warnings + " 張需要人工確認";
            else msg = "已建立 " + created + " 張採購單，點此查看";
            publish(msg, 100, true);
            sendUpdate();
            stopSelf();
            return;
        }

        final String originalPath = paths.get(index);
        final int total = paths.size();
        final int base = (int)((index * 100f) / total);
        final int span = Math.max(1, 100 / total);

        updateJob(base + 2,
                "第 " + (index + 1) + "/" + total + " 張｜校正文件、去陰影與提升文字清晰度",
                index, total);

        HybridOcrBridge.recognize(this, originalPath, new HybridOcrBridge.Callback() {
            @Override public void onSuccess(HybridOcrBridge.Result result) {
                updateJob(base + Math.max(4, span * 22 / 100),
                        "第 " + (index + 1) + "/" + total + " 張｜PP-OCRv6 + ML Kit 雙 OCR 完成",
                        index, total);
                String modelImage = result.preparedImagePath == null || result.preparedImagePath.isEmpty()
                        ? originalPath : result.preparedImagePath;
                runLocalModel(paths, index, replaceId, originalPath, modelImage,
                        result.evidence, result, total, base, span);
            }

            @Override public void onFailure(String error) {
                // Keep the workflow alive: MiniCPM can still inspect the original image, but the record is warned.
                updateJob(base + Math.max(4, span * 22 / 100),
                        "第 " + (index + 1) + "/" + total + " 張｜雙 OCR 未完成，改由圖片 AI 判讀",
                        index, total);
                runLocalModel(paths, index, replaceId, originalPath, originalPath,
                        "[OCR_ERROR] " + (error == null ? "雙 OCR 未完成" : error), null,
                        total, base, span);
            }
        });
    }

    private void runLocalModel(ArrayList<String> paths, int index, long replaceId,
                               String originalPath, String modelImagePath, String ocrEvidence,
                               HybridOcrBridge.Result hybridResult,
                               int total, int base, int span) {
        AiModelProvider provider = AiModelRegistry.active(this);
        provider.analyze(this, modelImagePath, ocrEvidence, (percent, stage) -> {
            int modelStart = 24;
            int modelSpan = 71;
            int mapped = modelStart + Math.max(0, Math.min(modelSpan, percent * modelSpan / 100));
            int overall = Math.min(98, base + Math.max(1, (int)(span * (mapped / 100f))));
            String text = "第 " + (index + 1) + "/" + total + " 張｜" + stage;
            AiJobStore.progress(this, overall, text, index + 1, total, created, 0);
            publish(text, overall, false);
            sendUpdate();
        }, new AiModelCallback() {
            @Override public void onSuccess(String response) {
                try {
                    PurchaseOcrParser.ParsedPurchase parsed = AiJsonParser.parse(response, ocrEvidence);
                    AiResultValidator.Validation validation = AiResultValidator.validateAndSanitize(parsed);
                    boolean reliable = validation.reliable;
                    long id = saveParsed(parsed, originalPath,
                            replaceId > 0 && total == 1 ? replaceId : -1,
                            reliable, validation.message);
                    created++;
                    if (!reliable) warnings++;
                    AiJobStore.appendId(AiAnalysisService.this, id);
                    String stage = reliable
                            ? "第 " + (index + 1) + " 張已建立採購單 ✓"
                            : "第 " + (index + 1) + " 張已建立；不確定欄位需要人工確認";
                    AiJobStore.progress(AiAnalysisService.this, Math.min(98, base + span - 1),
                            stage, index + 1, total, created, 0);
                    publish(stage, Math.min(98, base + span - 1), false);
                    sendUpdate();
                } catch (Throwable t) {
                    try {
                        long id = savePlaceholder(originalPath,
                                replaceId > 0 && total == 1 ? replaceId : -1,
                                "AI 結果處理失敗：" + safeMessage(t));
                        created++;
                        warnings++;
                        AiJobStore.appendId(AiAnalysisService.this, id);
                    } catch (Throwable ignored) {}
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                }
                processNext(paths, index + 1, replaceId);
            }

            @Override public void onFailure(String error) {
                try {
                    long id = savePlaceholder(originalPath,
                            replaceId > 0 && total == 1 ? replaceId : -1,
                            error == null ? "本機 AI 分析失敗" : error);
                    created++;
                    warnings++;
                    AiJobStore.appendId(AiAnalysisService.this, id);
                    AiJobStore.progress(AiAnalysisService.this, Math.min(98, base + span - 1),
                            "第 " + (index + 1) + " 張已留下原圖，可重新 AI 分析",
                            index + 1, total, created, 0);
                    sendUpdate();
                } catch (Throwable ignored) {
                } finally {
                    if (hybridResult != null) hybridResult.cleanup();
                }
                processNext(paths, index + 1, replaceId);
            }
        });
    }

    private void updateJob(int progress, String text, int index, int total) {
        int p = Math.max(1, Math.min(98, progress));
        AiJobStore.progress(this, p, text, index + 1, total, created, 0);
        publish(text, p, false);
        sendUpdate();
    }

    private long savePlaceholder(String imagePath, long replaceId, String reason) {
        PurchaseOcrParser.ParsedPurchase p = new PurchaseOcrParser.ParsedPurchase();
        p.rawText = "[AI_STATUS:RETRY]\n[AI_VALIDATION:" + reason + "]";
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
        p.location = parsed.location == null ? "" : parsed.location;
        p.orderNo = parsed.orderNo == null ? "" : parsed.orderNo;
        p.purchaseDate = parsed.purchaseDate == null ? "" : parsed.purchaseDate;
        p.imagePath = imagePath == null ? "" : imagePath;
        p.rawOcr = (reliable ? "[AI_STATUS:OK]" : "[AI_STATUS:RETRY]") +
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
        db.replaceItems(id, parsed.items == null ? new ArrayList<>() : parsed.items);
        try { DriveBackupHelper.backupIfDueAsync(this); } catch (Throwable ignored) {}
        return id;
    }

    private String safeMessage(Throwable t) {
        if (t == null) return "未知錯誤";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "本機 AI 採購單分析", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("PP-OCRv6、ML Kit 與本機 MiniCPM-V 分析採購單時顯示進度");
        c.setSound(null, null);
        nm.createNotificationChannel(c);
    }

    private Notification notification(String text, int progress, boolean finished) {
        Intent open = new Intent(this, AiMainActivity.class);
        open.putExtra("screen", finished ? "purchases" : "analysis");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 71, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(finished ? "全益採購追蹤｜AI 結果已建立" : "全益採購追蹤｜雙 OCR + MiniCPM 分析中")
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
