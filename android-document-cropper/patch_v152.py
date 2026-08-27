from pathlib import Path

main = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = main.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'v152 patch failed: {label}')
    s = s.replace(old, new, 1)

# ---------- imports ----------
rep('import android.app.Activity;\n', 'import android.app.Activity;\nimport android.app.AlertDialog;\n', 'AlertDialog import')
rep('import android.widget.FrameLayout;\n', 'import android.widget.FrameLayout;\nimport android.widget.HorizontalScrollView;\n', 'HorizontalScrollView import')

# ---------- session state / navigation ----------
rep(
'''    private final ArrayList<Uri> selectedUris = new ArrayList<>();\n    private final ArrayList<File> croppedFiles = new ArrayList<>();''',
'''    private final ArrayList<Uri> selectedUris = new ArrayList<>();\n    private final ArrayList<File> croppedFiles = new ArrayList<>();\n    private final ArrayList<float[]> cropHistory = new ArrayList<>();''',
'crop history field')

rep(
'''    private boolean aiScannedBatch = false;''',
'''    private boolean aiScannedBatch = false;\n    private boolean returnToResultAfterEdit = false;''',
'edit return field')

# ---------- performance split: light editing, high-quality output ----------
rep(
'''    private int sourceMaxDimension() {\n        if (lowMemoryMode) return 2400;\n        return Build.VERSION.SDK_INT <= 30 ? 3100 : 3800;\n    }\n    private int previewMaxDimension() {\n        if (lowMemoryMode) return 760;\n        return Build.VERSION.SDK_INT <= 30 ? 950 : 1250;\n    }''',
'''    private int sourceMaxDimension() {\n        // Crop UI uses a lighter bitmap. Final output is reloaded from the original file.\n        if (lowMemoryMode) return 1800;\n        return Build.VERSION.SDK_INT <= 30 ? 2200 : 2800;\n    }\n    private int previewMaxDimension() {\n        // Preview is sharper than v1.5.1 but still bounded for older phones.\n        if (lowMemoryMode) return 1050;\n        return Build.VERSION.SDK_INT <= 30 ? 1350 : 1750;\n    }\n    private int outputMaxDimension() {\n        // High quality is only used during confirm/save flow, not while dragging.\n        if (lowMemoryMode) return 3400;\n        return Build.VERSION.SDK_INT <= 30 ? 4100 : 4800;\n    }''',
'quality performance dimensions')

# Preview should not use RGB565 because it visibly degrades document/stamp quality.
s = s.replace('opts.inPreferredConfig = lowMemoryMode ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;',
              'opts.inPreferredConfig = Bitmap.Config.ARGB_8888;')

# Restore high JPEG quality after separating the editing bitmap from final output.
s = s.replace('lowMemoryMode ? 93 : 96', 'lowMemoryMode ? 97 : 99')

# ---------- compact interactive horizontal chips ----------
anchor = '''    private TextView guideBox(String s) {'''
helpers = '''    private void styleChoiceChip(TextView chip, boolean selected) {\n        GradientDrawable g = new GradientDrawable();\n        g.setShape(GradientDrawable.RECTANGLE);\n        g.setCornerRadius(dp(28));\n        if (selected) {\n            g.setColor(GREEN);\n            g.setStroke(dp(2), GREEN);\n            chip.setTextColor(BG);\n        } else {\n            g.setColor(PANEL);\n            g.setStroke(dp(1), CYAN);\n            chip.setTextColor(TEXT);\n        }\n        chip.setBackground(g);\n        chip.setPadding(dp(16), dp(9), dp(16), dp(9));\n    }\n\n    private TextView choiceChip(String label, boolean selected) {\n        TextView chip = text(label, compactUi()?12:13, true);\n        chip.setGravity(Gravity.CENTER);\n        chip.setMinHeight(dp(42));\n        chip.setSingleLine(true);\n        chip.setClickable(true);\n        chip.setFocusable(true);\n        styleChoiceChip(chip, selected);\n        return chip;\n    }\n\n    private HorizontalScrollView choiceStrip(TextView... chips) {\n        HorizontalScrollView scroll = new HorizontalScrollView(this);\n        scroll.setHorizontalScrollBarEnabled(false);\n        scroll.setFillViewport(false);\n        LinearLayout row = new LinearLayout(this);\n        row.setOrientation(LinearLayout.HORIZONTAL);\n        row.setGravity(Gravity.CENTER_VERTICAL);\n        row.setPadding(0, dp(4), dp(8), dp(4));\n        for (int i=0;i<chips.length;i++) {\n            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);\n            lp.setMarginEnd(dp(8));\n            row.addView(chips[i], lp);\n        }\n        scroll.addView(row, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        return scroll;\n    }\n\n'''
if anchor not in s:
    raise SystemExit('v152 patch failed: choice helpers anchor')
