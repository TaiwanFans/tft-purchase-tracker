package com.tft.purchase;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

public class ReminderWorker extends Worker {
    public ReminderWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context app = getApplicationContext();
        try { ReminderNotifier.notifyNow(app); } catch (Throwable ignored) {}
        if (BackupFileHelper.isConfigured(app)) {
            try { BackupFileHelper.backupNow(app); } catch (Throwable ignored) {}
        }
        return Result.success();
    }
}
