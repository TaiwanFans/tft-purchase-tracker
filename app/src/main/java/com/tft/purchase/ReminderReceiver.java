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
        List<PurchaseDbHelper.Purchase> list = db.list("");
        int overdue = 0, today = 0, soon = 0;
        int days = context.getSharedPreferences("tft_settings", Context.MODE_PRIVATE).getInt("reminder_days", 5);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
        Date now = startOfDay(new Date());
        for (PurchaseDbHelper.Purchase p : list) {
            if (p.completed || p.deliveryDate == null || p.deliveryDate.isEmpty()) continue;
            try {
                Date d = sdf.parse(p.deliveryDate);
                if (d == null) continue;
                long diff = TimeUnit.MILLISECONDS.toDays(d.getTime() - now.getTime());
                if (diff < 0) overdue++;
                else if (diff == 0) today++;
                else if (diff <= days) soon++;
            } catch (Exception ignored) {}
        }
        if (overdue + today + soon == 0) return;

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String channelId = "delivery_reminders";
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(channelId, "交貨提醒", NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "已逾期 " + overdue + "｜今天 " + today + "｜即將交貨 " + soon;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, channelId) : new Notification.Builder(context);
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("全益採購追蹤")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi);
        nm.notify(8235678, b.build());
    }

    private Date startOfDay(Date d) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
            Date x = sdf.parse(sdf.format(d));
            return x == null ? d : x;
        } catch (Exception e) {
            return d;
        }
    }
}
