from pathlib import Path
import re

# v1.5.8
# - Add Enhanced Sharpen preset.
# - Rework document filters around Lab luminance, local illumination correction,
#   CLAHE, Black Hat text emphasis, adaptive B/W, and thresholded unsharp masking.
# - Preview no longer reads the low-resolution JPEG cache. It rebuilds the current
#   page from the original URI at a RAM-aware high-quality size.
# - Press-and-hold Compare shows the unfiltered current-page crop instantly;
#   release returns to the selected filter without recomputation.
# - Only the current page's original + filtered preview are retained in RAM.

MAIN = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
IMAGE = Path('app/src/main/java/com/quanyi/docscanner/ImageUtils.java')
ZOOM = Path('app/src/main/java/com/quanyi/docscanner/ZoomableImageView.java')
BUILD = Path('app/build.gradle')

m = MAIN.read_text(encoding='utf-8')
u = IMAGE.read_text(encoding='utf-8')
z = ZOOM.read_text(encoding='utf-8')


def must_replace(text, old, new, label, count=1):
    if old not in text:
        raise SystemExit(f'v158 patch failed: {label}')
    return text.replace(old, new, count)


def replace_between(text, start_marker, end_marker, replacement, label):
    a = text.find(start_marker)
    if a < 0:
        raise SystemExit(f'v158 patch failed start: {label}')
    b = text.find(end_marker, a)
    if b < 0:
        raise SystemExit(f'v158 patch failed end: {label}')
    return text[:a] + replacement + text[b:]

# -----------------------------------------------------------------------------
# MainActivity: high-quality current-page preview + hold-to-compare.
# -----------------------------------------------------------------------------
m = must_replace(m, 'import android.view.Gravity;\n',
                 'import android.view.Gravity;\nimport android.view.MotionEvent;\n',
                 'MotionEvent import')

m = must_replace(m,
    '''    private Bitmap sourceBitmap;\n    private Bitmap displayBitmap;''',
    '''    private Bitmap sourceBitmap;\n    private Bitmap displayBitmap;\n    private Bitmap compareOriginalBitmap;\n    private int compareOriginalIndex = -1;''',
    'compare bitmap fields')

# RAM-aware HQ preview. This preview is rebuilt from the original source, not the
# 1000-1650 px cache. High-memory phones can preview at the same target as output;
# midrange phones stay below an OOM-prone two-bitmap footprint.
output_block = '''    private int outputMaxDimension() {\n        // Final save/share reloads directly from the original URI at a much higher target.\n        if (lowMemoryMode) return 3600;\n        return Build.VERSION.SDK_INT <= 30 ? 4600 : 5600;\n    }'''
output_new = output_block + '''\n    private int previewQualityMaxDimension() {\n        int memoryClass = 384;\n        try {\n            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);\n            if (am != null) memoryClass = am.getMemoryClass();\n        } catch (Throwable ignored) {}\n        if (lowMemoryMode || memoryClass <= 256) return Math.min(outputMaxDimension(), 2400);\n        if (memoryClass <= 384) return Math.min(outputMaxDimension(), 3000);\n        if (memoryClass <= 512) return Math.min(outputMaxDimension(), 3600);\n        return outputMaxDimension();\n    }'''
m = must_replace(m, output_block, output_new, 'HQ preview dimensions')

