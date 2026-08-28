from pathlib import Path
import re

# v1.5.6: better filters, true HQ output, faster two-stage detection,
# and re-check ML Kit scanned pages instead of assuming they are perfectly cropped.

MAIN = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
IMAGE = Path('app/src/main/java/com/quanyi/docscanner/ImageUtils.java')
BUILD = Path('app/build.gradle')

m = MAIN.read_text(encoding='utf-8')
u = IMAGE.read_text(encoding='utf-8')


def must_replace(text, old, new, label, count=1):
    if old not in text:
        raise SystemExit(f'v156 patch failed: {label}')
    return text.replace(old, new, count)


def replace_between(text, start_marker, end_marker, replacement, label):
    a = text.find(start_marker)
    if a < 0:
        raise SystemExit(f'v156 patch failed start: {label}')
    b = text.find(end_marker, a)
    if b < 0:
        raise SystemExit(f'v156 patch failed end: {label}')
    return text[:a] + replacement + text[b:]

# -----------------------------------------------------------------------------
# MainActivity: lighter editing path, exact high-resolution final decode.
# Preview remains intentionally lighter; final output is rebuilt from source.
# -----------------------------------------------------------------------------
old_dims = '''    private int sourceMaxDimension() {\n        // Crop UI uses a lighter bitmap. Final output is reloaded from the original file.\n        if (lowMemoryMode) return 1800;\n        return Build.VERSION.SDK_INT <= 30 ? 2200 : 2800;\n    }\n    private int previewMaxDimension() {\n        // Preview is sharper than v1.5.1 but still bounded for older phones.\n        if (lowMemoryMode) return 1050;\n        return Build.VERSION.SDK_INT <= 30 ? 1350 : 1750;\n    }\n    private int outputMaxDimension() {\n        // High quality is only used during confirm/save flow, not while dragging.\n        if (lowMemoryMode) return 3400;\n        return Build.VERSION.SDK_INT <= 30 ? 4100 : 4800;\n    }'''
new_dims = '''    private int sourceMaxDimension() {\n        // Editing/detection bitmap: deliberately light for responsive dragging and analysis.\n        if (lowMemoryMode) return 1600;\n        return Build.VERSION.SDK_INT <= 30 ? 2000 : 2400;\n    }\n    private int previewMaxDimension() {\n        // Result preview is not the file that gets saved.\n        if (lowMemoryMode) return 1000;\n        return Build.VERSION.SDK_INT <= 30 ? 1300 : 1650;\n    }\n    private int outputMaxDimension() {\n        // Final save/share reloads directly from the original URI at a much higher target.\n        if (lowMemoryMode) return 3600;\n        return Build.VERSION.SDK_INT <= 30 ? 4600 : 5600;\n    }'''
m = must_replace(m, old_dims, new_dims, 'quality/performance dimensions')

# ML Kit FULL scanner is excellent, but some gallery imports can still return a page
# with visible desk/background. Always verify with our own detector on first visit.
pattern = re.compile(
    r'''                PointF\[\] corners = restoreCornerState\(currentIndex, b\);\n'''
    r'''                if \(corners == null && aiScannedBatch\) \{.*?'''
    r'''                \} else if \(corners == null\) \{\n'''
    r'''                    corners = ImageUtils\.detectDocumentCorners\(b\);\n'''
    r'''                \}''',
    re.S,
)
replacement = '''                PointF[] corners = restoreCornerState(currentIndex, b);\n                if (corners == null) {\n                    // Verify every first-load page, including ML Kit / AI scanner results.\n                    // This fixes the case where the AI result still contains a visible desk/background.\n                    corners = ImageUtils.detectDocumentCorners(b);\n                    if (corners == null) corners = ImageUtils.defaultCorners(b);\n                }'''
m, n = pattern.subn(replacement, m, count=1)
if n != 1:
    raise SystemExit(f'v156 patch failed: AI crop verification block count={n}')

# Final output must never rebuild from the lightweight preview JPEG.
m = must_replace(
    m,
    'original = ImageUtils.loadBitmap(getContentResolver(), selectedUris.get(index), outputMaxDimension());',
    'original = ImageUtils.loadBitmapExact(getContentResolver(), selectedUris.get(index), outputMaxDimension());',
    'exact final decode')

