from pathlib import Path

main = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = main.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'v153 patch failed: {label}')
    s = s.replace(old, new, 1)

def replace_between(text, start_marker, end_marker, replacement, label):
    a = text.find(start_marker)
    if a < 0:
        raise SystemExit(f'v153 patch failed start: {label}')
    b = text.find(end_marker, a)
    if b < 0:
        raise SystemExit(f'v153 patch failed end: {label}')
    return text[:a] + replacement + text[b:]

# ---------- navigation helpers ----------
anchor = '    private void ensureCropHistorySize() {'
nav_helpers = '''    private void saveCurrentCropStateSafely() {\n        if (cropView == null || sourceBitmap == null) return;\n        try { saveCornerState(currentIndex, cropView.getCorners(), sourceBitmap); } catch (Throwable ignored) {}\n    }\n\n    private void confirmHomeFromWork() {\n        new AlertDialog.Builder(this)\n                .setTitle("返回首頁？")\n                .setMessage("回到首頁後，本次尚未儲存的裁切工作會清除。")\n                .setNegativeButton("繼續編輯", null)\n                .setPositiveButton("返回首頁", (dialog, which) -> {\n                    previewToken++;\n                    returnToResultAfterEdit = false;\n                    selectedUris.clear();\n                    croppedFiles.clear();\n                    cropHistory.clear();\n                    clearBatchFiles();\n                    showHome();\n                })\n                .show();\n    }\n\n    private void cropBackAction() {\n        saveCurrentCropStateSafely();\n        if (returnToResultAfterEdit) {\n            returnToResultAfterEdit = false;\n            currentIndex = selectedUris.size();\n            showBatchResult();\n            return;\n        }\n        if (currentIndex > 0) {\n            currentIndex--;\n            loadCurrentDocument();\n            return;\n        }\n        confirmHomeFromWork();\n    }\n\n    private void editCurrentPreview() {\n        if (selectedUris.isEmpty() || croppedFiles.isEmpty()) return;\n        returnToResultAfterEdit = true;\n        currentIndex = Math.max(0, Math.min(previewIndex, selectedUris.size()-1));\n        loadCurrentDocument();\n    }\n\n'''
if anchor not in s:
    raise SystemExit('v153 patch failed: navigation helper anchor')
s = s.replace(anchor, nav_helpers + anchor, 1)

# ---------- visible back/home buttons on crop ----------
needle = '''        LinearLayout root = vertical(compactUi()?7:9);\n        TextView title = text("STEP 2 / 3｜第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張｜校正裁切", compactUi()?16:19, true);'''
replacement = '''        LinearLayout root = vertical(compactUi()?7:9);\n        LinearLayout topNav = new LinearLayout(this);\n        topNav.setOrientation(LinearLayout.HORIZONTAL);\n        Button backTop = button("← 返回", false);\n        Button homeTop = button("⌂ 首頁", false);\n        topNav.addView(backTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n        View navGap = new View(this);\n        topNav.addView(navGap, new LinearLayout.LayoutParams(dp(8), 1));\n        topNav.addView(homeTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f));\n        root.addView(topNav);\n        backTop.setOnClickListener(v -> cropBackAction());\n        homeTop.setOnClickListener(v -> { saveCurrentCropStateSafely(); confirmHomeFromWork(); });\n        TextView title = text("STEP 2 / 3｜第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張｜校正裁切", compactUi()?16:19, true);'''
rep(needle, replacement, 'crop visible navigation')

# ---------- visible edit/home buttons on result ----------
needle = '''        LinearLayout page = vertical(compactUi()?8:10);\n        TextView title = text(croppedFiles.size() == 1 ? "STEP 3 / 3｜預覽與輸出" : "STEP 3 / 3｜預覽 " + croppedFiles.size() + " 張文件", compactUi()?18:20, true);'''
replacement = '''        LinearLayout page = vertical(compactUi()?8:10);\n        LinearLayout topNav = new LinearLayout(this);\n        topNav.setOrientation(LinearLayout.HORIZONTAL);\n        Button editTop = button("← 修改裁切", false);\n        Button homeTop = button("⌂ 首頁", false);\n        topNav.addView(editTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n        View navGap = new View(this);\n        topNav.addView(navGap, new LinearLayout.LayoutParams(dp(8), 1));\n        topNav.addView(homeTop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.72f));\n        page.addView(topNav);\n        editTop.setOnClickListener(v -> editCurrentPreview());\n        homeTop.setOnClickListener(v -> confirmHomeFromWork());\n        TextView title = text(croppedFiles.size() == 1 ? "STEP 3 / 3｜預覽與輸出" : "STEP 3 / 3｜預覽 " + croppedFiles.size() + " 張文件", compactUi()?18:20, true);'''
rep(needle, replacement, 'result visible navigation')

