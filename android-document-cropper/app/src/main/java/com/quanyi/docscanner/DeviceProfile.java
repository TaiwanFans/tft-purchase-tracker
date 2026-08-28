package com.quanyi.docscanner;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

/** Central RAM/API performance policy. */
public final class DeviceProfile {
    public final boolean lowMemoryMode;
    public final int memoryClassMb;
    private DeviceProfile(boolean lowMemoryMode, int memoryClassMb) {
        this.lowMemoryMode = lowMemoryMode;
        this.memoryClassMb = memoryClassMb;
    }

    public static DeviceProfile detect(Context context) {
        int memory = 384;
        boolean low = Build.VERSION.SDK_INT <= 27;
        try {
            ActivityManager am = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                memory = am.getMemoryClass();
                low = am.isLowRamDevice() || memory <= 384 || Build.VERSION.SDK_INT <= 27;
            }
        } catch (Throwable ignored) {}
        return new DeviceProfile(low, memory);
    }

    public int sourceMaxDimension() {
        if (lowMemoryMode) return 1600;
        return Build.VERSION.SDK_INT <= 30 ? 2000 : 2400;
    }
    public int fastPreviewMaxDimension() {
        if (lowMemoryMode) return 1000;
        return Build.VERSION.SDK_INT <= 30 ? 1300 : 1650;
    }
    public int outputMaxDimension() {
        if (lowMemoryMode) return 3600;
        return Build.VERSION.SDK_INT <= 30 ? 4600 : 5600;
    }
    public int hqPreviewMaxDimension() {
        if (lowMemoryMode || memoryClassMb <= 256) return Math.min(outputMaxDimension(), 2400);
        if (memoryClassMb <= 384) return Math.min(outputMaxDimension(), 3000);
        if (memoryClassMb <= 512) return Math.min(outputMaxDimension(), 3600);
        return outputMaxDimension();
    }
}