s = s.replace(anchor, helpers + anchor, 1)

# Shorter first-use guidance to recover vertical space.
s = s.replace(
'''TextView steps = guideBox("STEP 1 / 3  選擇掃描方式\\n\\n推薦：AI 智慧掃描會自動抓邊與校正。\\n完成後只要再確認裁切範圍，就能預覽、儲存或分享。");''',
'''TextView steps = guideBox("STEP 1 / 3  選擇掃描方式\\n推薦先用 AI 智慧掃描；也可以直接使用相簿或拍照。");''')

# Home secondary actions become compact horizontally scrollable choices.
rep(
'''        LinearLayout inputActions = vertical(0);\n        TextView recommended = text("推薦方式", 13, true);\n        recommended.setTextColor(GREEN);\n        inputActions.addView(recommended);\n        Button aiScan = button("✦ AI 智慧掃描", true);\n        aiScan.setOnClickListener(v -> startAiScan());\n        inputActions.addView(aiScan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));\n        gap(inputActions, 10);\n        TextView other = text("其他方式｜同樣支援裁切與文件濾鏡", 12, true);\n        other.setTextColor(MUTED);\n        inputActions.addView(other);\n        Button pick = button("▣ 從相簿選擇（最多 10 張）", false);\n        Button camera = button("◎ 使用手機相機拍照", false);\n        pick.setOnClickListener(v -> pickImages());\n        camera.setOnClickListener(v -> takePhoto());\n        addAdaptivePair(inputActions, pick, camera, 56);\n        root.addView(inputActions);''',
'''        LinearLayout inputActions = vertical(0);\n        TextView recommended = text("推薦方式", 13, true);\n        recommended.setTextColor(GREEN);\n        inputActions.addView(recommended);\n        Button aiScan = button("✦ AI 智慧掃描", true);\n        aiScan.setOnClickListener(v -> startAiScan());\n        inputActions.addView(aiScan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        gap(inputActions, 8);\n        TextView other = text("其他方式｜左右滑動選擇", 12, true);\n        other.setTextColor(MUTED);\n        inputActions.addView(other);\n        TextView pick = choiceChip("▣ 相簿｜最多 10 張", false);\n        TextView camera = choiceChip("◎ 手機拍照", false);\n        pick.setOnClickListener(v -> pickImages());\n        camera.setOnClickListener(v -> takePhoto());\n        inputActions.addView(choiceStrip(pick, camera));\n        root.addView(inputActions);''',
'compact home actions')

# Theme buttons -> horizontal chips.
rep(
'''        LinearLayout themes = vertical(0);\n        Button t0 = button(themeLabel(0, "薄荷綠"), themeIndex == 0);\n        Button t1 = button(themeLabel(1, "專業藍"), themeIndex == 1);\n        Button t2 = button(themeLabel(2, "暖陽橘"), themeIndex == 2);\n        Button t3 = button(themeLabel(3, "高對比黑"), themeIndex == 3);\n        t0.setOnClickListener(v -> selectTheme(0));\n        t1.setOnClickListener(v -> selectTheme(1));\n        t2.setOnClickListener(v -> selectTheme(2));\n        t3.setOnClickListener(v -> selectTheme(3));\n        addAdaptivePair(themes, t0, t1, 48);\n        gap(themes, 7);\n        addAdaptivePair(themes, t2, t3, 48);\n        root.addView(themes);''',
'''        TextView t0 = choiceChip(themeLabel(0, "薄荷綠"), themeIndex == 0);\n        TextView t1 = choiceChip(themeLabel(1, "專業藍"), themeIndex == 1);\n        TextView t2 = choiceChip(themeLabel(2, "暖陽橘"), themeIndex == 2);\n        TextView t3 = choiceChip(themeLabel(3, "高對比黑"), themeIndex == 3);\n        t0.setOnClickListener(v -> selectTheme(0));\n        t1.setOnClickListener(v -> selectTheme(1));\n        t2.setOnClickListener(v -> selectTheme(2));\n        t3.setOnClickListener(v -> selectTheme(3));\n        root.addView(choiceStrip(t0, t1, t2, t3));''',
'theme chips')