old_final_fallback = '''            if (points == null) {\n                if (aiScannedBatch) {\n                    float ix = Math.max(2f, original.getWidth() * 0.003f);\n                    float iy = Math.max(2f, original.getHeight() * 0.003f);\n                    points = new PointF[]{\n                            new PointF(ix, iy), new PointF(original.getWidth()-ix, iy),\n                            new PointF(original.getWidth()-ix, original.getHeight()-iy), new PointF(ix, original.getHeight()-iy)\n                    };\n                } else {\n                    points = ImageUtils.detectDocumentCorners(original);\n                    if (points == null) points = ImageUtils.defaultCorners(original);\n                }\n            }\n            cropped = ImageUtils.perspectiveCrop(original, points);'''
new_final_fallback = '''            if (points == null) {\n                points = ImageUtils.detectDocumentCorners(original);\n                if (points == null) points = ImageUtils.defaultCorners(original);\n            }\n            // HQ path uses the high-resolution source and does not inherit preview resolution.\n            cropped = ImageUtils.perspectiveCrop(original, points, outputMaxDimension());'''
m = must_replace(m, old_final_fallback, new_final_fallback, 'HQ crop rebuild')

# -----------------------------------------------------------------------------
# Filters: expand 4 presets to 7 genuinely different useful document looks.
# -----------------------------------------------------------------------------
old_filter_ui = '''        TextView f0 = choiceChip("原色", filterPreset == 0);\n        TextView f1 = choiceChip("清晰文件", filterPreset == 1);\n        TextView f2 = choiceChip("柔白文件", filterPreset == 2);\n        TextView f3 = choiceChip("黑白文件", filterPreset == 3);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus, f0, f1, f2, f3));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus, f0, f1, f2, f3));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus, f0, f1, f2, f3));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus, f0, f1, f2, f3));\n        page.addView(choiceStrip(f0, f1, f2, f3));'''
new_filter_ui = '''        TextView f0 = choiceChip("原色", filterPreset == 0);\n        TextView f1 = choiceChip("清晰文件", filterPreset == 1);\n        TextView f2 = choiceChip("柔白文件", filterPreset == 2);\n        TextView f3 = choiceChip("黑白掃描", filterPreset == 3);\n        TextView f4 = choiceChip("文字銳利", filterPreset == 4);\n        TextView f5 = choiceChip("自然灰階", filterPreset == 5);\n        TextView f6 = choiceChip("灰階清晰", filterPreset == 6);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f4.setOnClickListener(v -> selectFilterPreset(4, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f5.setOnClickListener(v -> selectFilterPreset(5, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        f6.setOnClickListener(v -> selectFilterPreset(6, filterStatus, f0, f1, f2, f3, f4, f5, f6));\n        page.addView(choiceStrip(f0, f1, f2, f3, f4, f5, f6));'''
m = must_replace(m, old_filter_ui, new_filter_ui, '7 filter chips')

m = must_replace(m, 'filterPreset = Math.max(0, Math.min(3, preset));',
                 'filterPreset = Math.max(0, Math.min(6, preset));', 'filter clamp')

old_filter_helpers = '''    private String filterName(int preset) {\n        switch (preset) {\n            case 0: return "原色";\n            case 2: return "柔白文件";\n            case 3: return "黑白文件";\n            default: return "清晰文件";\n        }\n    }'''
new_filter_helpers = '''    private String filterName(int preset) {\n        switch (preset) {\n            case 0: return "原色";\n            case 2: return "柔白文件";\n            case 3: return "黑白掃描";\n            case 4: return "文字銳利";\n            case 5: return "自然灰階";\n            case 6: return "灰階清晰";\n            default: return "清晰文件";\n        }\n    }'''
m = must_replace(m, old_filter_helpers, new_filter_helpers, 'filter names')

m = must_replace(m,
    '    private boolean presetBright() { return filterPreset == 1 || filterPreset == 2 || filterPreset == 3; }\n'
    '    private boolean presetSharp() { return filterPreset == 1 || filterPreset == 3; }\n'
    '    private boolean presetMonochrome() { return filterPreset == 3; }',
    '    private boolean presetBright() { return filterPreset == 1 || filterPreset == 2 || filterPreset == 3; }\n'
    '    private boolean presetSharp() { return filterPreset == 1 || filterPreset == 3 || filterPreset == 4 || filterPreset == 6; }\n'
    '    private boolean presetMonochrome() { return filterPreset == 3 || filterPreset == 5 || filterPreset == 6; }',
    'filter behavior map')