# Add 8th filter preset: Enhanced Sharpen.
old_filter_ui = '''        TextView f0 = choiceChip("原色", filterPreset == 0);\n        TextView f1 = choiceChip("清晰文件", filterPreset == 1);\n        TextView f2 = choiceChip("柔白文件", filterPreset == 2);\n        TextView f3 = choiceChip("黑白掃描", filterPreset == 3);\n        TextView f4 = choiceChip("文字銳利", filterPreset == 4);\n        TextView f5 = choiceChip("自然灰階", filterPreset == 5);\n        TextView f6 = choiceChip("灰階清晰", filterPreset == 6);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f4.setOnClickListener(v -> selectFilterPreset(4, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f5.setOnClickListener(v -> selectFilterPreset(5, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f6.setOnClickListener(v -> selectFilterPreset(6, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        page.addView(choiceStrip(f0, f1, f2, f3, f4, f5, f6));'''
new_filter_ui = '''        TextView f0 = choiceChip("原色", filterPreset == 0);\n        TextView f1 = choiceChip("清晰文件", filterPreset == 1);\n        TextView f2 = choiceChip("柔白文件", filterPreset == 2);\n        TextView f3 = choiceChip("黑白掃描", filterPreset == 3);\n        TextView f4 = choiceChip("文字銳利", filterPreset == 4);\n        TextView f5 = choiceChip("自然灰階", filterPreset == 5);\n        TextView f6 = choiceChip("灰階清晰", filterPreset == 6);\n        TextView f7 = choiceChip("增強銳化", filterPreset == 7);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f4.setOnClickListener(v -> selectFilterPreset(4, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f5.setOnClickListener(v -> selectFilterPreset(5, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f6.setOnClickListener(v -> selectFilterPreset(6, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        f7.setOnClickListener(v -> selectFilterPreset(7, filterStatus, f0, f1, f2, f3, f4, f5, f6, f7));\n        page.addView(choiceStrip(f0, f1, f2, f3, f4, f5, f6, f7));\n\n        gap(page, 6);\n        Button compare = button("◐ 按住對比｜查看原圖", false);\n        compare.setOnTouchListener((v, event) -> {\n            if (resultImage == null) return false;\n            int action = event.getActionMasked();\n            if (action == MotionEvent.ACTION_DOWN) {\n                if (compareOriginalBitmap != null && !compareOriginalBitmap.isRecycled()) {\n                    resultImage.setImageBitmapKeepTransform(compareOriginalBitmap);\n                    compare.setText("◐ 原圖｜放開返回目前效果");\n                }\n                return true;\n            }\n            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {\n                if (displayBitmap != null && !displayBitmap.isRecycled()) {\n                    resultImage.setImageBitmapKeepTransform(displayBitmap);\n                }\n                compare.setText("◐ 按住對比｜查看原圖");\n                return true;\n            }\n            return true;\n        });\n        page.addView(compare, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));'''
m = must_replace(m, old_filter_ui, new_filter_ui, '8 filters + compare button')

m = must_replace(m, 'filterPreset = Math.max(0, Math.min(6, preset));',
                 'filterPreset = Math.max(0, Math.min(7, preset));',
                 'filter clamp 8')
m = must_replace(m,
    '''            case 6: return "灰階清晰";\n            default: return "清晰文件";''',
    '''            case 6: return "灰階清晰";\n            case 7: return "增強銳化";\n            default: return "清晰文件";''',
    'filter name enhanced sharp')

# The 3 booleans provide 8 unique combinations. Preset 7 uses the previously
# unused (bright=true, sharp=false, mono=true) combination; ImageUtils recognizes
# that combination as the strong COLOR sharpen mode before monochrome handling.
m = must_replace(m,
    '''    private boolean presetBright() { return filterPreset == 1 || filterPreset == 2 || filterPreset == 3; }\n    private boolean presetSharp() { return filterPreset == 1 || filterPreset == 3 || filterPreset == 4 || filterPreset == 6; }\n    private boolean presetMonochrome() { return filterPreset == 3 || filterPreset == 5 || filterPreset == 6; }''',
    '''    private boolean presetBright() { return filterPreset == 1 || filterPreset == 2 || filterPreset == 3 || filterPreset == 7; }\n    private boolean presetSharp() { return filterPreset == 1 || filterPreset == 3 || filterPreset == 4 || filterPreset == 6; }\n    private boolean presetMonochrome() { return filterPreset == 3 || filterPreset == 5 || filterPreset == 6 || filterPreset == 7; }''',
    'preset behavior map')

m = m.replace('推薦：一般文件用「清晰文件」；陰影偏重用「柔白文件」；小字表格用「文字銳利」；影印件可試「黑白掃描」。',
              '推薦：一般文件用「清晰文件」；陰影偏重用「柔白文件」；小字表格用「文字銳利」；照片偏糊可試「增強銳化」；影印件用「黑白掃描」。')