# Filter buttons -> horizontal chips, with selected state refreshed in-place.
rep(
'''        LinearLayout filterButtons = vertical(0);\n        Button f0 = button("原色", filterPreset == 0);\n        Button f1 = button("清晰文件", filterPreset == 1);\n        Button f2 = button("柔白文件", filterPreset == 2);\n        Button f3 = button("黑白文件", filterPreset == 3);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus));\n        addAdaptivePair(filterButtons, f0, f1, 48);\n        gap(filterButtons, 7);\n        addAdaptivePair(filterButtons, f2, f3, 48);\n        page.addView(filterButtons);''',
'''        TextView f0 = choiceChip("原色", filterPreset == 0);\n        TextView f1 = choiceChip("清晰文件", filterPreset == 1);\n        TextView f2 = choiceChip("柔白文件", filterPreset == 2);\n        TextView f3 = choiceChip("黑白文件", filterPreset == 3);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus, f0, f1, f2, f3));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus, f0, f1, f2, f3));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus, f0, f1, f2, f3));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus, f0, f1, f2, f3));\n        page.addView(choiceStrip(f0, f1, f2, f3));''',
'filter chips')

rep(
'''    private void selectFilterPreset(int preset, TextView status) {\n        filterPreset = Math.max(0, Math.min(3, preset));\n        if (status != null) status.setText("目前：" + filterName(filterPreset));\n        refreshPreview();\n    }''',
'''    private void selectFilterPreset(int preset, TextView status, TextView... chips) {\n        filterPreset = Math.max(0, Math.min(3, preset));\n        if (status != null) status.setText("目前：" + filterName(filterPreset));\n        if (chips != null) {\n            for (int i=0;i<chips.length;i++) styleChoiceChip(chips[i], i == filterPreset);\n        }\n        refreshPreview();\n    }''',
'filter selected interaction')

# ---------- crop history helpers ----------
anchor = '''    private void loadCurrentDocument() {'''
history_helpers = '''    private void ensureCropHistorySize() {\n        while (cropHistory.size() < selectedUris.size()) cropHistory.add(null);\n    }\n\n    private void saveCornerState(int index, PointF[] points, Bitmap bitmap) {\n        if (index < 0 || bitmap == null || points == null || points.length != 4) return;\n        ensureCropHistorySize();\n        float[] n = new float[8];\n        float w = Math.max(1f, bitmap.getWidth());\n        float h = Math.max(1f, bitmap.getHeight());\n        for (int i=0;i<4;i++) {\n            n[i*2] = points[i].x / w;\n            n[i*2+1] = points[i].y / h;\n        }\n        cropHistory.set(index, n);\n    }\n\n    private PointF[] restoreCornerState(int index, Bitmap bitmap) {\n        if (index < 0 || index >= cropHistory.size() || bitmap == null) return null;\n        float[] n = cropHistory.get(index);\n        if (n == null || n.length != 8) return null;\n        PointF[] p = new PointF[4];\n        for (int i=0;i<4;i++) p[i] = new PointF(n[i*2] * bitmap.getWidth(), n[i*2+1] * bitmap.getHeight());\n        return p;\n    }\n\n    private PointF[] scaleCorners(PointF[] points, float sx, float sy) {\n        PointF[] out = new PointF[4];\n        for (int i=0;i<4;i++) out[i] = new PointF(points[i].x * sx, points[i].y * sy);\n        return out;\n    }\n\n'''
if anchor not in s:
    raise SystemExit('v152 patch failed: history helpers anchor')
s = s.replace(anchor, history_helpers + anchor, 1)

# Reset history only for a genuinely new batch.
rep(
'''        croppedFiles.clear();\n        currentIndex = 0;\n        previewIndex = 0;\n        loadCurrentDocument();''',
'''        croppedFiles.clear();\n        cropHistory.clear();\n        ensureCropHistorySize();\n        returnToResultAfterEdit = false;\n        currentIndex = 0;\n        previewIndex = 0;\n        loadCurrentDocument();''',
'batch history reset')

