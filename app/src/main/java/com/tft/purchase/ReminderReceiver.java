package com.tft.purchase;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PurchaseDbHelper db = new PurchaseDbHelper(context);
        List<PurchaseDbHelper.DueItem> list = db.listDueItems();
        int overdue = 0, today = 0, soon = 0;
        int reminderDays = context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE).getInt("reminder_days", 5);
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
            } catch (Exception ignored) {}
        }

        if (overdue + today + soon > 0) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "delivery_reminders";
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(channelId, "交貨提醒", NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("全益採購追蹤每日交貨提醒");
                nm.createNotificationChannel(channel);
            }
            Intent open = new Intent(context, MainActivity.class);
            open.putExtra("screen", "reminders");
            PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String text = "已逾期 " + overdue + " 項｜今天 " + today + " 項｜即將交貨 " + soon + " 項";
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
            b.setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("全益採購追蹤｜交貨雷達")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text + "\n點我查看廠商與品項明細"))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(8235678, b.build());
        }
        DriveBackupHelper.backupIfDueAsync(context);
    }

    private Date startOfDay(Date d) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
            Date x = sdf.parse(sdf.format(d));
            return x == null ? d : x;
        } catch (Exception e) { return d; }
    }
}