m = m.replace('推薦：一般文件用「清晰文件」；陰影偏重可用「柔白文件」；影印資料可試「黑白文件」。',
              '推薦：一般文件用「清晰文件」；陰影偏重用「柔白文件」；小字表格用「文字銳利」；影印件可試「黑白掃描」。')

# Final JPEG should not be the quality bottleneck. Preview cache stays at JPEG 96.
m = m.replace('lowMemoryMode ? 98 : 99', 'lowMemoryMode ? 99 : 100')
m = m.replace('lowMemoryMode ? 97 : 99', 'lowMemoryMode ? 99 : 100')

# Version / visible wording.
m = m.replace('PIXEL DOC TOOL  v1.5.5', 'PIXEL DOC TOOL  v1.5.6')
m = m.replace('預覽採輕量處理，儲存與分享仍使用完整裁切畫質。',
              '預覽採輕量處理；儲存與分享會重新讀取原始圖片並以高畫質輸出。')
MAIN.write_text(m, encoding='utf-8')

# -----------------------------------------------------------------------------
# ImageUtils: exact-size final decoder on Android 9+, faster two-stage detection,
# improved scoring against full-frame/background false positives, and stronger
# yet less destructive filters.
# -----------------------------------------------------------------------------

# Add exact target-size decoder. ImageDecoder honors EXIF orientation and avoids
# BitmapFactory's power-of-two downsample jump (e.g. 8000px -> 4000px).
load_anchor = '    static PointF[] defaultCorners(Bitmap b) {'
load_exact = '''    static Bitmap loadBitmapExact(ContentResolver resolver, Uri uri, int maxDim) throws Exception {\n        if (android.os.Build.VERSION.SDK_INT >= 28) {\n            android.graphics.ImageDecoder.Source source = android.graphics.ImageDecoder.createSource(resolver, uri);\n            return android.graphics.ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {\n                android.util.Size size = info.getSize();\n                int w = Math.max(1, size.getWidth());\n                int h = Math.max(1, size.getHeight());\n                int longest = Math.max(w, h);\n                if (longest > maxDim) {\n                    float scale = maxDim / (float)longest;\n                    decoder.setTargetSize(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)));\n                }\n                decoder.setAllocator(android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE);\n            });\n        }\n        return loadBitmap(resolver, uri, maxDim);\n    }\n\n'''
if load_anchor not in u:
    raise SystemExit('v156 patch failed: loadBitmapExact anchor')
u = u.replace(load_anchor, load_exact + load_anchor, 1)