# Restore saved crop when revisiting an image; only auto-detect on first visit.
rep(
'''                PointF[] corners;\n                if (aiScannedBatch) {\n                    float ix = Math.max(2f, b.getWidth() * 0.003f);\n                    float iy = Math.max(2f, b.getHeight() * 0.003f);\n                    corners = new PointF[]{\n                            new PointF(ix, iy),\n                            new PointF(b.getWidth()-ix, iy),\n                            new PointF(b.getWidth()-ix, b.getHeight()-iy),\n                            new PointF(ix, b.getHeight()-iy)\n                    };\n                } else {\n                    corners = ImageUtils.detectDocumentCorners(b);\n                }''',
'''                PointF[] corners = restoreCornerState(currentIndex, b);\n                if (corners == null && aiScannedBatch) {\n                    float ix = Math.max(2f, b.getWidth() * 0.003f);\n                    float iy = Math.max(2f, b.getHeight() * 0.003f);\n                    corners = new PointF[]{\n                            new PointF(ix, iy),\n                            new PointF(b.getWidth()-ix, iy),\n                            new PointF(b.getWidth()-ix, b.getHeight()-iy),\n                            new PointF(ix, b.getHeight()-iy)\n                    };\n                } else if (corners == null) {\n                    corners = ImageUtils.detectDocumentCorners(b);\n                }''',
'restore crop state')

# Save current corner state before processing.
rep(
'''        PointF[] p = cropView.getCorners();\n        lastCorners = p;''',
'''        PointF[] p = cropView.getCorners();\n        lastCorners = p;\n        saveCornerState(currentIndex, p, sourceBitmap);''',
'save crop state before output')

# High-quality final crop: reload original and map normalized edit coordinates to it.
rep(
'''        new Thread(() -> {\n            Bitmap out = null;\n            try {\n                out = ImageUtils.perspectiveCrop(sourceBitmap, p);''',
'''        new Thread(() -> {\n            Bitmap out = null;\n            Bitmap fullSource = null;\n            try {\n                fullSource = ImageUtils.loadBitmap(getContentResolver(), selectedUris.get(currentIndex), outputMaxDimension());\n                Bitmap cropSource = fullSource != null ? fullSource : sourceBitmap;\n                float sx = cropSource.getWidth() / (float)Math.max(1, sourceBitmap.getWidth());\n                float sy = cropSource.getHeight() / (float)Math.max(1, sourceBitmap.getHeight());\n                PointF[] highQualityCorners = scaleCorners(p, sx, sy);\n                out = ImageUtils.perspectiveCrop(cropSource, highQualityCorners);''',
'high quality output crop')

# Recycle the reloaded full source before returning to UI.
rep(
'''                Bitmap finalOut = out;\n                runOnUiThread(() -> {''',
'''                if (fullSource != null && fullSource != sourceBitmap && !fullSource.isRecycled()) fullSource.recycle();\n                fullSource = null;\n                Bitmap finalOut = out;\n                runOnUiThread(() -> {''',
'recycle full source success')

rep(
'''            } catch (Throwable e) {\n                if (out != null && !out.isRecycled()) out.recycle();''',
'''            } catch (Throwable e) {\n                if (fullSource != null && fullSource != sourceBitmap && !fullSource.isRecycled()) fullSource.recycle();\n                if (out != null && !out.isRecycled()) out.recycle();''',
'recycle full source failure')

# After re-editing from result, return straight to result instead of forcing the remaining batch again.
rep(
'''                    if (!croppedFiles.contains(f)) croppedFiles.add(f);\n                    if (finalOut != null && !finalOut.isRecycled()) finalOut.recycle();\n                    currentIndex++;\n                    loadCurrentDocument();''',
'''                    if (!croppedFiles.contains(f)) croppedFiles.add(f);\n                    if (finalOut != null && !finalOut.isRecycled()) finalOut.recycle();\n                    if (returnToResultAfterEdit) {\n                        int editedIndex = currentIndex;\n                        returnToResultAfterEdit = false;\n                        previewIndex = Math.max(0, Math.min(editedIndex, croppedFiles.size()-1));\n                        currentIndex = selectedUris.size();\n                        showBatchResult();\n                    } else {\n                        currentIndex++;\n                        loadCurrentDocument();\n                    }''',
'return to result after edit')

