package com.quanyi.docscanner;

import android.Manifest;
import android.app.Activity;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
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

public class MainActivity extends Activity {
    private static final int REQ_IMAGE = 1001;
    private static final int REQ_WRITE = 1002;
    private static final int MAX_IMAGES = 10;

    private static final int BG = Color.rgb(16,24,32);
    private static final int PANEL = Color.rgb(28,39,49);
    private static final int TEXT = Color.rgb(244,248,248);
    private static final int MUTED = Color.rgb(176,196,201);
    private static final int GREEN = Color.rgb(76,255,169);
    private static final int CYAN = Color.rgb(108,224,240);

    private enum Screen { HOME, CROP, RESULT }
    private Screen screen = Screen.HOME;

    private final ArrayList<Uri> selectedUris = new ArrayList<>();
    private final ArrayList<File> croppedFiles = new ArrayList<>();
    private int currentIndex = 0;
    private int previewIndex = 0;
    private int previewToken = 0;
    private boolean pendingBatchSave = false;

    private Bitmap sourceBitmap;
    private Bitmap displayBitmap;
    private PointF[] lastCorners;
    private DocumentCropView cropView;
    private ImageView resultImage;
    private Switch brightSwitch, sharpSwitch;
    private TextView previewCounter;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
        showHome();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private boolean compactUi() {
        return getResources().getConfiguration().screenWidthDp < 380 || getResources().getConfiguration().fontScale > 1.15f;
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
        b.setMinHeight(dp(50));
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setSingleLine(false);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(2));
        if (primary) {
            g.setColor(GREEN); g.setStroke(dp(2), GREEN); b.setTextColor(BG);
        } else {
            g.setColor(PANEL); g.setStroke(dp(2), CYAN); b.setTextColor(TEXT);
        }
        b.setBackground(g);
        b.setPadding(dp(8), dp(4), dp(8), dp(4));
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
        if (compactUi()) {
            parent.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
            gap(parent, 8);
            parent.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        } else {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(left, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));
            View spacer = new View(this);
            row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
            row.addView(right, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));
            parent.addView(row);
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

    private TextView badge(String s) {
        TextView v = text(s, 13, true);
        v.setTextColor(GREEN);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(7), dp(8), dp(7));
        GradientDrawable g = new GradientDrawable();
        g.setColor(PANEL); g.setStroke(dp(2), GREEN);
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
        root.addView(icon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(compactUi()?72:88)));

        TextView title = text("文件裁切器", compactUi()?24:27, true);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);
        TextView version = text("PIXEL DOC TOOL  v1.2", 12, true);
        version.setTextColor(GREEN); version.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(version);
        TextView sub = text("把手機照片變成清楚、端正的文件", compactUi()?14:15, false);
        sub.setTextColor(MUTED); sub.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(sub);
        gap(root, 14);

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.addView(badge("最多 10 張"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        View spacer = new View(this); badges.addView(spacer, new LinearLayout.LayoutParams(dp(8),1));
        badges.addView(badge("本機處理"), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(badges);
        gap(root, 16);

        TextView steps = text("[1] 選擇 1～10 張照片\n[2] 逐張確認文件四角\n[3] 一次套用文件增強\n[4] 全部儲存 / 分享 LINE", compactUi()?14:16, false);
        steps.setLineSpacing(dp(4), 1f);
        root.addView(steps);
        gap(root, 16);

        Button pick = button("▶  選擇照片（可多選）", true);
        pick.setOnClickListener(v -> pickImages());
        root.addView(pick, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        gap(root, 14);

        TextView privacy = text("隱私：所有影像只在手機內處理，不會上傳到伺服器。", 12, true);
        privacy.setTextColor(GREEN);
        root.addView(privacy);
        TextView note = text("TIP  拍攝時讓文件四周留一點背景，自動抓角會更準。", 12, false);
        note.setTextColor(MUTED);
        root.addView(note);
        setSafeContentView(scroll);
    }

    private void pickImages() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
        clearBatchFiles();
        selectedUris.clear();
        selectedUris.addAll(picked);
        croppedFiles.clear();
        currentIndex = 0;
        previewIndex = 0;
        loadCurrentDocument();
    }

    private void loadCurrentDocument() {
        if (currentIndex >= selectedUris.size()) {
            if (!croppedFiles.isEmpty()) showBatchResult(); else showHome();
            return;
        }
        releaseWorkingBitmaps();
        ProgressDialog d = ProgressDialog.show(this, "分析文件", "正在分析第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張…", true, false);
        Uri uri = selectedUris.get(currentIndex);
        new Thread(() -> {
            try {
                Bitmap b = ImageUtils.loadBitmap(getContentResolver(), uri, 4096);
                PointF[] corners = ImageUtils.detectDocumentCorners(b);
                runOnUiThread(() -> {
                    d.dismiss();
                    sourceBitmap = b;
                    lastCorners = corners != null ? corners : ImageUtils.defaultCorners(b);
                    showCrop(lastCorners);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    d.dismiss();
                    Toast.makeText(this, "第 " + (currentIndex+1) + " 張無法讀取，已略過。", Toast.LENGTH_LONG).show();
                    currentIndex++;
                    loadCurrentDocument();
                });
            }
        }).start();
    }

    private void showCrop(PointF[] corners) {
        screen = Screen.CROP;
        LinearLayout root = vertical(compactUi()?8:10);
        TextView title = text("第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張｜四角校正", compactUi()?17:20, true);
        title.setGravity(Gravity.CENTER);
        root.addView(title);
        TextView hint = text("拖曳方形控制點，讓綠框貼齊紙張。", 12, false);
        hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER);
        root.addView(hint);

        cropView = new DocumentCropView(this);
        cropView.setDocument(sourceBitmap, corners);
        root.addView(cropView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout actions = vertical(0);
        actions.setPadding(0, dp(8), 0, 0);
        Button auto = button("↻ 重新抓角", false);
        String confirmLabel = currentIndex < selectedUris.size()-1 ? "✓ 確認・下一張" : "✓ 確認・完成";
        Button crop = button(confirmLabel, true);
        addAdaptivePair(actions, auto, crop, 54);
        root.addView(actions);

        auto.setOnClickListener(v -> {
            ProgressDialog d = ProgressDialog.show(this, "重新分析", "正在尋找最佳文件邊界…", true, false);
            new Thread(() -> {
                PointF[] p;
                try { p = ImageUtils.detectDocumentCorners(sourceBitmap); }
                catch (Throwable e) { p = ImageUtils.defaultCorners(sourceBitmap); }
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
        ProgressDialog d = ProgressDialog.show(this, "裁切中", "正在處理第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張…", true, false);
        new Thread(() -> {
            Bitmap out = null;
            try {
                out = ImageUtils.perspectiveCrop(sourceBitmap, p);
                File dir = new File(getCacheDir(), "batch_raw");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");
                File f = new File(dir, String.format(Locale.US, "raw_%02d.jpg", currentIndex+1));
                try (FileOutputStream stream = new FileOutputStream(f)) {
                    if (!out.compress(Bitmap.CompressFormat.JPEG, 97, stream)) throw new Exception("encode");
                }
                Bitmap finalOut = out;
                runOnUiThread(() -> {
                    d.dismiss();
                    if (!croppedFiles.contains(f)) croppedFiles.add(f);
                    if (finalOut != null && !finalOut.isRecycled()) finalOut.recycle();
                    currentIndex++;
                    loadCurrentDocument();
                });
            } catch (Throwable e) {
                if (out != null && !out.isRecycled()) out.recycle();
                runOnUiThread(() -> {
                    d.dismiss();
                    Toast.makeText(this, "裁切失敗，請重新調整四個角。", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showBatchResult() {
        screen = Screen.RESULT;
        releaseWorkingBitmaps();
        previewIndex = Math.max(0, Math.min(previewIndex, croppedFiles.size()-1));

        LinearLayout page = vertical(compactUi()?8:10);
        TextView title = text(croppedFiles.size() == 1 ? "文件處理完成" : "已完成 " + croppedFiles.size() + " 張文件", compactUi()?18:20, true);
        title.setGravity(Gravity.CENTER);
        page.addView(title);
        TextView hint = text("增強設定會套用到全部照片。", 12, false);
        hint.setTextColor(MUTED); hint.setGravity(Gravity.CENTER);
        page.addView(hint);

        int screenH = getResources().getDisplayMetrics().heightPixels;
        int imageHeight = Math.max(dp(200), Math.min(dp(compactUi()?320:390), screenH/2));
        resultImage = new ImageView(this);
        resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultImage.setBackgroundColor(PANEL);
        page.addView(resultImage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight));

        if (croppedFiles.size() > 1) {
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
            prev.setOnClickListener(v -> { if (previewIndex > 0) { previewIndex--; refreshPreview(); } });
            next.setOnClickListener(v -> { if (previewIndex < croppedFiles.size()-1) { previewIndex++; refreshPreview(); } });
        }

        gap(page,6);
        TextView mode = text("文件增強", 14, true);
        mode.setTextColor(GREEN);
        page.addView(mode);

        LinearLayout switches = new LinearLayout(this);
        switches.setOrientation(compactUi()?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);
        brightSwitch = new Switch(this);
        brightSwitch.setText("亮度＋｜紙張白化、陰影校正");
        brightSwitch.setTextColor(TEXT); brightSwitch.setTextSize(compactUi()?13:14);
        brightSwitch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); brightSwitch.setChecked(true);
        sharpSwitch = new Switch(this);
        sharpSwitch.setText("清晰＋｜文字與表格線強化");
        sharpSwitch.setTextColor(TEXT); sharpSwitch.setTextSize(compactUi()?13:14);
        sharpSwitch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); sharpSwitch.setChecked(true);
        if (compactUi()) {
            switches.addView(brightSwitch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
            switches.addView(sharpSwitch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        } else {
            switches.addView(brightSwitch, new LinearLayout.LayoutParams(0,dp(54),1f));
            switches.addView(sharpSwitch, new LinearLayout.LayoutParams(0,dp(54),1f));
        }
        page.addView(switches);

        TextView note = text("建議：一般紙本文件同時開啟兩項，可得到最接近掃描器的效果。", 12, false);
        note.setTextColor(MUTED);
        page.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(BG);
        shell.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));

        LinearLayout actions = vertical(8);
        Button share = button(croppedFiles.size()>1 ? "LINE 分享全部" : "LINE 分享", false);
        Button save = button(croppedFiles.size()>1 ? "全部儲存到相簿" : "儲存到相簿", true);
        addAdaptivePair(actions, share, save, 52);
        gap(actions,8);
        Button again = button("重新選擇照片", false);
        actions.addView(again, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));
        shell.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        brightSwitch.setOnCheckedChangeListener((b,c) -> refreshPreview());
        sharpSwitch.setOnCheckedChangeListener((b,c) -> refreshPreview());
        share.setOnClickListener(v -> shareAll());
        save.setOnClickListener(v -> saveAll());
        again.setOnClickListener(v -> pickImages());
        setSafeContentView(shell);
        refreshPreview();
    }

    private void refreshPreview() {
        if (resultImage == null || croppedFiles.isEmpty()) return;
        if (previewCounter != null) previewCounter.setText((previewIndex+1) + " / " + croppedFiles.size());
        final int token = ++previewToken;
        final int idx = previewIndex;
        final boolean bright = brightSwitch != null && brightSwitch.isChecked();
        final boolean sharp = sharpSwitch != null && sharpSwitch.isChecked();
        new Thread(() -> {
            Bitmap raw = BitmapFactory.decodeFile(croppedFiles.get(idx).getAbsolutePath());
            if (raw == null) return;
            Bitmap enhanced = null;
            try { enhanced = ImageUtils.enhance(raw, bright, sharp); }
            catch (Throwable ignored) {}
            if (enhanced == null) enhanced = raw.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap finalEnhanced = enhanced;
            runOnUiThread(() -> {
                if (token != previewToken || resultImage == null) {
                    if (!finalEnhanced.isRecycled()) finalEnhanced.recycle();
                    return;
                }
                if (displayBitmap != null && !displayBitmap.isRecycled()) displayBitmap.recycle();
                displayBitmap = finalEnhanced;
                resultImage.setImageBitmap(displayBitmap);
            });
            if (!raw.isRecycled()) raw.recycle();
        }).start();
    }

    private File createEnhancedShareFile(File rawFile, int index, boolean bright, boolean sharp) throws Exception {
        Bitmap raw = BitmapFactory.decodeFile(rawFile.getAbsolutePath());
        if (raw == null) throw new Exception("decode");
        Bitmap out = null;
        try {
            out = ImageUtils.enhance(raw, bright, sharp);
            if (out == null) out = raw.copy(Bitmap.Config.ARGB_8888, false);
            File dir = new File(getCacheDir(), "shared");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");
            File f = new File(dir, String.format(Locale.US, "document_%02d_%d.jpg", index+1, System.currentTimeMillis()));
            try (FileOutputStream stream = new FileOutputStream(f)) {
                if (!out.compress(Bitmap.CompressFormat.JPEG, 97, stream)) throw new Exception("encode");
            }
            return f;
        } finally {
            if (out != null && out != raw && !out.isRecycled()) out.recycle();
            if (!raw.isRecycled()) raw.recycle();
        }
    }

    private void shareAll() {
        if (croppedFiles.isEmpty()) return;
        final boolean bright = brightSwitch != null && brightSwitch.isChecked();
        final boolean sharp = sharpSwitch != null && sharpSwitch.isChecked();
        ProgressDialog d = ProgressDialog.show(this, "準備分享", "正在處理 1 / " + croppedFiles.size() + " 張…", true, false);
        new Thread(() -> {
            try {
                ArrayList<Uri> uris = new ArrayList<>();
                for (int i=0;i<croppedFiles.size();i++) {
                    int n=i;
                    runOnUiThread(() -> d.setMessage("正在處理 " + (n+1) + " / " + croppedFiles.size() + " 張…"));
                    File f = createEnhancedShareFile(croppedFiles.get(i), i, bright, sharp);
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
        if (croppedFiles.isEmpty()) return;
        final boolean bright = brightSwitch != null && brightSwitch.isChecked();
        final boolean sharp = sharpSwitch != null && sharpSwitch.isChecked();
        ProgressDialog d = ProgressDialog.show(this, "儲存中", "正在儲存 1 / " + croppedFiles.size() + " 張…", true, false);
        new Thread(() -> {
            int saved = 0;
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());
            for (int i=0;i<croppedFiles.size();i++) {
                int n=i;
                runOnUiThread(() -> d.setMessage("正在儲存 " + (n+1) + " / " + croppedFiles.size() + " 張…"));
                Bitmap raw = null, out = null;
                try {
                    raw = BitmapFactory.decodeFile(croppedFiles.get(i).getAbsolutePath());
                    if (raw == null) continue;
                    out = ImageUtils.enhance(raw, bright, sharp);
                    if (out == null) out = raw.copy(Bitmap.Config.ARGB_8888, false);
                    String name = "文件_" + stamp + "_" + String.format(Locale.US,"%02d",i+1) + ".jpg";
                    saveBitmapToGallery(out, name);
                    saved++;
                } catch (Throwable ignored) {
                } finally {
                    if (out != null && out != raw && !out.isRecycled()) out.recycle();
                    if (raw != null && !raw.isRecycled()) raw.recycle();
                }
            }
            int finalSaved = saved;
            runOnUiThread(() -> {
                d.dismiss();
                if (finalSaved == croppedFiles.size()) Toast.makeText(this, "已將 " + finalSaved + " 張文件儲存到相簿。", Toast.LENGTH_LONG).show();
                else Toast.makeText(this, "已儲存 " + finalSaved + " / " + croppedFiles.size() + " 張，部分檔案處理失敗。", Toast.LENGTH_LONG).show();
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
                if (out == null || !b.compress(Bitmap.CompressFormat.JPEG, 97, out)) throw new Exception("write");
            }
            cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);
        } else {
            File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File dir = new File(base, "文件裁切器");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("mkdir");
            File f = new File(dir, name);
            try (FileOutputStream out = new FileOutputStream(f)) {
                if (!b.compress(Bitmap.CompressFormat.JPEG, 97, out)) throw new Exception("write");
            }
            android.media.MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        }
    }

    private void releaseWorkingBitmaps() {
        if (resultImage != null) resultImage.setImageDrawable(null);
        if (displayBitmap != null && !displayBitmap.isRecycled()) displayBitmap.recycle();
        displayBitmap = null;
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

    @Override public void onBackPressed() {
        if (screen == Screen.RESULT) {
            showHome();
            return;
        }
        if (screen == Screen.CROP) {
            Toast.makeText(this, "已取消本次批次處理。", Toast.LENGTH_SHORT).show();
            selectedUris.clear(); croppedFiles.clear(); clearBatchFiles(); showHome();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        releaseWorkingBitmaps();
    }
}