# Replace the detector with a two-stage pipeline: obvious documents exit early;
# hard pages get extra thresholds, shadow maps and Hough support.
new_detector = '''    static PointF[] detectDocumentCorners(Bitmap src) {\n        if (src == null || src.isRecycled()) return null;\n        if (!ensureOpenCv()) return fallbackDetectDocumentCorners(src);\n\n        Bitmap detectBitmap = null;\n        Mat rgba = new Mat();\n        Mat rgb = new Mat();\n        Mat gray = new Mat();\n        Mat hsv = new Mat();\n        Mat edgeReference = new Mat();\n        ArrayList<Mat> maps = new ArrayList<>();\n        try {\n            final int maxDetectDim = 1280;\n            double scale = Math.min(1.0, maxDetectDim / (double)Math.max(src.getWidth(), src.getHeight()));\n            int dw = Math.max(32, (int)Math.round(src.getWidth() * scale));\n            int dh = Math.max(32, (int)Math.round(src.getHeight() * scale));\n            detectBitmap = scale < 1.0 ? Bitmap.createScaledBitmap(src, dw, dh, true) : src;\n\n            Utils.bitmapToMat(detectBitmap, rgba);\n            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);\n            Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY);\n            Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV);\n            Imgproc.GaussianBlur(gray, gray, new Size(5,5), 0);\n\n            Imgproc.Canny(gray, edgeReference, 32, 104);\n            Mat edgeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));\n            Imgproc.morphologyEx(edgeReference, edgeReference, Imgproc.MORPH_CLOSE, edgeKernel);\n            edgeKernel.release();\n\n            ArrayList<Candidate> candidates = new ArrayList<>();\n\n            // Fast stage: enough for normal A4 / invoices / cards.\n            int[][] fastCanny = {{20,60},{38,114},{64,192}};\n            for (int[] t : fastCanny) {\n                Mat edge = new Mat();\n                Imgproc.Canny(gray, edge, t[0], t[1]);\n                Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));\n                Imgproc.morphologyEx(edge, edge, Imgproc.MORPH_CLOSE, kernel);\n                kernel.release();\n                maps.add(edge);\n                findQuadCandidates(edge, rgba.cols(), rgba.rows(), 0, candidates);\n            }\n\n            Mat adaptive = new Mat();\n            Imgproc.adaptiveThreshold(gray, adaptive, 255,\n                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 41, 9);\n            Mat adaptiveKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(9,9));\n            Imgproc.morphologyEx(adaptive, adaptive, Imgproc.MORPH_CLOSE, adaptiveKernel);\n            adaptiveKernel.release();\n            maps.add(adaptive);\n            findQuadCandidates(adaptive, rgba.cols(), rgba.rows(), 1, candidates);\n\n            Mat paperMask = new Mat();\n            Core.inRange(hsv, new Scalar(0, 0, 92), new Scalar(180, 155, 255), paperMask);\n            Mat paperClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(21,21));\n            Mat paperOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5,5));\n            Imgproc.morphologyEx(paperMask, paperMask, Imgproc.MORPH_CLOSE, paperClose);\n            Imgproc.morphologyEx(paperMask, paperMask, Imgproc.MORPH_OPEN, paperOpen);\n            paperClose.release();\n            paperOpen.release();\n            maps.add(paperMask);\n            findQuadCandidates(paperMask, rgba.cols(), rgba.rows(), 3, candidates);\n\n            Candidate best = chooseBestCandidate(candidates, gray, hsv, edgeReference, rgba.cols(), rgba.rows());\n            Candidate lineCandidate = detectByDominantLines(edgeReference, gray, hsv, rgba.cols(), rgba.rows());\n            if (lineCandidate != null && (best == null || lineCandidate.score > best.score)) best = lineCandidate;\n\n            // Hard-stage only when confidence is not already strong.\n            if (best == null || best.score < 70.0) {\n                for (int[] t : new int[][]{{12,36},{92,255}}) {\n                    Mat edge = new Mat();\n                    Imgproc.Canny(gray, edge, t[0], t[1]);\n                    Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7,7));\n                    Imgproc.morphologyEx(edge, edge, Imgproc.MORPH_CLOSE, kernel);\n                    kernel.release();\n                    maps.add(edge);\n                    findQuadCandidates(edge, rgba.cols(), rgba.rows(), 0, candidates);\n                }\n\n                Mat edgeWide = edgeReference.clone();\n                Mat edgeWideKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(11,11));\n                Imgproc.morphologyEx(edgeWide, edgeWide, Imgproc.MORPH_CLOSE, edgeWideKernel);\n                edgeWideKernel.release();\n                maps.add(edgeWide);\n                findQuadCandidates(edgeWide, rgba.cols(), rgba.rows(), 0, candidates);\n\n                Mat adaptive2 = new Mat();\n                Imgproc.adaptiveThreshold(gray, adaptive2, 255,\n                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 61, 7);\n                Mat adaptiveKernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(13,13));\n                Imgproc.morphologyEx(adaptive2, adaptive2, Imgproc.MORPH_CLOSE, adaptiveKernel2);\n                adaptiveKernel2.release();\n                maps.add(adaptive2);\n                findQuadCandidates(adaptive2, rgba.cols(), rgba.rows(), 1, candidates);\n\n                Mat otsu = new Mat();\n                Imgproc.threshold(gray, otsu, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);\n                Mat otsuKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(17,17));\n                Imgproc.morphologyEx(otsu, otsu, Imgproc.MORPH_CLOSE, otsuKernel);\n                otsuKernel.release();\n                maps.add(otsu);\n                findQuadCandidates(otsu, rgba.cols(), rgba.rows(), 2, candidates);\n\n                Mat paperShadow = new Mat();\n                Core.inRange(hsv, new Scalar(0, 0, 72), new Scalar(180, 185, 255), paperShadow);\n                Mat shadowClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(31,31));\n                Mat shadowOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7,7));\n                Imgproc.morphologyEx(paperShadow, paperShadow, Imgproc.MORPH_CLOSE, shadowClose);\n                Imgproc.morphologyEx(paperShadow, paperShadow, Imgproc.MORPH_OPEN, shadowOpen);\n                shadowClose.release();\n                shadowOpen.release();\n                maps.add(paperShadow);\n                findQuadCandidates(paperShadow, rgba.cols(), rgba.rows(), 3, candidates);\n\n                Candidate hardBest = chooseBestCandidate(candidates, gray, hsv, edgeReference, rgba.cols(), rgba.rows());\n                if (hardBest != null && (best == null || hardBest.score > best.score)) best = hardBest;\n                lineCandidate = detectByDominantLines(edgeWide, gray, hsv, rgba.cols(), rgba.rows());\n                if (lineCandidate != null && (best == null || lineCandidate.score > best.score)) best = lineCandidate;\n            }\n\n            if (best == null || best.score < 34.0) return fallbackDetectDocumentCorners(src);\n\n            Point[] expanded = expandQuad(best.points, rgba.cols(), rgba.rows(), 0.0035);\n            float inv = (float)(1.0 / scale);\n            PointF[] result = new PointF[4];\n            for (int i=0;i<4;i++) {\n                result[i] = new PointF((float)expanded[i].x * inv, (float)expanded[i].y * inv);\n            }\n            if (!validQuad(result, src.getWidth(), src.getHeight())) return fallbackDetectDocumentCorners(src);\n            return result;\n        } catch (Throwable ignored) {\n            return fallbackDetectDocumentCorners(src);\n        } finally {\n            for (Mat mat : maps) try { mat.release(); } catch (Throwable ignored) {}\n            try { edgeReference.release(); } catch (Throwable ignored) {}\n            try { hsv.release(); } catch (Throwable ignored) {}\n            try { gray.release(); } catch (Throwable ignored) {}\n            try { rgb.release(); } catch (Throwable ignored) {}\n            try { rgba.release(); } catch (Throwable ignored) {}\n            if (detectBitmap != null && detectBitmap != src && !detectBitmap.isRecycled()) detectBitmap.recycle();\n        }\n    }\n\n'''
u = replace_between(u, '    static PointF[] detectDocumentCorners(Bitmap src) {',
                    '    private static void findQuadCandidates(', new_detector,
                    'two-stage document detector')

