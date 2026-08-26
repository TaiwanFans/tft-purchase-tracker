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
import java.util.List;

/**
 * Foreground service for long Gemma inference. The analysis continues when the UI is backgrounded.
 */
public class AiAnalysisService extends Service {
    public static final String ACTION_UPDATE = "com.tft.purchase.AI_JOB_UPDATE";
    private static final String CHANNEL = "gemma_analysis_v207";
    private static final int NOTIFICATION_ID = 8235707;
    private volatile boolean processing = false;
    private int success = 0;
    private int failed = 0;

    public static void start(Context context, ArrayList<String> imagePaths, long replaceId) {
        if (imagePaths == null || imagePaths.isEmpty()) return;
        AiJobStore.begin(context, imagePaths, replaceId);
        Intent i = new Intent(context, AiAnalysisService.class);
        i.putStringArrayListExtra("paths", imagePaths);
        i.putExtra("replace_id", replaceId);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("AI 準備分析採購單", 1, false));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        if (!GemmaBridge.isReady(this)) {
            AiJobStore.error(this, "Gemma AI 模型尚未安裝完成");
            publish("Gemma 模型尚未就緒", 100, true);
            stopSelf();
            return START_NOT_STICKY;
        }

        processing = true;
        success = 0;
        failed = 0;
        processNext(paths, 0, replaceId);
        return START_STICKY;
    }

    private void processNext(ArrayList<String> paths, int index, long replaceId) {
        if (index >= paths.size()) {
            processing = false;
            AiJobStore.done(this, success, failed);
            String msg = failed == 0 ? "AI 已完成 " + success + " 張採購單" : "AI 完成：成功 " + success + "、需重試 " + failed;
            publish(msg, 100, true);
            sendUpdate();
            stopSelf();
            return;
        }
        final String path = paths.get(index);
        final int total = paths.size();
        final int base = (int)((index * 100f) / total);
        final int span = Math.max(1, 100 / total);
        AiJobStore.progress(this, Math.max(1, base + 2), "第 " + (index + 1) + "/" + total + " 張：準備 AI", index + 1, total, success, failed);
        publish("第 " + (index + 1) + "/" + total + " 張：準備 AI", Math.max(1, base + 2), false);
        sendUpdate();

        GemmaBridge.analyzeAdvanced(this, path, (percent, stage) -> {
            int overall = Math.min(98, base + Math.max(1, (int)(span * (percent / 100f))));
            String text = "第 " + (index + 1) + "/" + total + " 張｜" + stage;
            AiJobStore.progress(this, overall, text, index + 1, total, success, failed);
            publish(text, overall, false);
            sendUpdate();
        }, new GemmaAiCallback() {
            @Override public void onSuccess(String response) {
                try {
                    PurchaseOcrParser.ParsedPurchase parsed = GemmaJsonParser.parseGemmaOnly(response);
                    AiResultValidator.Validation validation = AiResultValidator.validateAndSanitize(parsed);
                    if (!validation.reliable) {
                        failed++;
                        AiJobStore.progress(AiAnalysisService.this, Math.min(98, base + span - 1),
                                "第 " + (index + 1) + " 張未通過可靠度檢查：" + validation.message,
                                index + 1, total, success, failed);
                        sendUpdate();
                    } else {
                        long id = saveParsed(parsed, path, replaceId > 0 && total == 1 ? replaceId : -1);
                        success++;
                        AiJobStore.appendId(AiAnalysisService.this, id);
                    }
                } catch (Throwable t) {
                    failed++;
                }
                processNext(paths, index + 1, replaceId);
            }

            @Override public void onFailure(String error) {
                failed++;
                AiJobStore.progress(AiAnalysisService.this, Math.min(98, base + span - 1),
                        "第 " + (index + 1) + " 張 AI 失敗：" + error,
                        index + 1, total, success, failed);
                sendUpdate();
                processNext(paths, index + 1, replaceId);
            }
        });
    }

    private long saveParsed(PurchaseOcrParser.ParsedPurchase parsed, String imagePath, long replaceId) {
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

        p.vendorName = parsed.vendor;
        p.location = parsed.location;
        p.orderNo = parsed.orderNo;
        p.purchaseDate = parsed.purchaseDate;
        p.imagePath = imagePath;
        p.rawOcr = parsed.rawText;
        p.signatureReturnDate = preservedSignature == null ? "" : preservedSignature;
        p.notes = preservedNotes == null ? "" : preservedNotes;
        p.completed = preservedCompleted;

        StringBuilder legacy = new StringBuilder();
        String earliest = "";
        for (PurchaseDbHelper.PurchaseItem i : parsed.items) {
            if (legacy.length() > 0) legacy.append("\n");
            legacy.append(i.description);
            if (i.quantity != null && !i.quantity.isEmpty()) legacy.append(" × ").append(i.quantity).append(i.unit == null ? "" : i.unit);
            if (i.deliveryDate != null && !i.deliveryDate.isEmpty() && (earliest.isEmpty() || i.deliveryDate.compareTo(earliest) < 0)) earliest = i.deliveryDate;
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
        db.replaceItems(id, parsed.items);
        try { DriveBackupHelper.backupIfDueAsync(this); } catch (Throwable ignored) {}
        return id;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "Gemma AI 採購單分析", NotificationManager.IMPORTANCE_LOW);
        c.setDescription("採購單在背景進行 AI 辨識時顯示進度");
        c.setSound(null, null);
        nm.createNotificationChannel(c);
    }

    private Notification notification(String text, int progress, boolean finished) {
        Intent open = new Intent(this, AiMainActivity.class);
        open.putExtra("screen", finished ? "purchases" : "analysis");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 71, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(finished ? "全益採購追蹤｜AI 分析完成" : "全益採購追蹤｜Gemma AI 分析中")
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
