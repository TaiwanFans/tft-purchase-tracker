package com.quanyi.docscanner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ActivityManager;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMAGE = 1001;
    private static final int REQ_WRITE = 1002;
    private static final int REQ_CAMERA = 1003;
    private static final int REQ_AI_SCAN = 1004;
    private static final int MAX_IMAGES = 10;

    private int BG = Color.rgb(16,24,32);
    private int PANEL = Color.rgb(28,39,49);
    private int TEXT = Color.rgb(244,248,248);
    private int MUTED = Color.rgb(176,196,201);
    private int GREEN = Color.rgb(76,255,169);
    private int CYAN = Color.rgb(108,224,240);
    private int themeIndex = 0;
    private static final String UI_PREFS = "docscanner_ui";
    private static final String UI_THEME = "theme";

    private enum Screen { HOME, CROP, RESULT }
    private Screen screen = Screen.HOME;

    private final ScanSession session = new ScanSession();
    private int previewToken = 0;
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();
    private boolean pendingBatchSave = false;
    private DeviceProfile deviceProfile;
    private DocumentDetector documentDetector;
    private DocumentFilterEngine filterEngine;
    private PreviewRenderer previewRenderer;
    private ExportManager exportManager;

    private Bitmap sourceBitmap;
    private Bitmap displayBitmap;
    private Bitmap compareOriginalBitmap;
    private int compareOriginalIndex = -1;
    private PointF[] lastCorners;
    private DocumentCropView cropView;
    private ZoomableImageView resultImage;
    private Switch brightSwitch, sharpSwitch;
    private TextView previewCounter;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        loadTheme();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
        deviceProfile = DeviceProfile.detect(this);
        documentDetector = new DocumentDetector();
        filterEngine = new DocumentFilterEngine();
        previewRenderer = new PreviewRenderer(getContentResolver(), documentDetector);
        exportManager = new ExportManager(getContentResolver(), documentDetector, filterEngine);
        if (b != null && session.restoreFromBundle(b)) {
            String restored = b.getString("ui.screen", Screen.HOME.name());
            try { screen = Screen.valueOf(restored); } catch (Throwable ignored) { screen = Screen.HOME; }
            if (screen == Screen.CROP && !session.aiScannedBatch && session.currentIndex < session.selectedUris.size()) loadCurrentDocument();
            else if (screen == Screen.RESULT && !session.croppedFiles.isEmpty()) showBatchResult();
            else showHome();
        } else {
            showHome();
        }
    }

    private void loadTheme() {
        themeIndex = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getInt(UI_THEME, 0);
        applyThemeColors(themeIndex);
    }

    private void applyThemeColors(int idx) {
        themeIndex = Math.max(0, Math.min(3, idx));
        switch (themeIndex) {
            case 1: // Professional blue
                BG = Color.rgb(15,23,42);
                PANEL = Color.rgb(30,41,59);
                TEXT = Color.rgb(248,250,252);
                MUTED = Color.rgb(203,213,225);
                GREEN = Color.rgb(56,189,248);
                CYAN = Color.rgb(125,211,252);
                break;
            case 2: // Warm orange
                BG = Color.rgb(28,25,23);
                PANEL = Color.rgb(41,37,36);
                TEXT = Color.rgb(250,250,249);
                MUTED = Color.rgb(214,211,209);
                GREEN = Color.rgb(251,146,60);
                CYAN = Color.rgb(253,186,116);
                break;
            case 3: // High contrast
                BG = Color.rgb(8,8,10);
                PANEL = Color.rgb(26,26,29);
                TEXT = Color.WHITE;
                MUTED = Color.rgb(218,218,222);
                GREEN = Color.WHITE;
                CYAN = Color.rgb(190,190,196);
                break;
            default: // Mint green
                BG = Color.rgb(16,24,32);
                PANEL = Color.rgb(28,39,49);
                TEXT = Color.rgb(244,248,248);
                MUTED = Color.rgb(176,196,201);
                GREEN = Color.rgb(76,255,169);
                CYAN = Color.rgb(108,224,240);
                break;
        }
    }

    private void selectTheme(int idx) {
        applyThemeColors(idx);
        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putInt(UI_THEME, themeIndex).apply();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        showHome();
    }

    private String themeLabel(int idx, String name) {
        return (themeIndex == idx ? "✓ " : "") + name;
    }

    private int sourceMaxDimension() { return deviceProfile.sourceMaxDimension(); }
    private int previewMaxDimension() { return deviceProfile.fastPreviewMaxDimension(); }
    private int outputMaxDimension() { return deviceProfile.outputMaxDimension(); }
    private int previewQualityMaxDimension() { return deviceProfile.hqPreviewMaxDimension(); }
    private boolean lowMemoryMode() { return deviceProfile != null && deviceProfile.lowMemoryMode; }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private boolean compactUi() {
        return getResources().getConfiguration().screenWidthDp < 520 || getResources().getConfiguration().fontScale > 1.10f;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(TEXT);
        v.setPadding(0, dp(6), 0, dp(6));
        v.setTypeface(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL);
        return v;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(compactUi() ? 14 : 15);
        b.setAllCaps(false);
        b.setMinHeight(dp(54));
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setSingleLine(false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(12));
        if (primary) {
            g.setColor(GREEN); g.setStroke(dp(2), GREEN); b.setTextColor(BG);
        } else {
            g.setColor(PANEL); g.setStroke(dp(2), CYAN); b.setTextColor(TEXT);
        }
        b.setBackground(g);
        b.setPadding(dp(12), dp(9), dp(12), dp(9));
        return b;
    }

    private LinearLayout vertical(int pad) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(pad), dp(pad), dp(pad), dp(pad));
        l.setBackgroundColor(BG);
        return l;
    }

    private void gap(LinearLayout l, int h) {
        View v = new View(this);
        l.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
    }

    private void addAdaptivePair(LinearLayout parent, Button left, Button right, int heightDp) {
        left.setMinHeight(dp(heightDp));
        right.setMinHeight(dp(heightDp));
        if (compactUi()) {
            parent.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            gap(parent, 8);
            parent.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));
            row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void setSafeContentView(View content) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setOnApplyWindowInsetsListener((v, insets) -> {
            int l=0,t=0,r=0,b=0;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets s = insets.getInsets(WindowInsets.Type.systemBars());
                l=s.left; t=s.top; r=s.right; b=s.bottom;
            } else {
                l=insets.getSystemWindowInsetLeft(); t=insets.getSystemWindowInsetTop();
                r=insets.getSystemWindowInsetRight(); b=insets.getSystemWindowInsetBottom();
            }
            frame.setPadding(l,t,r,b);
            return insets;
        });
        setContentView(frame);
        frame.requestApplyInsets();
    }

    private void styleChoiceChip(TextView chip, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(28));
        if (selected) {
            g.setColor(GREEN);
            g.setStroke(dp(2), GREEN);
            chip.setTextColor(BG);
        } else {
            g.setColor(PANEL);
            g.setStroke(dp(1), CYAN);
            chip.setTextColor(TEXT);
        }
        chip.setBackground(g);
        chip.setPadding(dp(16), dp(9), dp(16), dp(9));
    }

    private TextView choiceChip(String label, boolean selected) {
        TextView chip = text(label, compactUi()?12:13, true);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(dp(42));
        chip.setSingleLine(true);
        chip.setClickable(true);
        chip.setFocusable(true);
        styleChoiceChip(chip, selected);
        return chip;
    }

    private HorizontalScrollView choiceStrip(TextView... chips) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), dp(8), dp(4));
        for (int i=0;i<chips.length;i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(8));
            row.addView(chips[i], lp);
        }
        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView guideBox(String s) {
        TextView v = text(s, compactUi()?13:14, false);
        v.setTextColor(TEXT);
        v.setLineSpacing(dp(3), 1f);
        v.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL);
        g.setStroke(dp(1), CYAN);
        g.setCornerRadius(dp(12));
        v.setBackground(g);
        return v;
    }

    private TextView badge(String s) {
        TextView v = text(s, 13, true);
        v.setTextColor(GREEN);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL); g.setStroke(dp(2), GREEN); g.setCornerRadius(dp(10));
        v.setBackground(g);
        return v;
    }

    private void showHome() {
        screen = Screen.HOME;
        releaseWorkingBitmaps();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = vertical(compactUi() ? 12 : 18);
        scroll.addView(root);

        ImageView icon = new ImageView(this);
        icon.setImageResource(com.quanyi.docscanner.R.drawable.ic_pixel_document);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        root.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(compactUi()?68:84)));

        TextView title = text("文件掃描與裁切", compactUi()?23:27, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);
        TextView version = text("PIXEL DOC TOOL  v2.0.0", 12, true);
        version.setTextColor(GREEN); version.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(version);
        TextView sub = text("第一次使用也很簡單：掃描 → 校正 → 預覽輸出", compactUi()?13:15, false);
        sub.setTextColor(MUTED); sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub);
        gap(root, 12);

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.addView(badge("最多 10 張"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this); badges.addView(spacer, new LinearLayout.LayoutParams(dp(8),1));
        badges.addView(badge(lowMemoryMode() ? "AI＋輕量備援" : "AI＋本機備援"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(badges);
        gap(root, 14);

        TextView steps = guideBox("STEP 1 / 3  選擇掃描方式\n推薦先用 AI 智慧掃描；也可以直接使用相簿或拍照。");
        root.addView(steps);
        gap(root, 14);

        LinearLayout inputActions = vertical(0);
        TextView recommended = text("推薦方式", 13, true);
        recommended.setTextColor(GREEN);
        inputActions.addView(recommended);
        Button aiScan = button("✦ AI 智慧掃描", true);
        aiScan.setOnClickListener(v -> startAiScan());
        inputActions.addView(aiScan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        gap(inputActions, 8);
        TextView other = text("其他方式｜左右滑動選擇", 12, true);
        other.setTextColor(MUTED);
        inputActions.addView(other);
        TextView pick = choiceChip("▣ 相簿｜最多 10 張", false);
        TextView camera = choiceChip("◎ 手機拍照", false);
        pick.setOnClickListener(v -> pickImages());
        camera.setOnClickListener(v -> takePhoto());
        inputActions.addView(choiceStrip(pick, camera));
        root.addView(inputActions);
        gap(root, 14);

        TextView privacy = text("AI 掃描由 Google Play 服務提供；首次使用可能需要下載掃描元件。文件後續裁切與增強由本 APP 處理。", 12, true);
        privacy.setTextColor(GREEN);
        root.addView(privacy);
        TextView note = text("TIP  若 AI 智慧掃描無法啟動，可直接改用相簿或手機相機，原本功能都會保留。", 12, false);
        note.setTextColor(MUTED);
        root.addView(note);
        gap(root, 16);
        TextView themeTitle = text("介面風格", 15, true);
        themeTitle.setTextColor(GREEN);
        root.addView(themeTitle);
        TextView themeHint = text("選擇喜歡的配色，APP 會自動記住。", 12, false);
        themeHint.setTextColor(MUTED);
        root.addView(themeHint);
        TextView t0 = choiceChip(themeLabel(0, "薄荷綠"), themeIndex == 0);
        TextView t1 = choiceChip(themeLabel(1, "專業藍"), themeIndex == 1);
        TextView t2 = choiceChip(themeLabel(2, "暖陽橘"), themeIndex == 2);
        TextView t3 = choiceChip(themeLabel(3, "高對比黑"), themeIndex == 3);
        t0.setOnClickListener(v -> selectTheme(0));
        t1.setOnClickListener(v -> selectTheme(1));
        t2.setOnClickListener(v -> selectTheme(2));
        t3.setOnClickListener(v -> selectTheme(3));
        root.addView(choiceStrip(t0, t1, t2, t3));
        setSafeContentView(scroll);
    }

    private void startAiScan() {
        AiDocumentScanner.start(this, REQ_AI_SCAN, () -> runOnUiThread(this::takePhoto));
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMAGE);
    }

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "camera");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("camera cache");
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) try { if (f.isFile()) f.delete(); } catch (Throwable ignored) {}
            session.pendingCameraFile = new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
            if (!session.pendingCameraFile.exists()) session.pendingCameraFile.createNewFile();
            session.pendingCameraUri = new Uri.Builder()
                    .scheme("content")
                    .authority(getPackageName()+".camera")
                    .appendPath("capture")
                    .appendPath(session.pendingCameraFile.getName())
                    .build();

            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, session.pendingCameraUri);
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            camera.setClipData(ClipData.newRawUri("photo", session.pendingCameraUri));
            startActivityForResult(camera, REQ_CAMERA);
        } catch (Throwable e) {
            session.pendingCameraUri = null;
            session.pendingCameraFile = null;
            Toast.makeText(this, "無法啟動相機，請改用相簿選圖。", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_AI_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<Uri> pages = AiDocumentScanner.readPages(data, MAX_IMAGES);
                if (!pages.isEmpty()) {
                    startAiBatch(pages);
                } else {
                    Toast.makeText(this, "AI 掃描沒有取得文件，請再試一次或使用其他方式。", Toast.LENGTH_LONG).show();
                }
            }
            return;
        }

        if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && session.pendingCameraUri != null && session.pendingCameraFile != null && session.pendingCameraFile.exists() && session.pendingCameraFile.length() > 0) {
                ArrayList<Uri> one = new ArrayList<>();
                one.add(session.pendingCameraUri);
                startBatch(one);
            } else {
                if (session.pendingCameraFile != null) try { session.pendingCameraFile.delete(); } catch (Throwable ignored) {}
            }
            return;
        }

        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || data == null) return;

        ArrayList<Uri> picked = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            int total = clip.getItemCount();
            for (int n=0; n<total && picked.size()<MAX_IMAGES; n++) {
                Uri u = clip.getItemAt(n).getUri();
                if (u != null && !picked.contains(u)) picked.add(u);
            }
            if (total > MAX_IMAGES) Toast.makeText(this, "一次最多處理 10 張，已取前 10 張。", Toast.LENGTH_LONG).show();
        } else if (data.getData() != null) {
            picked.add(data.getData());
        }
        if (picked.isEmpty()) return;

        for (Uri u : picked) {
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(u, flags);
            } catch (Throwable ignored) {}
        }
        startBatch(picked);
    }

    private void startBatch(ArrayList<Uri> picked) {
        session.aiScannedBatch = false;
        beginBatch(picked);
    }

    private void startAiBatch(ArrayList<Uri> picked) {
        session.aiScannedBatch = true;
        prepareAiBatchForPreview(picked);
    }

    private void prepareAiBatchForPreview(ArrayList<Uri> picked) {
        clearBatchFiles();
        session.selectedUris.clear();
        session.croppedFiles.clear();
        session.cropHistory.clear();
        session.returnToResultAfterEdit = false;
        session.currentIndex = 0;
        session.previewIndex = 0;
        if (picked == null || picked.isEmpty()) { showHome(); return; }

        ProgressDialog d = ProgressDialog.show(this, "AI 掃描完成", "正在建立快速預覽…", true, false);
        new Thread(() -> {
            ArrayList<Uri> okUris = new ArrayList<>();
            ArrayList<File> okFiles = new ArrayList<>();
            try {
                File dir = new File(getCacheDir(), "batch_raw");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");
                for (int i=0; i<picked.size() && i<MAX_IMAGES; i++) {
                    final int n = i;
                    runOnUiThread(() -> d.setMessage("建立預覽 " + (n+1) + " / " + Math.min(picked.size(), MAX_IMAGES) + "…"));
                    Bitmap preview = null;
                    try {
                        Uri uri = picked.get(i);
                        preview = ImageUtils.loadBitmap(getContentResolver(), uri, previewMaxDimension());
                        File f = new File(dir, String.format(Locale.US, "raw_%02d.jpg", okFiles.size()+1));
                        try (FileOutputStream stream = new FileOutputStream(f)) {
                            if (!preview.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw new Exception("encode");
                        }
                        okUris.add(uri);
                        okFiles.add(f);
                    } catch (Throwable ignored) {
                    } finally {
                        if (preview != null && !preview.isRecycled()) preview.recycle();
                    }
                }
            } catch (Throwable ignored) {
            }

            runOnUiThread(() -> {
                d.dismiss();
                session.selectedUris.clear();
                session.selectedUris.addAll(okUris);
                session.croppedFiles.clear();
                session.croppedFiles.addAll(okFiles);
                session.cropHistory.clear();
                ensureCropHistorySize();
                // ML Kit returned pages are already cropped. Preserve the whole returned page
                // so HQ save/share never performs a destructive second crop.
                for (int i=0; i<session.cropHistory.size(); i++) {
                    session.cropHistory.set(i, new float[]{0f,0f, 1f,0f, 1f,1f, 0f,1f});
                }
                session.currentIndex = session.selectedUris.size();
                session.previewIndex = 0;
                if (!session.croppedFiles.isEmpty()) {
                    showBatchResult();
                } else {
                    Toast.makeText(this, "AI 掃描結果無法讀取，請再試一次。", Toast.LENGTH_LONG).show();
                    showHome();
                }
            });
        }).start();
    }

    private void beginBatch(ArrayList<Uri> picked) {
        clearBatchFiles();
        session.selectedUris.clear();
        session.selectedUris.addAll(picked);
        session.croppedFiles.clear();
        session.cropHistory.clear();
        ensureCropHistorySize();
        session.returnToResultAfterEdit = false;
        session.currentIndex = 0;
        session.previewIndex = 0;
        loadCurrentDocument();
    }

    private void saveCurrentCropStateSafely() {
        if (cropView == null || sourceBitmap == null) return;
        try { saveCornerState(session.currentIndex, cropView.getCorners(), sourceBitmap); } catch (Throwable ignored) {}
    }

    private void confirmHomeFromWork() {
        new AlertDialog.Builder(this)
                .setTitle("返回首頁？")
                .setMessage("回到首頁後，本次尚未儲存的裁切工作會清除。")
                .setNegativeButton("繼續編輯", null)
                .setPositiveButton("返回首頁", (dialog, which) -> {
                    previewToken++;
                    session.returnToResultAfterEdit = false;
                    session.selectedUris.clear();
                    session.croppedFiles.clear();
                    session.cropHistory.clear();
                    clearBatchFiles();
                    showHome();
                })
                .show();
    }

    private void cropBackAction() {
        saveCurrentCropStateSafely();
        if (session.returnToResultAfterEdit) {
            session.returnToResultAfterEdit = false;
            session.currentIndex = session.selectedUris.size();
            showBatchResult();
            return;
        }
        if (session.currentIndex > 0) {
            session.currentIndex--;
            loadCurrentDocument();
            return;
        }
        confirmHomeFromWork();
    }

    private void editCurrentPreview() {
        if (session.selectedUris.isEmpty() || session.croppedFiles.isEmpty()) return;
        if (session.aiScannedBatch) {
            new AlertDialog.Builder(this)
                    .setTitle("重新 AI 掃描？")
                    .setMessage("AI 智慧掃描已在 Google 掃描器中完成抓邊與透視校正，因此不再進行第二次裁切。若邊界不滿意，可重新開啟 AI 掃描調整。")
                    .setNegativeButton("保留目前結果", null)
                    .setPositiveButton("重新 AI 掃描", (dialog, which) -> startAiScan())
                    .show();
            return;
        }
        session.returnToResultAfterEdit = true;
        session.currentIndex = Math.max(0, Math.min(session.previewIndex, session.selectedUris.size()-1));
        loadCurrentDocument();
    }

    private void ensureCropHistorySize() { session.ensureCropHistorySize(); }

    private void saveCornerState(int index, PointF[] points, Bitmap bitmap) {
        session.ensureCropHistorySize();
        if (index >= 0 && index < session.cropHistory.size())
            session.cropHistory.set(index, CropController.normalize(points, bitmap));
    }

    private PointF[] restoreCornerState(int index, Bitmap bitmap) {
        if (index < 0 || index >= session.cropHistory.size()) return null;
        return CropController.restore(session.cropHistory.get(index), bitmap);
    }

    private PointF[] scaleCorners(PointF[] points, float sx, float sy) {
        return CropController.scale(points, sx, sy);
    }

    private void loadCurrentDocument() {
        if (session.currentIndex >= session.selectedUris.size()) {
            if (!session.croppedFiles.isEmpty()) showBatchResult(); else showHome();
            return;
        }
        releaseWorkingBitmaps();
        ProgressDialog d = ProgressDialog.show(this, "分析文件", "正在分析第 " + (session.currentIndex+1) + " / " + session.selectedUris.size() + " 張…", true, false);
        Uri uri = session.selectedUris.get(session.currentIndex);
        new Thread(() -> {
            try {
                Bitmap b = ImageUtils.loadBitmap(getContentResolver(), uri, sourceMaxDimension());
                PointF[] corners = restoreCornerState(session.currentIndex, b);
                if (corners == null) {
                    // Verify every first-load page, including ML Kit / AI scanner results.
                    // This fixes the case where the AI result still contains a visible desk/background.
                    corners = documentDetector.detect(b);
                    if (corners == null) corners = documentDetector.defaultCorners(b);
                }
                final PointF[] resolvedCorners = corners;
                runOnUiThread(() -> {
                    d.dismiss();
                    sourceBitmap = b;
                    lastCorners = resolvedCorners != null ? resolvedCorners : documentDetector.defaultCorners(b);
                    showCrop(lastCorners);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    d.dismiss();
                    Toast.makeText(this, "第 " + (session.currentIndex+1) + " 張無法讀取，已略過。", Toast.LENGTH_LONG).show();
                    session.currentIndex++;
                    loadCurrentDocument();
                });
            }
        }).start();
    }

    private void showCrop(PointF[] corners) {
        screen = Screen.CROP;
        LinearLayout root = vertical(compactUi()?7:9);
        LinearLayout topNav = new LinearLayout(this);
        topNav.setOrientation(LinearLayout.HORIZONTAL);
        Button backTop = button("← 返回", false);
        Button homeTop = button("⌂ 首頁", false);
        topNav.addView(backTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View navGap = new View(this);
        topNav.addView(navGap, new LinearLayout.LayoutParams(dp(8), 1));
        topNav.addView(homeTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f));
        root.addView(topNav);
        backTop.setOnClickListener(v -> cropBackAction());
        homeTop.setOnClickListener(v -> { saveCurrentCropStateSafely(); confirmHomeFromWork(); });
        TextView title = text("STEP 2 / 3｜第 " + (session.currentIndex+1) + " / " + session.selectedUris.size() + " 張｜校正裁切", compactUi()?16:19, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        String cropHint = session.aiScannedBatch
                ? "AI 已先抓邊與透視校正。若邊界正確可直接按確認；需要時再拖四角 / 四邊中點精修。畫面四周已保留手指操作空間；拖四邊時會即時自動貼邊。"
                : "確認綠色框是否貼齊紙張。可拖四角或四邊中點；拖曳時會顯示放大鏡；拖四邊時會即時自動貼齊附近的紙張邊緣。";
        TextView hint = text(cropHint, compactUi()?11:12, false);
        hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        cropView = new DocumentCropView(this);
        cropView.setDocument(sourceBitmap, corners);
        root.addView(cropView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout actions = vertical(0);
        actions.setPadding(0, dp(7), 0, 0);
        Button auto = button("↻ 重新抓角", false);
        String confirmLabel = session.currentIndex < session.selectedUris.size()-1 ? "✓ 確認・下一張" : "✓ 確認・完成";
        Button crop = button(confirmLabel, true);
        addAdaptivePair(actions, auto, crop, 52);
        root.addView(actions);

        auto.setOnClickListener(v -> {
            ProgressDialog d = ProgressDialog.show(this, "重新分析", "正在尋找最佳文件邊界…", true, false);
            new Thread(() -> {
                PointF[] p;
                try { p = documentDetector.detect(sourceBitmap); }
                catch (Throwable e) { p = documentDetector.defaultCorners(sourceBitmap); }
                PointF[] result = p;
                runOnUiThread(() -> { d.dismiss(); lastCorners=result; cropView.setCorners(result); });
            }).start();
        });
        crop.setOnClickListener(v -> doCropAndContinue());
        setSafeContentView(root);
    }

    private void doCropAndContinue() {
        if (cropView == null || sourceBitmap == null) return;
        PointF[] p = cropView.getCorners();
        lastCorners = p;
        saveCornerState(session.currentIndex, p, sourceBitmap);
        ProgressDialog d = ProgressDialog.show(this, "建立預覽", "正在處理第 " + (session.currentIndex+1) + " / " + session.selectedUris.size() + " 張…", true, false);
        final int indexBeingProcessed = session.currentIndex;
        new Thread(() -> {
            Bitmap out = null;
            try {
                // Use the light editing bitmap here. Final HQ image is rebuilt from the original only when saving/sharing.
                out = ImageUtils.perspectiveCrop(sourceBitmap, p);
                File dir = new File(getCacheDir(), "batch_raw");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");
                File f = new File(dir, String.format(Locale.US, "raw_%02d.jpg", indexBeingProcessed+1));
                try (FileOutputStream stream = new FileOutputStream(f)) {
                    if (!out.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw new Exception("encode");
                }
                Bitmap finalOut = out;
                runOnUiThread(() -> {
                    d.dismiss();
                    if (indexBeingProcessed < session.croppedFiles.size()) session.croppedFiles.set(indexBeingProcessed, f);
                    else session.croppedFiles.add(f);
                    if (finalOut != null && !finalOut.isRecycled()) finalOut.recycle();
                    if (session.returnToResultAfterEdit) {
                        session.returnToResultAfterEdit = false;
                        session.previewIndex = Math.max(0, Math.min(indexBeingProcessed, session.croppedFiles.size()-1));
                        session.currentIndex = session.selectedUris.size();
                        showBatchResult();
                    } else {
                        session.currentIndex = indexBeingProcessed + 1;
                        loadCurrentDocument();
                    }
                });
            } catch (Throwable e) {
                if (out != null && !out.isRecycled()) out.recycle();
                runOnUiThread(() -> {
                    d.dismiss();
                    Toast.makeText(this, "裁切失敗，請重新調整裁切範圍。", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showBatchResult() {
        screen = Screen.RESULT;
        releaseWorkingBitmaps();
        session.previewIndex = Math.max(0, Math.min(session.previewIndex, session.croppedFiles.size()-1));

        LinearLayout page = vertical(compactUi()?8:10);
        LinearLayout topNav = new LinearLayout(this);
        topNav.setOrientation(LinearLayout.HORIZONTAL);
        Button editTop = button(session.aiScannedBatch ? "↻ 重新 AI 掃描" : "← 修改裁切", false);
        Button homeTop = button("⌂ 首頁", false);
        topNav.addView(editTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View navGap = new View(this);
        topNav.addView(navGap, new LinearLayout.LayoutParams(dp(8), 1));
        topNav.addView(homeTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f));
        page.addView(topNav);
        editTop.setOnClickListener(v -> editCurrentPreview());
        homeTop.setOnClickListener(v -> confirmHomeFromWork());
        TextView title = text(session.croppedFiles.size() == 1 ? "STEP 3 / 3｜預覽與輸出" : "STEP 3 / 3｜預覽 " + session.croppedFiles.size() + " 張文件", compactUi()?18:20, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        if (session.aiScannedBatch) {
            TextView aiDone = text("✓ AI 已完成抓邊與透視校正｜直接預覽，不再二次裁切", 12, true);
            aiDone.setTextColor(GREEN);
            aiDone.setGravity(Gravity.CENTER);
            page.addView(aiDone);
        }
        TextView hint = text("先放大確認文字與邊界，再決定是否儲存 / 分享。增強設定會套用到全部照片。", 12, false);
        hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER);
        page.addView(hint);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int imageHeight = Math.max(dp(190), Math.min(dp(compactUi()?300:380), screenH/2));
        resultImage = new ZoomableImageView(this);
        resultImage.setBackgroundColor(PANEL);
        page.addView(resultImage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight));
        TextView zoomTip = text("🔍 雙指張合：放大 / 縮小　｜　放大後單指拖曳：查看位置　｜　雙擊：還原", 12, true);
        zoomTip.setTextColor(CYAN);
        zoomTip.setGravity(Gravity.CENTER);
        page.addView(zoomTip);
        Button resetZoom = button("↺ 還原完整畫面", false);
        resetZoom.setOnClickListener(v -> { if (resultImage != null) resultImage.resetZoom(); });
        page.addView(resetZoom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        if (session.croppedFiles.size() > 1) {
            gap(page,6);
            LinearLayout nav = new LinearLayout(this);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            Button prev = button("‹ 上一張", false);
            Button next = button("下一張 ›", false);
            previewCounter = text("", 13, true);
            previewCounter.setGravity(Gravity.CENTER);
            nav.addView(prev, new LinearLayout.LayoutParams(0,dp(46),1f));
            nav.addView(previewCounter, new LinearLayout.LayoutParams(0,dp(46),0.7f));
            nav.addView(next, new LinearLayout.LayoutParams(0,dp(46),1f));
            page.addView(nav);
            prev.setOnClickListener(v -> { if (session.previewIndex > 0) { session.previewIndex--; refreshPreview(); } });
            next.setOnClickListener(v -> { if (session.previewIndex < session.croppedFiles.size()-1) { session.previewIndex++; refreshPreview(); } });
        }

        gap(page,6);
        TextView mode = text("文件濾鏡｜AI、相簿、一般拍照都可使用", 14, true);
        mode.setTextColor(GREEN);
        page.addView(mode);
        TextView filterHint = text("選一種即可，會套用到目前這批全部文件。", 12, false);
        filterHint.setTextColor(MUTED);
        page.addView(filterHint);
        TextView filterStatus = text("目前：" + filterName(session.filterPreset), 13, true);
        filterStatus.setTextColor(CYAN);
        page.addView(filterStatus);

        TextView f0 = choiceChip("原色", session.filterPreset == 0);
        TextView f1 = choiceChip("清晰文件", session.filterPreset == 1);
        TextView f2 = choiceChip("柔白文件", session.filterPreset == 2);
        TextView f3 = choiceChip("黑白掃描", session.filterPreset == 3);
        TextView f4 = choiceChip("文字銳利", session.filterPreset == 4);
        TextView f5 = choiceChip("自然灰階", session.filterPreset == 5);
        TextView f6 = choiceChip("灰階清晰", session.filterPreset == 6);
        TextView f7 = choiceChip("增強銳化", session.filterPreset == 7);
        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f4.setOnClickListener(v -> selectFilterPreset(4, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f5.setOnClickListener(v -> selectFilterPreset(5, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f6.setOnClickListener(v -> selectFilterPreset(6, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        f7.setOnClickListener(v -> selectFilterPreset(7, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));
        page.addView(choiceStrip(f0, f1, f2, f3, f4, f5, f6, f7));

        gap(page, 6);
        Button compare = button("◐ 按住對比｜查看原圖", false);
        compare.setOnTouchListener((v, event) -> {
            if (resultImage == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (compareOriginalBitmap != null && !compareOriginalBitmap.isRecycled()) {
                    resultImage.setImageBitmapKeepTransform(compareOriginalBitmap);
                    compare.setText("◐ 原圖｜放開返回目前效果");
                }
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (displayBitmap != null && !displayBitmap.isRecycled()) {
                    resultImage.setImageBitmapKeepTransform(displayBitmap);
                }
                compare.setText("◐ 按住對比｜查看原圖");
                return true;
            }
            return true;
        });
        page.addView(compare, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = text("推薦：一般文件用「清晰文件」；陰影偏重用「柔白文件」；小字表格用「文字銳利」；照片偏糊可試「增強銳化」；影印件用「黑白掃描」。", 12, false);
        note.setTextColor(MUTED);
        page.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout actions = vertical(8);
        Button share = button(session.croppedFiles.size()>1 ? "LINE 分享全部" : "LINE 分享", false);
        Button save = button(session.croppedFiles.size()>1 ? "全部儲存到相簿" : "儲存到相簿", true);
        addAdaptivePair(actions, share, save, 52);
        gap(actions,8);
        Button again = button("重新拍照 / 選圖", false);
        actions.addView(again, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        shell.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        share.setOnClickListener(v -> shareAll());
        save.setOnClickListener(v -> saveAll());
        again.setOnClickListener(v -> showHome());
        setSafeContentView(shell);
        refreshPreview();
    }

    private String filterName(int preset) { return FilterPreset.fromId(preset).label; }

    private void selectFilterPreset(int preset, TextView status, TextView... chips) {
        session.filterPreset = FilterPreset.fromId(preset).id;
        for (int i=0;i<chips.length;i++) styleChoiceChip(chips[i], i == session.filterPreset);
        status.setText("目前效果：" + filterName(session.filterPreset));
        refreshPreview();
    }

    private FilterPreset selectedFilter() { return FilterPreset.fromId(session.filterPreset); }

    private Bitmap decodePreview(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int max = previewMaxDimension();
            while (Math.max(bounds.outWidth/sample, bounds.outHeight/sample) > max*1.35f) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1,sample);
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (b == null) return null;
            int longest = Math.max(b.getWidth(), b.getHeight());
            if (longest > max) {
                float scale = max/(float)longest;
                Bitmap scaled = Bitmap.createScaledBitmap(b, Math.max(1,Math.round(b.getWidth()*scale)), Math.max(1,Math.round(b.getHeight()*scale)), true);
                if (scaled != b) b.recycle();
                b = scaled;
            }
            return b;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Bitmap buildPreviewDocumentBitmap(int index) throws Exception {
        if (index < 0 || index >= session.selectedUris.size()) throw new Exception("index");
        float[] crop = index < session.cropHistory.size() ? session.cropHistory.get(index) : null;
        return previewRenderer.render(session.selectedUris.get(index), crop, session.aiScannedBatch, previewQualityMaxDimension());
    }

    private void refreshPreview() {
        if (resultImage == null || session.croppedFiles.isEmpty()) return;
        if (previewCounter != null) previewCounter.setText((session.previewIndex+1) + " / " + session.croppedFiles.size());
        final int token = ++previewToken;
        final int idx = session.previewIndex;
        final FilterPreset preset = selectedFilter();

        previewExecutor.execute(() -> {
            if (token != previewToken) return;
            Bitmap base = null;
            Bitmap enhanced = null;
            boolean newBase = false;
            try {
                Bitmap existing = compareOriginalBitmap;
                if (existing != null && !existing.isRecycled() && compareOriginalIndex == idx) {
                    base = existing;
                } else {
                    base = buildPreviewDocumentBitmap(idx);
                    newBase = true;
                }
                if (token != previewToken || base == null || base.isRecycled()) {
                    if (newBase && base != null && !base.isRecycled()) base.recycle();
                    return;
                }

                enhanced = filterEngine.apply(base, preset);
                if (enhanced == null) enhanced = base.copy(Bitmap.Config.ARGB_8888, false);
                if (token != previewToken) {
                    if (enhanced != base && !enhanced.isRecycled()) enhanced.recycle();
                    if (newBase && base != null && !base.isRecycled()) base.recycle();
                    return;
                }

                final Bitmap finalBase = base;
                final Bitmap finalEnhanced = enhanced;
                final boolean finalNewBase = newBase;
                runOnUiThread(() -> {
                    if (token != previewToken || resultImage == null) {
                        if (finalEnhanced != finalBase && !finalEnhanced.isRecycled()) finalEnhanced.recycle();
                        if (finalNewBase && !finalBase.isRecycled()) finalBase.recycle();
                        return;
                    }

                    Bitmap oldDisplay = displayBitmap;
                    Bitmap oldOriginal = compareOriginalBitmap;
                    compareOriginalBitmap = finalBase;
                    compareOriginalIndex = idx;
                    displayBitmap = finalEnhanced;
                    resultImage.setImageBitmap(displayBitmap);

                    if (oldDisplay != null && oldDisplay != oldOriginal && oldDisplay != finalBase && oldDisplay != finalEnhanced && !oldDisplay.isRecycled()) oldDisplay.recycle();
                    if (oldOriginal != null && oldOriginal != finalBase && oldOriginal != finalEnhanced && !oldOriginal.isRecycled()) oldOriginal.recycle();
                });
            } catch (Throwable ignored) {
                if (enhanced != null && enhanced != base && !enhanced.isRecycled()) enhanced.recycle();
                if (newBase && base != null && !base.isRecycled()) base.recycle();
            }
        });
    }

    private Bitmap buildFinalDocumentBitmap(int index, FilterPreset preset) throws Exception {
        if (index < 0 || index >= session.selectedUris.size()) throw new Exception("index");
        float[] crop = index < session.cropHistory.size() ? session.cropHistory.get(index) : null;
        return exportManager.renderFinal(session.selectedUris.get(index), crop, session.aiScannedBatch, preset, outputMaxDimension());
    }

    private File createEnhancedShareFile(int index, FilterPreset preset) throws Exception {
        Bitmap out = null;
        try {
            out = buildFinalDocumentBitmap(index, preset);
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");
            File f = new File(dir, String.format(Locale.US, "document_%02d_%d.jpg", index+1, System.currentTimeMillis()));
            exportManager.writeJpeg(out, f, lowMemoryMode() ? 99 : 100);
            return f;
        } finally {
            if (out != null && !out.isRecycled()) out.recycle();
        }
    }

    private void shareAll() {
        if (session.croppedFiles.isEmpty()) return;
        final FilterPreset preset = selectedFilter();
        ProgressDialog d = ProgressDialog.show(this, "準備分享", "正在處理 1 / " + session.croppedFiles.size() + " 張…", true, false);
        new Thread(() -> {
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                for (int i=0;i<session.croppedFiles.size();i++) {
                    int n=i;
                    runOnUiThread(() -> d.setMessage("正在處理 " + (n+1) + " / " + session.croppedFiles.size() + " 張…"));
                    File f = createEnhancedShareFile(i, preset);
                    Uri u = new Uri.Builder().scheme("content").authority(getPackageName()+".share").appendPath("images").appendPath(f.getName()).build();
                    uris.add(u);
                }
                runOnUiThread(() -> {
                    d.dismiss();
                    try {
                        Intent share;
                        if (uris.size() == 1) {
                            share = new Intent(Intent.ACTION_SEND);
                            share.setType("image/jpeg");
                            share.putExtra(Intent.EXTRA_STREAM, uris.get(0));
                            share.setClipData(ClipData.newUri(getContentResolver(), "document", uris.get(0)));
                        } else {
                            share = new Intent(Intent.ACTION_SEND_MULTIPLE);
                            share.setType("image/jpeg");
                            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                            ClipData clip = ClipData.newUri(getContentResolver(), "documents", uris.get(0));
                            for (int i=1;i<uris.size();i++) clip.addItem(new ClipData.Item(uris.get(i)));
                            share.setClipData(clip);
                        }
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        share.setPackage("jp.naver.line.android");
                        try { startActivity(share); }
                        catch (Throwable e) { share.setPackage(null); startActivity(Intent.createChooser(share, "分享文件")); }
                    } catch (Throwable e) {
                        Toast.makeText(this, "分享失敗，請先嘗試儲存到相簿。", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Throwable e) {
                runOnUiThread(() -> { d.dismiss(); Toast.makeText(this, "準備分享時發生錯誤。", Toast.LENGTH_LONG).show(); });
            }
        }).start();
    }

    private void saveAll() {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingBatchSave = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
            return;
        }
        saveBatchToGallery();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WRITE) {
            if (grantResults.length>0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingBatchSave) saveBatchToGallery();
            pendingBatchSave = false;
        }
    }

    private void saveBatchToGallery() {
        if (session.croppedFiles.isEmpty()) return;
        final FilterPreset preset = selectedFilter();
        ProgressDialog d = ProgressDialog.show(this, "高畫質儲存", "正在處理 1 / " + session.croppedFiles.size() + " 張…", true, false);
        new Thread(() -> {
            int saved = 0;
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());
            for (int i=0;i<session.croppedFiles.size();i++) {
                int n=i;
                runOnUiThread(() -> d.setMessage("高畫質處理 " + (n+1) + " / " + session.croppedFiles.size() + " 張…"));
                Bitmap out = null;
                try {
                    out = buildFinalDocumentBitmap(i, preset);
                    String name = "文件_" + stamp + "_" + String.format(Locale.US,"%02d",i+1) + ".jpg";
                    saveBitmapToGallery(out, name);
                    saved++;
                } catch (Throwable ignored) {
                } finally {
                    if (out != null && !out.isRecycled()) out.recycle();
                }
            }
            int finalSaved = saved;
            runOnUiThread(() -> {
                d.dismiss();
                if (finalSaved == session.croppedFiles.size()) Toast.makeText(this, "已將 " + finalSaved + " 張高畫質文件儲存到相簿。", Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "已儲存 " + finalSaved + " / " + session.croppedFiles.size() + " 張，部分檔案處理失敗。", Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void saveBitmapToGallery(Bitmap b, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/文件裁切器");
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("insert");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null || !b.compress(Bitmap.CompressFormat.JPEG, lowMemoryMode() ? 99 : 100, out)) throw new Exception("write");
            }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
        } else {
            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File dir = new File(base, "文件裁切器");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir");
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                if (!b.compress(Bitmap.CompressFormat.JPEG, lowMemoryMode() ? 99 : 100, out)) throw new Exception("write");
            }
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        }
    }

    private void releaseWorkingBitmaps() {
        if (resultImage != null) resultImage.setImageDrawable(null);
        Bitmap oldDisplay = displayBitmap;
        Bitmap oldOriginal = compareOriginalBitmap;
        displayBitmap = null;
        compareOriginalBitmap = null;
        compareOriginalIndex = -1;
        if (oldDisplay != null && !oldDisplay.isRecycled()) oldDisplay.recycle();
        if (oldOriginal != null && oldOriginal != oldDisplay && !oldOriginal.isRecycled()) oldOriginal.recycle();
        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();
        sourceBitmap = null;
    }

    private void clearBatchFiles() {
        File raw = new File(getCacheDir(), "batch_raw");
        deleteChildren(raw);
        File shared = new File(getCacheDir(), "shared");
        deleteChildren(shared);
    }

    private void deleteChildren(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            try { if (f.isFile()) f.delete(); } catch (Throwable ignored) {}
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        saveCurrentCropStateSafely();
        session.saveToBundle(outState);
        outState.putString("ui.screen", screen.name());
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if (screen == Screen.RESULT) {
            confirmHomeFromWork();
            return;
        }
        if (screen == Screen.CROP) {
            cropBackAction();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        previewToken++;
        try { previewExecutor.shutdownNow(); } catch (Throwable ignored) {}
        releaseWorkingBitmaps();
    }
}