# Scoring: large frame-filling rectangles should not beat a bright paper rectangle
# merely because they cover more pixels. This directly targets the supplied screenshot.
old_score_return = '''            return areaScore * 38.0\n                    + angleScore * 15.0\n                    + rectScore * 7.0\n                    + aspectScore * 6.0\n                    + centerScore * 4.0\n                    + edgeSupport * 17.0\n                    + paperBrightness * 4.0\n                    + paperLowSaturation * 4.0\n                    + contrastScore * 5.0\n                    + sourceBonus;'''
new_score_return = '''            double minX = w, minY = h, maxX = 0, maxY = 0;\n            for (Point q : p) {\n                minX = Math.min(minX, q.x); minY = Math.min(minY, q.y);\n                maxX = Math.max(maxX, q.x); maxY = Math.max(maxY, q.y);\n            }\n            double frameMargin = Math.min(Math.min(minX / Math.max(1.0,w), (w-maxX) / Math.max(1.0,w)),\n                    Math.min(minY / Math.max(1.0,h), (h-maxY) / Math.max(1.0,h)));\n            double nearFramePenalty = 0.0;\n            if (areaRatio > 0.86 && frameMargin < 0.018) {\n                nearFramePenalty = (1.0 - clamp01(edgeSupport / 0.28)) * 16.0\n                        + (1.0 - contrastScore) * 8.0;\n            }\n            double extremeAreaPenalty = areaRatio > 0.94 ? clamp01((areaRatio - 0.94) / 0.06) * 6.0 : 0.0;\n\n            return areaScore * 30.0\n                    + angleScore * 15.0\n                    + rectScore * 8.0\n                    + aspectScore * 7.0\n                    + centerScore * 4.0\n                    + edgeSupport * 19.0\n                    + paperBrightness * 5.0\n                    + paperLowSaturation * 4.0\n                    + contrastScore * 10.0\n                    + sourceBonus\n                    - nearFramePenalty\n                    - extremeAreaPenalty;'''
u = must_replace(u, old_score_return, new_score_return, 'document candidate scoring')