# Build the preview directly from the original URI/crop state. Only one current
# page is kept, making hold-to-compare instant without retaining all pages in RAM.
preview_helper = '''    private Bitmap buildPreviewDocumentBitmap(int index) throws Exception {\n        if (index < 0 || index >= selectedUris.size()) throw new Exception("index");\n        Bitmap original = null;\n        Bitmap cropped = null;\n        try {\n            int target = previewQualityMaxDimension();\n            original = ImageUtils.loadBitmapExact(getContentResolver(), selectedUris.get(index), target);\n            PointF[] points = restoreCornerState(index, original);\n            if (points == null) {\n                if (aiScannedBatch) {\n                    points = new PointF[]{\n                            new PointF(0,0), new PointF(original.getWidth(),0),\n                            new PointF(original.getWidth(),original.getHeight()), new PointF(0,original.getHeight())\n                    };\n                } else {\n                    points = ImageUtils.detectDocumentCorners(original);\n                    if (points == null) points = ImageUtils.defaultCorners(original);\n                }\n            }\n            cropped = ImageUtils.perspectiveCrop(original, points, target);\n            Bitmap result = cropped;\n            cropped = null;\n            return result;\n        } finally {\n            if (cropped != null && !cropped.isRecycled()) cropped.recycle();\n            if (original != null && !original.isRecycled()) original.recycle();\n        }\n    }\n\n'''
refresh_start = '    private void refreshPreview() {'
if refresh_start not in m:
    raise SystemExit('v158 patch failed: refreshPreview anchor')
m = m.replace(refresh_start, preview_helper + refresh_start, 1)

new_refresh = '''    private void refreshPreview() {\n        if (resultImage == null || croppedFiles.isEmpty()) return;\n        if (previewCounter != null) previewCounter.setText((previewIndex+1) + " / " + croppedFiles.size());\n        final int token = ++previewToken;\n        final int idx = previewIndex;\n        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();\n\n        previewExecutor.execute(() -> {\n            if (token != previewToken) return;\n            Bitmap base = null;\n            Bitmap enhanced = null;\n            boolean newBase = false;\n            try {\n                Bitmap existing = compareOriginalBitmap;\n                if (existing != null && !existing.isRecycled() && compareOriginalIndex == idx) {\n                    base = existing;\n                } else {\n                    base = buildPreviewDocumentBitmap(idx);\n                    newBase = true;\n                }\n                if (token != previewToken || base == null || base.isRecycled()) {\n                    if (newBase && base != null && !base.isRecycled()) base.recycle();\n                    return;\n                }\n\n                enhanced = ImageUtils.enhance(base, bright, sharp, monochrome);\n                if (enhanced == null) enhanced = base.copy(Bitmap.Config.ARGB_8888, false);\n                if (token != previewToken) {\n                    if (enhanced != base && !enhanced.isRecycled()) enhanced.recycle();\n                    if (newBase && base != null && !base.isRecycled()) base.recycle();\n                    return;\n                }\n\n                final Bitmap finalBase = base;\n                final Bitmap finalEnhanced = enhanced;\n                final boolean finalNewBase = newBase;\n                runOnUiThread(() -> {\n                    if (token != previewToken || resultImage == null) {\n                        if (finalEnhanced != finalBase && !finalEnhanced.isRecycled()) finalEnhanced.recycle();\n                        if (finalNewBase && !finalBase.isRecycled()) finalBase.recycle();\n                        return;\n                    }\n\n                    Bitmap oldDisplay = displayBitmap;\n                    Bitmap oldOriginal = compareOriginalBitmap;\n                    compareOriginalBitmap = finalBase;\n                    compareOriginalIndex = idx;\n                    displayBitmap = finalEnhanced;\n                    resultImage.setImageBitmap(displayBitmap);\n\n                    if (oldDisplay != null && oldDisplay != oldOriginal && oldDisplay != finalBase && oldDisplay != finalEnhanced && !oldDisplay.isRecycled()) oldDisplay.recycle();\n                    if (oldOriginal != null && oldOriginal != finalBase && oldOriginal != finalEnhanced && !oldOriginal.isRecycled()) oldOriginal.recycle();\n                });\n            } catch (Throwable ignored) {\n                if (enhanced != null && enhanced != base && !enhanced.isRecycled()) enhanced.recycle();\n                if (newBase && base != null && !base.isRecycled()) base.recycle();\n            }\n        });\n    }\n\n'''
m = replace_between(m, '    private void refreshPreview() {', '    private File createEnhancedShareFile(', new_refresh,
                    'HQ preview refresh')