# ---------- fast crop confirm: create preview only, not high-res final ----------
new_crop_method = '''    private void doCropAndContinue() {\n        if (cropView == null || sourceBitmap == null) return;\n        PointF[] p = cropView.getCorners();\n        lastCorners = p;\n        saveCornerState(currentIndex, p, sourceBitmap);\n        ProgressDialog d = ProgressDialog.show(this, "建立預覽", "正在處理第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張…", true, false);\n        final int indexBeingProcessed = currentIndex;\n        new Thread(() -> {\n            Bitmap out = null;\n            try {\n                // Use the light editing bitmap here. Final HQ image is rebuilt from the original only when saving/sharing.\n                out = ImageUtils.perspectiveCrop(sourceBitmap, p);\n                File dir = new File(getCacheDir(), "batch_raw");\n                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");\n                File f = new File(dir, String.format(Locale.US, "raw_%02d.jpg", indexBeingProcessed+1));\n                try (FileOutputStream stream = new FileOutputStream(f)) {\n                    if (!out.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw new Exception("encode");\n                }\n                Bitmap finalOut = out;\n                runOnUiThread(() -> {\n                    d.dismiss();\n                    if (indexBeingProcessed < croppedFiles.size()) croppedFiles.set(indexBeingProcessed, f);\n                    else croppedFiles.add(f);\n                    if (finalOut != null && !finalOut.isRecycled()) finalOut.recycle();\n                    if (returnToResultAfterEdit) {\n                        returnToResultAfterEdit = false;\n                        previewIndex = Math.max(0, Math.min(indexBeingProcessed, croppedFiles.size()-1));\n                        currentIndex = selectedUris.size();\n                        showBatchResult();\n                    } else {\n                        currentIndex = indexBeingProcessed + 1;\n                        loadCurrentDocument();\n                    }\n                });\n            } catch (Throwable e) {\n                if (out != null && !out.isRecycled()) out.recycle();\n                runOnUiThread(() -> {\n                    d.dismiss();\n                    Toast.makeText(this, "裁切失敗，請重新調整裁切範圍。", Toast.LENGTH_LONG).show();\n                });\n            }\n        }).start();\n    }\n\n'''
s = replace_between(s, '    private void doCropAndContinue() {', '    private void showBatchResult() {', new_crop_method, 'fast crop method')

# ---------- HQ final output rebuilt directly from original ----------
anchor = '    private File createEnhancedShareFile('
hq_helpers = '''    private Bitmap buildFinalDocumentBitmap(int index, boolean bright, boolean sharp, boolean monochrome) throws Exception {\n        if (index < 0 || index >= selectedUris.size()) throw new Exception("index");\n        Bitmap original = null;\n        Bitmap cropped = null;\n        Bitmap enhanced = null;\n        try {\n            original = ImageUtils.loadBitmap(getContentResolver(), selectedUris.get(index), outputMaxDimension());\n            PointF[] points = restoreCornerState(index, original);\n            if (points == null) {\n                if (aiScannedBatch) {\n                    float ix = Math.max(2f, original.getWidth() * 0.003f);\n                    float iy = Math.max(2f, original.getHeight() * 0.003f);\n                    points = new PointF[]{\n                            new PointF(ix, iy), new PointF(original.getWidth()-ix, iy),\n                            new PointF(original.getWidth()-ix, original.getHeight()-iy), new PointF(ix, original.getHeight()-iy)\n                    };\n                } else {\n                    points = ImageUtils.detectDocumentCorners(original);\n                    if (points == null) points = ImageUtils.defaultCorners(original);\n                }\n            }\n            cropped = ImageUtils.perspectiveCrop(original, points);\n            enhanced = ImageUtils.enhance(cropped, bright, sharp, monochrome);\n            if (enhanced == null) enhanced = cropped.copy(Bitmap.Config.ARGB_8888, false);\n            Bitmap result = enhanced;\n            enhanced = null;\n            return result;\n        } finally {\n            if (enhanced != null && !enhanced.isRecycled()) enhanced.recycle();\n            if (cropped != null && !cropped.isRecycled()) cropped.recycle();\n            if (original != null && !original.isRecycled()) original.recycle();\n        }\n    }\n\n'''
if anchor not in s:
    raise SystemExit('v153 patch failed: HQ helper anchor')
s = s.replace(anchor, hq_helpers + anchor, 1)