# HQ perspective crop overload. Preview keeps the old 4096 cap; final output supplies
# its own larger limit and uses anti-alias/filter/dither without a preview JPEG round-trip.
new_crop_methods = '''    static Bitmap perspectiveCrop(Bitmap src, PointF[] p) {\n        return perspectiveCrop(src, p, 4096);\n    }\n\n    static Bitmap perspectiveCrop(Bitmap src, PointF[] p, int maxOutputDim) {\n        float top=dist(p[0],p[1]), bottom=dist(p[3],p[2]);\n        float left=dist(p[0],p[3]), right=dist(p[1],p[2]);\n        int ow=Math.max(64, Math.round((top+bottom)/2f));\n        int oh=Math.max(64, Math.round((left+right)/2f));\n        int safeMax = Math.max(1024, maxOutputDim);\n        float limit=safeMax/(float)Math.max(ow,oh);\n        if(limit<1f){ow=Math.max(64,Math.round(ow*limit));oh=Math.max(64,Math.round(oh*limit));}\n        Bitmap out=Bitmap.createBitmap(ow,oh,Bitmap.Config.ARGB_8888);\n        Canvas c=new Canvas(out);\n        c.drawColor(Color.WHITE);\n        float[] s={p[0].x,p[0].y,p[1].x,p[1].y,p[2].x,p[2].y,p[3].x,p[3].y};\n        float[] d={0,0,ow,0,ow,oh,0,oh};\n        Matrix matrix=new Matrix();\n        if(!matrix.setPolyToPoly(s,0,d,0,4)) {\n            out.recycle();\n            return src.copy(Bitmap.Config.ARGB_8888,false);\n        }\n        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);\n        c.drawBitmap(src,matrix,paint);\n        return out;\n    }\n\n'''
u = replace_between(u, '    static Bitmap perspectiveCrop(Bitmap src, PointF[] p) {',
                    '    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper) {',
                    new_crop_methods, 'HQ perspective crop overload')

# Faster color enhancement: local illumination correction on Lab luminance with a
# bounded box blur (much cheaper than an enormous Gaussian), then moderate CLAHE.
new_enhance_cv = '''    private static Bitmap enhanceOpenCv(Bitmap input, boolean brighter, boolean sharper) {\n        Mat src = new Mat();\n        Mat rgb = new Mat();\n        try {\n            Utils.bitmapToMat(input, src);\n            Imgproc.cvtColor(src, rgb, Imgproc.COLOR_RGBA2RGB);\n\n            if (brighter) {\n                Mat lab = new Mat();\n                Mat background = new Mat();\n                Mat normalized = new Mat();\n                List<Mat> channels = new ArrayList<>();\n                try {\n                    Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab);\n                    Core.split(lab, channels);\n                    Mat l = channels.get(0);\n                    int k = Math.max(25, Math.min(81, (Math.min(input.getWidth(), input.getHeight()) / 26) | 1));\n                    Imgproc.blur(l, background, new Size(k,k));\n                    Core.add(background, Scalar.all(1.0), background);\n                    Core.divide(l, background, normalized, 238.0);\n                    CLAHE clahe = Imgproc.createCLAHE(1.85, new Size(8,8));\n                    Mat enhancedL = new Mat();\n                    clahe.apply(normalized, enhancedL);\n                    enhancedL.convertTo(enhancedL, -1, 1.03, 6.0);\n                    l.release();\n                    channels.set(0, enhancedL);\n                    Core.merge(channels, lab);\n                    Imgproc.cvtColor(lab, rgb, Imgproc.COLOR_Lab2RGB);\n                } finally {\n                    for (Mat channel : channels) try { channel.release(); } catch (Throwable ignored) {}\n                    normalized.release();\n                    background.release();\n                    lab.release();\n                }\n            }\n\n            if (sharper) {\n                Mat blur = new Mat();\n                try {\n                    Imgproc.GaussianBlur(rgb, blur, new Size(0,0), 0.72);\n                    Core.addWeighted(rgb, 1.42, blur, -0.42, 0, rgb);\n                } finally {\n                    blur.release();\n                }\n            }\n\n            Mat outRgba = new Mat();\n            try {\n                Imgproc.cvtColor(rgb, outRgba, Imgproc.COLOR_RGB2RGBA);\n                Bitmap out = Bitmap.createBitmap(input.getWidth(), input.getHeight(), Bitmap.Config.ARGB_8888);\n                Utils.matToBitmap(outRgba, out);\n                return out;\n            } finally {\n                outRgba.release();\n            }\n        } finally {\n            rgb.release();\n            src.release();\n        }\n    }\n\n'''
u = replace_between(u, '    private static Bitmap enhanceOpenCv(',
                    '    private static Bitmap fallbackEnhance(', new_enhance_cv,
                    'faster stronger OpenCV enhancement')