# Release compare bitmap safely. It may be the same object as displayBitmap.
old_release = '''    private void releaseWorkingBitmaps() {\n        if (resultImage != null) resultImage.setImageDrawable(null);\n        if (displayBitmap != null && !displayBitmap.isRecycled()) displayBitmap.recycle();\n        displayBitmap = null;\n        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();\n        sourceBitmap = null;\n    }'''
new_release = '''    private void releaseWorkingBitmaps() {\n        if (resultImage != null) resultImage.setImageDrawable(null);\n        Bitmap oldDisplay = displayBitmap;\n        Bitmap oldOriginal = compareOriginalBitmap;\n        displayBitmap = null;\n        compareOriginalBitmap = null;\n        compareOriginalIndex = -1;\n        if (oldDisplay != null && !oldDisplay.isRecycled()) oldDisplay.recycle();\n        if (oldOriginal != null && oldOriginal != oldDisplay && !oldOriginal.isRecycled()) oldOriginal.recycle();\n        if (sourceBitmap != null && !sourceBitmap.isRecycled()) sourceBitmap.recycle();\n        sourceBitmap = null;\n    }'''
m = must_replace(m, old_release, new_release, 'release compare bitmap')

m = m.replace('PIXEL DOC TOOL  v1.5.7', 'PIXEL DOC TOOL  v1.5.8')
m = m.replace('預覽採輕量處理；儲存與分享會重新讀取原始圖片並以高畫質輸出。',
              '預覽會從原始圖片重建高畫質目前頁；儲存與分享仍重新讀取原始圖片輸出。')
MAIN.write_text(m, encoding='utf-8')

# -----------------------------------------------------------------------------
# ZoomableImageView: swapping original/filtered compare frames must preserve zoom.
# -----------------------------------------------------------------------------
old_zoom = '''    @Override public void setImageBitmap(Bitmap bm) {\n        super.setImageBitmap(bm);\n        post(this::resetZoom);\n    }'''
new_zoom = '''    @Override public void setImageBitmap(Bitmap bm) {\n        super.setImageBitmap(bm);\n        post(this::resetZoom);\n    }\n\n    public void setImageBitmapKeepTransform(Bitmap bm) {\n        // Compare mode swaps equal-sized current-page bitmaps. Keep the user's\n        // pinch zoom / pan matrix so the exact same text region stays visible.\n        super.setImageBitmap(bm);\n        invalidate();\n    }'''
z = must_replace(z, old_zoom, new_zoom, 'keep-transform compare')
ZOOM.write_text(z, encoding='utf-8')

# -----------------------------------------------------------------------------
# ImageUtils: scanner-oriented filter engine.
# -----------------------------------------------------------------------------

def iu_replace_between(start_marker, end_marker, replacement, label):
    global u
    a = u.find(start_marker)
    if a < 0:
        raise SystemExit(f'v158 image patch failed start: {label}')
    b = u.find(end_marker, a)
    if b < 0:
        raise SystemExit(f'v158 image patch failed end: {label}')
    u = u[:a] + replacement + u[b:]

new_mono = '''    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {\n        if (input == null || input.isRecycled()) return input;\n\n        // Unused boolean combination from previous presets becomes a dedicated\n        // strong COLOR sharpen mode. It is handled before monochrome conversion.\n        boolean enhancedSharpen = brighter && !sharper && monochrome;\n        if (enhancedSharpen) {\n            if (ensureOpenCv()) {\n                try { return enhanceStrongSharpOpenCv(input); } catch (Throwable ignored) {}\n            }\n            return fallbackStrongSharp(input);\n        }\n\n        Bitmap out = enhance(input, brighter, sharper);\n        if (!monochrome || out == null || out.isRecycled()) return out;\n        if (ensureOpenCv()) {\n            Mat rgba = new Mat();\n            Mat gray = new Mat();\n            Mat temp = new Mat();\n            Mat processed = new Mat();\n            try {\n                Utils.bitmapToMat(out, rgba);\n                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);\n\n                if (brighter && sharper) {\n                    // B&W scan: local contrast first, then adaptive threshold so\n                    // uneven lighting does not wash out small Chinese text.\n                    CLAHE clahe = Imgproc.createCLAHE(1.75, new Size(8,8));\n                    clahe.apply(gray, temp);\n                    int block = Math.max(25, Math.min(71, (Math.min(out.getWidth(), out.getHeight()) / 30) | 1));\n                    Imgproc.adaptiveThreshold(temp, processed, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,\n                            Imgproc.THRESH_BINARY, block, 11);\n                    clahe.collectGarbage();\n                } else {\n                    // Natural Gray / Gray Clear.\n                    CLAHE clahe = Imgproc.createCLAHE(sharper ? 2.0 : 1.45, new Size(8,8));\n                    clahe.apply(gray, processed);\n                    clahe.collectGarbage();\n                    if (sharper) unsharpLuminance(processed, 0.48, 0.72, 4.0);\n                }\n\n                Imgproc.cvtColor(processed, rgba, Imgproc.COLOR_GRAY2RGBA);\n                Utils.matToBitmap(rgba, out);\n                return out;\n            } catch (Throwable ignored) {\n            } finally {\n                processed.release();\n                temp.release();\n                gray.release();\n                rgba.release();\n            }\n        }\n\n        // Basic non-OpenCV gray fallback.\n        try {\n            int w=out.getWidth(), h=out.getHeight();\n            int[] px=new int[w*h];\n            out.getPixels(px,0,w,0,0,w,h);\n            for (int i=0;i<px.length;i++) {\n                int c=px[i];\n                int y=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;\n                px[i]=Color.argb(Color.alpha(c),y,y,y);\n            }\n            out.setPixels(px,0,w,0,0,w,h);\n        } catch (Throwable ignored) {}\n        return out;\n    }\n\n'''
iu_replace_between('    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {',
                   '    private static Bitmap enhanceOpenCv(', new_mono, 'monochrome + enhanced sharpen dispatcher')