# Replace share-file creation so it bypasses the preview JPEG completely.
new_share_file = '''    private File createEnhancedShareFile(File rawFile, int index, boolean bright, boolean sharp, boolean monochrome) throws Exception {\n        Bitmap out = null;\n        try {\n            out = buildFinalDocumentBitmap(index, bright, sharp, monochrome);\n            File dir = new File(getCacheDir(), "shared");\n            if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");\n            File f = new File(dir, String.format(Locale.US, "document_%02d_%d.jpg", index+1, System.currentTimeMillis()));\n            try (FileOutputStream stream = new FileOutputStream(f)) {\n                if (!out.compress(Bitmap.CompressFormat.JPEG, lowMemoryMode ? 98 : 99, stream)) throw new Exception("encode");\n            }\n            return f;\n        } finally {\n            if (out != null && !out.isRecycled()) out.recycle();\n        }\n    }\n\n'''
s = replace_between(s, '    private File createEnhancedShareFile(', '    private void shareAll() {', new_share_file, 'HQ share file')

# Replace gallery save loop with original-source HQ output.
new_save = '''    private void saveBatchToGallery() {\n        if (croppedFiles.isEmpty()) return;\n        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();\n        ProgressDialog d = ProgressDialog.show(this, "高畫質儲存", "正在處理 1 / " + croppedFiles.size() + " 張…", true, false);\n        new Thread(() -> {\n            int saved = 0;\n            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());\n            for (int i=0;i<croppedFiles.size();i++) {\n                int n=i;\n                runOnUiThread(() -> d.setMessage("高畫質處理 " + (n+1) + " / " + croppedFiles.size() + " 張…"));\n                Bitmap out = null;\n                try {\n                    out = buildFinalDocumentBitmap(i, bright, sharp, monochrome);\n                    String name = "文件_" + stamp + "_" + String.format(Locale.US,"%02d",i+1) + ".jpg";\n                    saveBitmapToGallery(out, name);\n                    saved++;\n                } catch (Throwable ignored) {\n                } finally {\n                    if (out != null && !out.isRecycled()) out.recycle();\n                }\n            }\n            int finalSaved = saved;\n            runOnUiThread(() -> {\n                d.dismiss();\n                if (finalSaved == croppedFiles.size()) Toast.makeText(this, "已將 " + finalSaved + " 張高畫質文件儲存到相簿。", Toast.LENGTH_LONG).show();\n                else Toast.makeText(this, "已儲存 " + finalSaved + " / " + croppedFiles.size() + " 張，部分檔案處理失敗。", Toast.LENGTH_LONG).show();\n            });\n        }).start();\n    }\n\n'''
s = replace_between(s, '    private void saveBatchToGallery() {', '    private void saveBitmapToGallery(', new_save, 'HQ gallery save')

# System back: result -> safe home confirmation, crop -> previous step/result/home. No more RESULT<->CROP loop.
start = s.find('    @Override public void onBackPressed() {')
end = s.find('    @Override protected void onDestroy()', start)
if start < 0 or end < 0:
    raise SystemExit('v153 patch failed: onBackPressed markers')
new_back = '''    @Override public void onBackPressed() {\n        if (screen == Screen.RESULT) {\n            confirmHomeFromWork();\n            return;\n        }\n        if (screen == Screen.CROP) {\n            cropBackAction();\n            return;\n        }\n        super.onBackPressed();\n    }\n\n'''
s = s[:start] + new_back + s[end:]

s = s.replace('PIXEL DOC TOOL  v1.5.2', 'PIXEL DOC TOOL  v1.5.3')
main.write_text(s, encoding='utf-8')

# ---------- faster, clearer luminance-only document enhancement ----------
image = Path('app/src/main/java/com/quanyi/docscanner/ImageUtils.java')
u = image.read_text(encoding='utf-8')

# Replace monochrome overload with OpenCV grayscale fast path when available.
mono_start = u.find('    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {')
mono_end = u.find('    private static Bitmap enhanceOpenCv(', mono_start)
if mono_start < 0 or mono_end < 0:
    raise SystemExit('v153 patch failed: monochrome overload')
mono_method = '''    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {\n        Bitmap out = enhance(input, brighter, sharper);\n        if (!monochrome || out == null || out.isRecycled()) return out;\n        if (ensureOpenCv()) {\n            Mat rgba = new Mat();\n            Mat gray = new Mat();\n            Mat result = new Mat();\n            try {\n                Utils.bitmapToMat(out, rgba);\n                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);\n                gray.convertTo(gray, -1, 1.10, -10.0);\n                Imgproc.cvtColor(gray, result, Imgproc.COLOR_GRAY2RGBA);\n                Utils.matToBitmap(result, out);\n                return out;\n            } catch (Throwable ignored) {\n            } finally {\n                result.release();\n                gray.release();\n                rgba.release();\n            }\n        }\n        try {\n            int w = out.getWidth(), h = out.getHeight();\n            int[] px = new int[w*h];\n            out.getPixels(px, 0, w, 0, 0, w, h);\n            for (int i=0;i<px.length;i++) {\n                int c = px[i];\n                int y = clamp((Color.red(c)*30 + Color.green(c)*59 + Color.blue(c)*11) / 100);\n                y = clamp(Math.round((y - 128) * 1.10f + 118));\n                px[i] = Color.argb(Color.alpha(c), y, y, y);\n            }\n            out.setPixels(px, 0, w, 0, 0, w, h);\n        } catch (Throwable ignored) {}\n        return out;\n    }\n\n'''
u = u[:mono_start] + mono_method + u[mono_end:]

