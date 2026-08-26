package com.tft.purchase;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class ReminderNotifier {
    private ReminderNotifier() {}
    public static final int NOTIFICATION_ID = 8235678;
    private static final String CHANNEL_ID = "delivery_reminders_v2";

    public static void notifyNow(Context context) {
        PurchaseDbHelper db = new PurchaseDbHelper(context);
        List<PurchaseDbHelper.DueItem> list = db.listDueItems();
        int reminderDays = context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE).getInt("reminder_days", 5);
        int overdue = 0, today = 0, soon = 0;
        List<String> names = new ArrayList<>();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
        Date now = startOfDay(new Date());
        for (PurchaseDbHelper.DueItem d : list) {
            if (d.purchaseCompleted || d.itemCompleted || d.deliveryDate == null || d.deliveryDate.isEmpty()) continue;
            try {
                Date target = sdf.parse(d.deliveryDate);
                if (target == null) continue;
                long diff = TimeUnit.MILLISECONDS.toDays(target.getTime() - now.getTime());
                if (diff < 0) overdue++;
                else if (diff == 0) today++;
                else if (diff <= reminderDays) soon++;
                else continue;
                if (names.size() < 3) {
                    String n = (d.vendorName == null || d.vendorName.isEmpty() ? "未填廠商" : d.vendorName) + "｜" + d.deliveryDate;
                    if (!names.contains(n)) names.add(n);
                }
            } catch (Exception ignored) {}
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "採購交貨提醒", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("即使 APP 關閉也會由背景工作檢查交貨日期");
            nm.createNotificationChannel(channel);
        }
        if (overdue + today + soon == 0) { nm.cancel(NOTIFICATION_ID); return; }
        Intent open = new Intent(context, SafeMainActivityV203.class);
        open.putExtra("screen", "reminders");
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 20, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String summary = "已逾期 " + overdue + "｜今天 " + today + "｜" + reminderDays + "天內 " + soon;
        StringBuilder detail = new StringBuilder(summary);
        for (String n : names) detail.append("\n").append(n);
        if (overdue > 0) detail.append("\n⚠ 請優先處理逾期項目");
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("全益採購追蹤｜交貨提醒")
                .setContentText(summary)
                .setStyle(new Notification.BigTextStyle().bigText(detail.toString()))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE);
        nm.notify(NOTIFICATION_ID, b.build());
    }

    private static Date startOfDay(Date d) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
            Date x = sdf.parse(sdf.format(d));
            return x == null ? d : x;
        } catch (Exception e) { return d; }
    }
}