new_enhance_cv = '''    private static Bitmap enhanceOpenCv(Bitmap input, boolean brighter, boolean sharper) {\n        Mat src = new Mat();\n        Mat rgb = new Mat();\n        Mat lab = new Mat();\n        List<Mat> channels = new ArrayList<>();\n        try {\n            Utils.bitmapToMat(input, src);\n            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB);\n            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);\n            Core.split(lab, channels);\n            Mat l = channels.get(0);\n\n            if (brighter) {\n                normalizeIllumination(l);\n                applyClahe(l, 1.70);\n            } else if (sharper) {\n                // Text-sharp mode: improve local luminance contrast without\n                // whitening colored stamps/signatures.\n                applyClahe(l, 1.45);\n                emphasizeDarkText(l, 0.24);\n            }\n\n            if (sharper) {\n                // Mild scanner sharpening for Clear / Text Sharp. Thresholding\n                // avoids sharpening flat paper noise and reduces bright halos.\n                unsharpLuminance(l, brighter ? 0.36 : 0.52, 0.72, 4.0);\n            }\n\n            Core.merge(channels, lab);\n            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);\n            Mat outRgba = new Mat();\n            try {\n                Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA);\n                Bitmap out = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);\n                Utils.matToBitmap(outRgba, out);\n                return out;\n            } finally {\n                outRgba.release();\n            }\n        } finally {\n            for (Mat ch : channels) try { ch.release(); } catch (Throwable ignored) {}\n            lab.release();\n            rgb.release();\n            src.release();\n        }\n    }\n\n    private static Bitmap enhanceStrongSharpOpenCv(Bitmap input) {\n        Mat src = new Mat();\n        Mat rgb = new Mat();\n        Mat lab = new Mat();\n        List<Mat> channels = new ArrayList<>();\n        try {\n            Utils.bitmapToMat(input, src);\n            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB);\n            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);\n            Core.split(lab, channels);\n            Mat l = channels.get(0);\n\n            // Strong mode still changes only luminance: blue/red stamps keep hue.\n            normalizeIllumination(l);\n            applyClahe(l, 2.05);\n            emphasizeDarkText(l, 0.30);\n            unsharpLuminance(l, 0.72, 0.68, 5.0);\n\n            Core.merge(channels, lab);\n            Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);\n            Mat outRgba = new Mat();\n            try {\n                Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA);\n                Bitmap out = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);\n                Utils.matToBitmap(outRgba, out);\n                return out;\n            } finally {\n                outRgba.release();\n            }\n        } finally {\n            for (Mat ch : channels) try { ch.release(); } catch (Throwable ignored) {}\n            lab.release();\n            rgb.release();\n            src.release();\n        }\n    }\n\n    private static void normalizeIllumination(Mat l) {\n        Mat small = new Mat();\n        Mat backgroundSmall = new Mat();\n        Mat background = new Mat();\n        Mat normalized = new Mat();\n        try {\n            int longest = Math.max(l.cols(), l.rows());\n            double scale = longest > 760 ? 760.0 / longest : 1.0;\n            if (scale < 0.999) Imgproc.resize(l, small, new Size(), scale, scale, Imgproc.INTER_AREA);\n            else l.copyTo(small);\n\n            int shortSide = Math.max(1, Math.min(small.cols(), small.rows()));\n            int k = Math.max(31, Math.min(91, (shortSide / 11) | 1));\n            if ((k & 1) == 0) k++;\n            Imgproc.blur(small, backgroundSmall, new Size(k,k));\n            Imgproc.resize(backgroundSmall, background, l.size(), 0, 0, Imgproc.INTER_LINEAR);\n            Core.add(background, Scalar.all(1.0), background);\n            Core.divide(l, background, normalized, 238.0);\n            normalized.convertTo(l, CvType.CV_8U);\n        } finally {\n            normalized.release();\n            background.release();\n            backgroundSmall.release();\n            small.release();\n        }\n    }\n\n    private static void applyClahe(Mat l, double clipLimit) {\n        CLAHE clahe = Imgproc.createCLAHE(clipLimit, new Size(8,8));\n        Mat out = new Mat();\n        try {\n            clahe.apply(l, out);\n            out.copyTo(l);\n        } finally {\n            out.release();\n            clahe.collectGarbage();\n        }\n    }\n\n    private static void emphasizeDarkText(Mat l, double weight) {\n        Mat blackHat = new Mat();\n        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7,7));\n        try {\n            Imgproc.morphologyEx(l, blackHat, Imgproc.MORPH_BLACKHAT, kernel);\n            Core.addWeighted(l, 1.0, blackHat, -weight, 0, l);\n        } finally {\n            kernel.release();\n            blackHat.release();\n        }\n    }\n\n    private static void unsharpLuminance(Mat l, double amount, double sigma, double edgeThreshold) {\n        Mat blur = new Mat();\n        Mat sharpened = new Mat();\n        Mat detail = new Mat();\n        Mat mask = new Mat();\n        try {\n            Imgproc.GaussianBlur(l, blur, new Size(0,0), sigma);\n            Core.addWeighted(l, 1.0 + amount, blur, -amount, 0, sharpened);\n            Core.absdiff(l, blur, detail);\n            Imgproc.threshold(detail, mask, edgeThreshold, 255, Imgproc.THRESH_BINARY);\n            sharpened.copyTo(l, mask);\n        } finally {\n            mask.release();\n            detail.release();\n            sharpened.release();\n            blur.release();\n        }\n    }\n\n    private static Bitmap fallbackStrongSharp(Bitmap input) {\n        Bitmap first = fallbackEnhance(input, false, true);\n        if (first == null || first.isRecycled()) return first;\n        Bitmap second = fallbackEnhance(first, false, true);\n        if (second != first && !first.isRecycled()) first.recycle();\n        return second;\n    }\n\n'''
iu_replace_between('    private static Bitmap enhanceOpenCv(', '    private static Bitmap fallbackEnhance(',
                   new_enhance_cv, 'document filter engine')