# Replace expensive 3-channel illumination correction with downsampled luminance correction.
start = u.find('    private static Bitmap enhanceOpenCv(Bitmap input, boolean brighter, boolean sharper) {')
end = u.find('    private static Bitmap fallbackEnhance(', start)
if start < 0 or end < 0:
    raise SystemExit('v153 patch failed: enhanceOpenCv markers')
new_enhance = '''    private static Bitmap enhanceOpenCv(Bitmap input, boolean brighter, boolean sharper) {\n        Mat src = new Mat();\n        Mat rgb = new Mat();\n        Mat lab = new Mat();\n        List<Mat> channels = new ArrayList<>();\n        try {\n            Utils.bitmapToMat(input, src);\n            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB);\n            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);\n            Core.split(lab, channels);\n\n            Mat l = channels.get(0);\n            if (brighter) {\n                Mat small = new Mat();\n                Mat smallBg = new Mat();\n                Mat background = new Mat();\n                Mat corrected = new Mat();\n                Mat claheL = new Mat();\n                try {\n                    int longest = Math.max(l.cols(), l.rows());\n                    double ds = Math.min(1.0, 900.0 / Math.max(1, longest));\n                    if (ds < 0.999) {\n                        Imgproc.resize(l, small, new Size(Math.max(32, Math.round(l.cols()*ds)), Math.max(32, Math.round(l.rows()*ds))), 0, 0, Imgproc.INTER_AREA);\n                    } else {\n                        l.copyTo(small);\n                    }\n                    double sigma = Math.max(8.0, Math.min(30.0, Math.max(small.cols(), small.rows()) / 28.0));\n                    Imgproc.GaussianBlur(small, smallBg, new Size(0,0), sigma);\n                    Imgproc.resize(smallBg, background, l.size(), 0, 0, Imgproc.INTER_LINEAR);\n                    Core.add(background, Scalar.all(3.0), background);\n                    Core.divide(l, background, corrected, 248.0);\n                    corrected.convertTo(corrected, -1, 1.035, 3.0);\n                    CLAHE clahe = Imgproc.createCLAHE(2.35, new Size(8,8));\n                    clahe.apply(corrected, claheL);\n                    l.release();\n                    channels.set(0, claheL);\n                    claheL = null;\n                } finally {\n                    if (claheL != null) claheL.release();\n                    corrected.release();\n                    background.release();\n                    smallBg.release();\n                    small.release();\n                }\n            }\n\n            if (sharper) {\n                Mat currentL = channels.get(0);\n                Mat blur = new Mat();\n                try {\n                    Imgproc.GaussianBlur(currentL, blur, new Size(0,0), 0.65);\n                    Core.addWeighted(currentL, 1.50, blur, -0.50, 0, currentL);\n                } finally {\n                    blur.release();\n                }\n            }\n\n            Core.merge(channels, lab);\n            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);\n            Mat outRgba = new Mat();\n            try {\n                Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA);\n                Bitmap out = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);\n                Utils.matToBitmap(outRgba, out);\n                return out;\n            } finally {\n                outRgba.release();\n            }\n        } finally {\n            for (Mat c : channels) try { c.release(); } catch (Throwable ignored) {}\n            lab.release();\n            rgb.release();\n            src.release();\n        }\n    }\n\n'''
u = u[:start] + new_enhance + u[end:]
image.write_text(u, encoding='utf-8')

# ---------- version ----------
gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
if 'versionCode 12' not in g or "versionName '1.5.2'" not in g:
    raise SystemExit('v153 patch failed: expected v1.5.2 version')
g = g.replace('versionCode 12', 'versionCode 13', 1)
g = g.replace("versionName '1.5.2'", "versionName '1.5.3'", 1)
gradle.write_text(g, encoding='utf-8')

# ---------- verification ----------
checks = [
    'PIXEL DOC TOOL  v1.5.3',
    'Button backTop = button("← 返回", false)',
    'Button editTop = button("← 修改裁切", false)',
    'Button homeTop = button("⌂ 首頁", false)',
    'confirmHomeFromWork()',
    'buildFinalDocumentBitmap',
    'Use the light editing bitmap here',
    'high-quality' if False else '高畫質儲存',
]
for check in checks:
    if check not in s:
        raise SystemExit('v153 verification failed: ' + check)
if '900.0 / Math.max(1, longest)' not in u or 'createCLAHE(2.35' not in u:
    raise SystemExit('v153 verification failed: new enhancement pipeline')

print('v1.5.3 navigation / fast preview / single-pass HQ output / faster enhancement applied')
