package com.quanyi.docscanner;

import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.util.ArrayList;

/** Single owner of mutable scan/session state. */
public final class ScanSession {
    public final ArrayList<Uri> selectedUris = new ArrayList<>();
    public final ArrayList<File> croppedFiles = new ArrayList<>();
    public final ArrayList<float[]> cropHistory = new ArrayList<>();
    public int currentIndex = 0;
    public int previewIndex = 0;
    public int filterPreset = FilterPreset.CLEAR_DOCUMENT.id;
    public boolean aiScannedBatch = false;
    public boolean returnToResultAfterEdit = false;
    public Uri pendingCameraUri;
    public File pendingCameraFile;

    public void ensureCropHistorySize() {
        while (cropHistory.size() < selectedUris.size()) cropHistory.add(null);
    }

    public void saveToBundle(Bundle out) {
        ArrayList<String> uris = new ArrayList<>();
        for (Uri uri : selectedUris) uris.add(uri.toString());
        ArrayList<String> files = new ArrayList<>();
        for (File file : croppedFiles) files.add(file.getAbsolutePath());
        out.putStringArrayList("scan.uris", uris);
        out.putStringArrayList("scan.files", files);
        out.putInt("scan.current", currentIndex);
        out.putInt("scan.preview", previewIndex);
        out.putInt("scan.filter", filterPreset);
        out.putBoolean("scan.ai", aiScannedBatch);
        out.putBoolean("scan.returnResult", returnToResultAfterEdit);
        out.putInt("scan.cropCount", cropHistory.size());
        for (int i=0;i<cropHistory.size();i++) {
            float[] crop = cropHistory.get(i);
            if (crop != null) out.putFloatArray("scan.crop."+i, crop);
        }
    }

    public boolean restoreFromBundle(Bundle in) {
        if (in == null) return false;
        ArrayList<String> uris = in.getStringArrayList("scan.uris");
        if (uris == null || uris.isEmpty()) return false;
        resetWork();
        for (String value : uris) try { selectedUris.add(Uri.parse(value)); } catch (Throwable ignored) {}
        ArrayList<String> files = in.getStringArrayList("scan.files");
        if (files != null) for (String value : files) {
            File file = new File(value);
            if (file.exists()) croppedFiles.add(file);
        }
        int count = Math.max(selectedUris.size(), in.getInt("scan.cropCount", 0));
        for (int i=0;i<count;i++) cropHistory.add(in.getFloatArray("scan.crop."+i));
        currentIndex = Math.max(0, in.getInt("scan.current", 0));
        previewIndex = Math.max(0, in.getInt("scan.preview", 0));
        filterPreset = FilterPreset.fromId(in.getInt("scan.filter", FilterPreset.CLEAR_DOCUMENT.id)).id;
        aiScannedBatch = in.getBoolean("scan.ai", false);
        returnToResultAfterEdit = in.getBoolean("scan.returnResult", false);
        ensureCropHistorySize();
        return !selectedUris.isEmpty();
    }

    public void resetWork() {
        selectedUris.clear();
        croppedFiles.clear();
        cropHistory.clear();
        currentIndex = 0;
        previewIndex = 0;
        aiScannedBatch = false;
        returnToResultAfterEdit = false;
        pendingCameraUri = null;
        pendingCameraFile = null;
    }
}
