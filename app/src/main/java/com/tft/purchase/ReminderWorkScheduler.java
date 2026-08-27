package com.tft.purchase;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public final class ReminderWorkScheduler {
    private static final String DAILY = "tft_daily_delivery_reminder";
    private static final String NOW = "tft_delivery_reminder_now";
    private ReminderWorkScheduler() {}

    public static void schedule(Context context) {
        long delay = millisUntilNext830();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ReminderWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void runNow(Context context) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ReminderWorker.class).build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request);
    }

    private static long millisUntilNext830() {
        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, 8);
        next.set(Calendar.MINUTE, 30);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);
        return Math.max(1000L, next.getTimeInMillis() - now.getTimeInMillis());
    }
}