IMAGE.write_text(u, encoding='utf-8')

# -----------------------------------------------------------------------------
# Version and source-level sanity checks.
# -----------------------------------------------------------------------------
b = BUILD.read_text(encoding='utf-8')
b, n1 = re.subn(r'versionCode\s+\d+', 'versionCode 158', b, count=1)
b, n2 = re.subn(r"versionName\s+'[^']+'", "versionName '1.5.8'", b, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit(f'v158 version patch failed: code={n1}, name={n2}')
BUILD.write_text(b, encoding='utf-8')

check_main = MAIN.read_text(encoding='utf-8')
check_image = IMAGE.read_text(encoding='utf-8')
check_zoom = ZOOM.read_text(encoding='utf-8')
assert '增強銳化' in check_main
assert '按住對比｜查看原圖' in check_main
assert 'buildPreviewDocumentBitmap' in check_main
assert 'ImageUtils.loadBitmapExact' in check_main
assert 'compareOriginalBitmap' in check_main
assert 'setImageBitmapKeepTransform' in check_zoom
assert 'normalizeIllumination' in check_image
assert 'MORPH_BLACKHAT' in check_image
assert 'unsharpLuminance' in check_image
assert 'adaptiveThreshold(temp, processed' in check_image
assert 'enhanceStrongSharpOpenCv' in check_image
print('v1.5.8 HQ preview / compare / filter engine applied and self-reviewed')