# ---------- correct Android back navigation ----------
start = s.index('    @Override public void onBackPressed() {')
end = s.index('    @Override protected void onDestroy()', start)
new_back = '''    @Override public void onBackPressed() {\n        if (screen == Screen.RESULT) {\n            if (!selectedUris.isEmpty() && !croppedFiles.isEmpty()) {\n                returnToResultAfterEdit = true;\n                currentIndex = Math.max(0, Math.min(previewIndex, selectedUris.size()-1));\n                loadCurrentDocument();\n            } else {\n                showHome();\n            }\n            return;\n        }\n        if (screen == Screen.CROP) {\n            if (cropView != null && sourceBitmap != null) {\n                try { saveCornerState(currentIndex, cropView.getCorners(), sourceBitmap); } catch (Throwable ignored) {}\n            }\n            if (returnToResultAfterEdit) {\n                returnToResultAfterEdit = false;\n                currentIndex = selectedUris.size();\n                showBatchResult();\n                return;\n            }\n            if (currentIndex > 0) {\n                currentIndex--;\n                loadCurrentDocument();\n                return;\n            }\n            new AlertDialog.Builder(this)\n                    .setTitle("取消這次掃描？")\n                    .setMessage("目前照片與裁切進度會被清除。若只是要修改，請選「繼續編輯」。")\n                    .setNegativeButton("繼續編輯", null)\n                    .setPositiveButton("取消本次掃描", (dialog, which) -> {\n                        selectedUris.clear();\n                        croppedFiles.clear();\n                        cropHistory.clear();\n                        clearBatchFiles();\n                        showHome();\n                    })\n                    .show();\n            return;\n        }\n        super.onBackPressed();\n    }\n\n'''
s = s[:start] + new_back + s[end:]

# Version text in the home page.
s = s.replace('PIXEL DOC TOOL  v1.5.1', 'PIXEL DOC TOOL  v1.5.2')

main.write_text(s, encoding='utf-8')

# ---------- ImageUtils: allow higher final document resolution ----------
image = Path('app/src/main/java/com/quanyi/docscanner/ImageUtils.java')
u = image.read_text(encoding='utf-8')
if 'float limit=4096f/Math.max(ow,oh);' not in u:
    raise SystemExit('v152 patch failed: perspective crop limit')
u = u.replace('float limit=4096f/Math.max(ow,oh);', 'float limit=4800f/Math.max(ow,oh);', 1)
image.write_text(u, encoding='utf-8')

# ---------- new clear/simple app icon ----------
icon = Path('app/src/main/res/drawable/ic_pixel_document.xml')
icon.write_text('''<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="108dp"\n    android:height="108dp"\n    android:viewportWidth="108"\n    android:viewportHeight="108">\n    <path android:fillColor="#101820" android:pathData="M0,0H108V108H0Z"/>\n    <path android:fillColor="#F8FAFC" android:pathData="M32,20H67L82,35V88H32Z"/>\n    <path android:fillColor="#C7D2DA" android:pathData="M67,20V35H82Z"/>\n    <path android:fillColor="#101820" android:pathData="M42,44H72V49H42ZM42,56H72V61H42ZM42,68H64V73H42Z"/>\n    <path android:fillColor="#4CFFA9" android:pathData="M16,32V16H32V22H22V32ZM76,16H92V32H86V22H76ZM16,76H22V86H32V92H16ZM86,76H92V92H76V86H86Z"/>\n    <path android:fillColor="#6CE0F0" android:pathData="M26,52H82V57H26Z"/>\n</vector>\n''', encoding='utf-8')

# ---------- version ----------
gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
if 'versionCode 11' not in g or "versionName '1.5.1'" not in g:
    raise SystemExit('v152 patch failed: expected v1.5.1 version')
g = g.replace('versionCode 11', 'versionCode 12', 1)
g = g.replace("versionName '1.5.1'", "versionName '1.5.2'", 1)
gradle.write_text(g, encoding='utf-8')

# ---------- static verification before Gradle ----------
checks = [
    'HorizontalScrollView',
    'returnToResultAfterEdit',
    'outputMaxDimension()',
    'saveCornerState(currentIndex, p, sourceBitmap)',
    'PIXEL DOC TOOL  v1.5.2',
    '取消這次掃描？',
]
for check in checks:
    if check not in s:
        raise SystemExit('v152 verification failed: ' + check)
if '4800f/Math.max(ow,oh)' not in u:
    raise SystemExit('v152 verification failed: HQ perspective crop')

print('v1.5.2 compact UI / safe back / HQ output / new icon patch applied and verified')