# Three monochrome behaviors now differ:
# B&W Scan = adaptive threshold; Natural Gray = CLAHE gray; Gray Clear = CLAHE + unsharp.
new_mono = '''    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {\n        Bitmap out = enhance(input, brighter, sharper);\n        if (!monochrome || out == null || out.isRecycled()) return out;\n        if (ensureOpenCv()) {\n            Mat rgba = new Mat();\n            Mat gray = new Mat();\n            Mat processed = new Mat();\n            Mat temp = new Mat();\n            try {\n                Utils.bitmapToMat(out, rgba);\n                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);\n                if (brighter && sharper) {\n                    // Strong scanner-like black/white for photocopies and text-heavy sheets.\n                    Imgproc.GaussianBlur(gray, temp, new Size(3,3), 0);\n                    int block = Math.max(21, Math.min(61, (Math.min(out.getWidth(), out.getHeight()) / 28) | 1));\n                    Imgproc.adaptiveThreshold(temp, processed, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,\n                            Imgproc.THRESH_BINARY, block, 11);\n                } else {\n                    CLAHE clahe = Imgproc.createCLAHE(sharper ? 2.15 : 1.65, new Size(8,8));\n                    clahe.apply(gray, processed);\n                    if (sharper) {\n                        Imgproc.GaussianBlur(processed, temp, new Size(0,0), 0.75);\n                        Core.addWeighted(processed, 1.38, temp, -0.38, 0, processed);\n                    }\n                }\n                Imgproc.cvtColor(processed, rgba, Imgproc.COLOR_GRAY2RGBA);\n                Utils.matToBitmap(rgba, out);\n                return out;\n            } catch (Throwable ignored) {\n            } finally {\n                temp.release();\n                processed.release();\n                gray.release();\n                rgba.release();\n            }\n        }\n        try {\n            int w = out.getWidth(), h = out.getHeight();\n            int[] px = new int[w*h];\n            out.getPixels(px, 0, w, 0, 0, w, h);\n            for (int i=0;i<px.length;i++) {\n                int c = px[i];\n                int y = clamp((Color.red(c)*30 + Color.green(c)*59 + Color.blue(c)*11)/100);\n                if (brighter && sharper) y = y > 176 ? 255 : (y < 118 ? 0 : clamp((y-118)*4));\n                else if (sharper) y = clamp((int)((y-128)*1.18 + 128));\n                px[i] = Color.argb(255, y, y, y);\n            }\n            out.setPixels(px, 0, w, 0, 0, w, h);\n        } catch (Throwable ignored) {}\n        return out;\n    }\n\n'''
u = replace_between(u, '    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {',
                    '    private static Bitmap enhanceOpenCv(', new_mono,
                    'expanded monochrome filters')

IMAGE.write_text(u, encoding='utf-8')

# -----------------------------------------------------------------------------
# Version + source-level self-review assertions.
# -----------------------------------------------------------------------------
b = BUILD.read_text(encoding='utf-8')
b, n1 = re.subn(r'versionCode\s+\d+', 'versionCode 156', b, count=1)
b, n2 = re.subn(r"versionName\s+'[^']+'", "versionName '1.5.6'", b, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit(f'v156 patch failed: versionCode={n1}, versionName={n2}')
BUILD.write_text(b, encoding='utf-8')

# Self-review: fail the build early if any core promise is missing.
check_main = MAIN.read_text(encoding='utf-8')
check_image = IMAGE.read_text(encoding='utf-8')
assert 'versionName' not in check_main  # build.gradle owns package version
assert 'loadBitmapExact' in check_main
assert 'perspectiveCrop(original, points, outputMaxDimension())' in check_main
assert 'Math.min(6, preset)' in check_main
assert '文字銳利' in check_main and '自然灰階' in check_main and '灰階清晰' in check_main
assert 'Verify every first-load page' in check_main
assert 'final int maxDetectDim = 1280' in check_image
assert 'best.score < 70.0' in check_image
assert 'nearFramePenalty' in check_image
assert 'static Bitmap loadBitmapExact' in check_image
assert 'static Bitmap perspectiveCrop(Bitmap src, PointF[] p, int maxOutputDim)' in check_image
assert 'Imgproc.adaptiveThreshold(temp, processed' in check_image
print('v1.5.6 quality / filters / speed / detection patch applied and self-reviewed')
