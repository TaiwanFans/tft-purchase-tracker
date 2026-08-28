from pathlib import Path
import re

# One-time migration only. This script is deleted after CI proves the flattened v2 source builds.
root = Path('.')
java = root / 'app/src/main/java/com/quanyi/docscanner'
MAIN = java / 'MainActivity.java'
IMAGE = java / 'ImageUtils.java'
BUILD = root / 'app/build.gradle'

m = MAIN.read_text(encoding='utf-8')
u = IMAGE.read_text(encoding='utf-8')


def must_replace(text, old, new, label, count=1):
    if old not in text:
        raise SystemExit('v2 migration failed: ' + label)
    return text.replace(old, new, count)


def replace_between(text, start, end, replacement, label):
    a = text.find(start)
    if a < 0:
        raise SystemExit('v2 migration start missing: ' + label)
    b = text.find(end, a)
    if b < 0:
        raise SystemExit('v2 migration end missing: ' + label)
    return text[:a] + replacement + text[b:]

# -----------------------------------------------------------------------------
# MainActivity: move mutable workflow state and service responsibilities out.
# -----------------------------------------------------------------------------
old_fields = '''    private final ArrayList<Uri> selectedUris = new ArrayList<>();\n    private final ArrayList<File> croppedFiles = new ArrayList<>();\n    private final ArrayList<float[]> cropHistory = new ArrayList<>();\n    private int currentIndex = 0;\n    private int previewIndex = 0;\n    private int previewToken = 0;\n    private int filterPreset = 1; // 0 original, 1 clear, 2 soft white, 3 B&W\n    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();\n    private boolean pendingBatchSave = false;\n    private boolean lowMemoryMode = false;\n    private boolean aiScannedBatch = false;\n    private boolean returnToResultAfterEdit = false;\n'''
new_fields = '''    private final ScanSession session = new ScanSession();\n    private int previewToken = 0;\n    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();\n    private boolean pendingBatchSave = false;\n    private DeviceProfile deviceProfile;\n    private DocumentDetector documentDetector;\n    private DocumentFilterEngine filterEngine;\n    private PreviewRenderer previewRenderer;\n    private ExportManager exportManager;\n'''
m = must_replace(m, old_fields, new_fields, 'session/service fields')
m = must_replace(m, '''    private Uri pendingCameraUri;\n    private File pendingCameraFile;\n\n''', '', 'camera session fields')

# Replace state references with the single ScanSession owner.
for old, new in {
    'selectedUris': 'session.selectedUris',
    'croppedFiles': 'session.croppedFiles',
    'cropHistory': 'session.cropHistory',
    'currentIndex': 'session.currentIndex',
    'previewIndex': 'session.previewIndex',
    'filterPreset': 'session.filterPreset',
    'aiScannedBatch': 'session.aiScannedBatch',
    'returnToResultAfterEdit': 'session.returnToResultAfterEdit',
    'pendingCameraUri': 'session.pendingCameraUri',
    'pendingCameraFile': 'session.pendingCameraFile',
}.items():
    m = re.sub(r'\b' + re.escape(old) + r'\b', new, m)

# Bootstrap services once, and restore non-destructive scan state after Activity recreation.
m = must_replace(m,
'''        lowMemoryMode = detectLowMemoryDevice();\n        showHome();''',
'''        deviceProfile = DeviceProfile.detect(this);\n        documentDetector = new DocumentDetector();\n        filterEngine = new DocumentFilterEngine();\n        previewRenderer = new PreviewRenderer(getContentResolver(), documentDetector);\n        exportManager = new ExportManager(getContentResolver(), documentDetector, filterEngine);\n        if (b != null && session.restoreFromBundle(b)) {\n            String restored = b.getString("ui.screen", Screen.HOME.name());\n            try { screen = Screen.valueOf(restored); } catch (Throwable ignored) { screen = Screen.HOME; }\n            if (screen == Screen.CROP && !session.aiScannedBatch && session.currentIndex < session.selectedUris.size()) loadCurrentDocument();\n            else if (screen == Screen.RESULT && !session.croppedFiles.isEmpty()) showBatchResult();\n            else showHome();\n        } else {\n            showHome();\n        }''',
'onCreate services/state restore')

# One capability policy class owns all RAM/API dimension decisions.
a = m.find('    private boolean detectLowMemoryDevice() {')
b = m.find('    private int dp(int v)', a)
if a < 0 or b < 0:
    raise SystemExit('v2 migration failed: device profile block')
m = m[:a] + '''    private int sourceMaxDimension() { return deviceProfile.sourceMaxDimension(); }\n    private int previewMaxDimension() { return deviceProfile.fastPreviewMaxDimension(); }\n    private int outputMaxDimension() { return deviceProfile.outputMaxDimension(); }\n    private int previewQualityMaxDimension() { return deviceProfile.hqPreviewMaxDimension(); }\n    private boolean lowMemoryMode() { return deviceProfile != null && deviceProfile.lowMemoryMode; }\n\n''' + m[b:]
m = re.sub(r'\blowMemoryMode\b(?!\s*\()', 'lowMemoryMode()', m)
m = m.replace('deviceProfile.lowMemoryMode()', 'deviceProfile.lowMemoryMode')

# Crop state becomes normalized quad data owned by CropController/ScanSession.
a = m.find('    private void ensureCropHistorySize() {')
b = m.find('    private void loadCurrentDocument() {', a)
if a < 0 or b < 0:
    raise SystemExit('v2 migration failed: crop state block')
m = m[:a] + '''    private void ensureCropHistorySize() { session.ensureCropHistorySize(); }\n\n    private void saveCornerState(int index, PointF[] points, Bitmap bitmap) {\n        session.ensureCropHistorySize();\n        if (index >= 0 && index < session.cropHistory.size())\n            session.cropHistory.set(index, CropController.normalize(points, bitmap));\n    }\n\n    private PointF[] restoreCornerState(int index, Bitmap bitmap) {\n        if (index < 0 || index >= session.cropHistory.size()) return null;\n        return CropController.restore(session.cropHistory.get(index), bitmap);\n    }\n\n    private PointF[] scaleCorners(PointF[] points, float sx, float sy) {\n        return CropController.scale(points, sx, sy);\n    }\n\n''' + m[b:]

m = m.replace('ImageUtils.detectDocumentCorners(', 'documentDetector.detect(')
m = m.replace('ImageUtils.defaultCorners(', 'documentDetector.defaultCorners(')

# Filter UI uses a named FilterPreset model instead of encoding meaning in 3 booleans.
a = m.find('    private String filterName(int preset) {')
b = m.find('    private Bitmap decodePreview(', a)
if a < 0 or b < 0:
    raise SystemExit('v2 migration failed: filter helper block')
m = m[:a] + '''    private String filterName(int preset) { return FilterPreset.fromId(preset).label; }\n\n    private void selectFilterPreset(int preset, TextView status, TextView... chips) {\n        session.filterPreset = FilterPreset.fromId(preset).id;\n        for (int i=0;i<chips.length;i++) styleChoiceChip(chips[i], i == session.filterPreset);\n        status.setText("目前效果：" + filterName(session.filterPreset));\n        refreshPreview();\n    }\n\n    private FilterPreset selectedFilter() { return FilterPreset.fromId(session.filterPreset); }\n\n''' + m[b:]

# Preview renderer always rebuilds from original URI + normalized crop state.
a = m.find('    private Bitmap buildPreviewDocumentBitmap(int index) throws Exception {')
b = m.find('    private void refreshPreview() {', a)
if a < 0 or b < 0:
    raise SystemExit('v2 migration failed: preview builder')
m = m[:a] + '''    private Bitmap buildPreviewDocumentBitmap(int index) throws Exception {\n        if (index < 0 || index >= session.selectedUris.size()) throw new Exception("index");\n        float[] crop = index < session.cropHistory.size() ? session.cropHistory.get(index) : null;\n        return previewRenderer.render(session.selectedUris.get(index), crop, session.aiScannedBatch, previewQualityMaxDimension());\n    }\n\n''' + m[b:]

# First boolean triplet belongs to refreshPreview.
m = must_replace(m,
'''        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();''',
'''        final FilterPreset preset = selectedFilter();''',
'preview preset selection')
m = must_replace(m, 'enhanced = ImageUtils.enhance(base, bright, sharp, monochrome);',
                 'enhanced = filterEngine.apply(base, preset);', 'preview filter engine')

# Full-resolution export gets its own manager; it never consumes the preview bitmap/cache.
a = m.find('    private Bitmap buildFinalDocumentBitmap(')
b = m.find('    private File createEnhancedShareFile(', a)
if a < 0 or b < 0:
    raise SystemExit('v2 migration failed: final renderer')
m = m[:a] + '''    private Bitmap buildFinalDocumentBitmap(int index, FilterPreset preset) throws Exception {\n        if (index < 0 || index >= session.selectedUris.size()) throw new Exception("index");\n        float[] crop = index < session.cropHistory.size() ? session.cropHistory.get(index) : null;\n        return exportManager.renderFinal(session.selectedUris.get(index), crop, session.aiScannedBatch, preset, outputMaxDimension());\n    }\n\n''' + m[b:]

a = m.find('    private File createEnhancedShareFile(')
b = m.find('    private void shareAll() {', a)
if a < 0 or b < 0:
    raise SystemExit('v2 migration failed: share helper')
m = m[:a] + '''    private File createEnhancedShareFile(int index, FilterPreset preset) throws Exception {\n        Bitmap out = null;\n        try {\n            out = buildFinalDocumentBitmap(index, preset);\n            File dir = new File(getCacheDir(), "shared");\n            if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");\n            File f = new File(dir, String.format(Locale.US, "document_%02d_%d.jpg", index+1, System.currentTimeMillis()));\n            exportManager.writeJpeg(out, f, lowMemoryMode() ? 99 : 100);\n            return f;\n        } finally {\n            if (out != null && !out.isRecycled()) out.recycle();\n        }\n    }\n\n''' + m[b:]

# Remaining boolean triplets are share/save paths.
m = m.replace('''        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();''',
'''        final FilterPreset preset = selectedFilter();''')
m = re.sub(r'createEnhancedShareFile\(session\.croppedFiles\.get\(i\), i, bright, sharp, monochrome\)',
           'createEnhancedShareFile(i, preset)', m)
m = m.replace('buildFinalDocumentBitmap(i, bright, sharp, monochrome)', 'buildFinalDocumentBitmap(i, preset)')
m = m.replace('PIXEL DOC TOOL  v1.5.8', 'PIXEL DOC TOOL  v2.0.0')

# Preserve current work on rotation/process recreation.
back_anchor = '    @Override public void onBackPressed() {'
if back_anchor not in m:
    raise SystemExit('v2 migration failed: back anchor')
m = m.replace(back_anchor, '''    @Override protected void onSaveInstanceState(Bundle outState) {\n        saveCurrentCropStateSafely();\n        session.saveToBundle(outState);\n        outState.putString("ui.screen", screen.name());\n        super.onSaveInstanceState(outState);\n    }\n\n''' + back_anchor, 1)

MAIN.write_text(m, encoding='utf-8')

# -----------------------------------------------------------------------------
# ImageUtils keeps low-level OpenCV operations; expose semantic named entrypoints.
# The old boolean combinator remains private implementation detail only.
# -----------------------------------------------------------------------------
anchor = '    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper) {'
if anchor not in u:
    raise SystemExit('v2 migration failed: ImageUtils enhancement anchor')
u = u.replace(anchor, '''    static Bitmap clearDocument(Bitmap input) { return enhance(input, true, true, false); }\n    static Bitmap softWhiteDocument(Bitmap input) { return enhance(input, true, false, false); }\n    static Bitmap blackWhiteScan(Bitmap input) { return enhance(input, true, true, true); }\n    static Bitmap textSharp(Bitmap input) { return enhance(input, false, true, false); }\n    static Bitmap naturalGray(Bitmap input) { return enhance(input, false, false, true); }\n    static Bitmap grayClear(Bitmap input) { return enhance(input, false, true, true); }\n    static Bitmap enhancedSharpen(Bitmap input) {\n        if (input == null || input.isRecycled()) return input;\n        if (ensureOpenCv()) {\n            try { return enhanceStrongSharpOpenCv(input); } catch (Throwable ignored) {}\n        }\n        return fallbackStrongSharp(input);\n    }\n\n''' + anchor, 1)
IMAGE.write_text(u, encoding='utf-8')

# -----------------------------------------------------------------------------
# New v2 core classes.
# -----------------------------------------------------------------------------
(java / 'FilterPreset.java').write_text(r'''package com.quanyi.docscanner;

public enum FilterPreset {
    ORIGINAL(0, "原色"),
    CLEAR_DOCUMENT(1, "清晰文件"),
    SOFT_WHITE(2, "柔白文件"),
    BLACK_WHITE(3, "黑白掃描"),
    TEXT_SHARP(4, "文字銳利"),
    NATURAL_GRAY(5, "自然灰階"),
    GRAY_CLEAR(6, "灰階清晰"),
    ENHANCED_SHARP(7, "增強銳化");

    public final int id;
    public final String label;
    FilterPreset(int id, String label) { this.id = id; this.label = label; }

    public static FilterPreset fromId(int id) {
        for (FilterPreset preset : values()) if (preset.id == id) return preset;
        return CLEAR_DOCUMENT;
    }
}
''', encoding='utf-8')

(java / 'DocumentFilterEngine.java').write_text(r'''package com.quanyi.docscanner;

import android.graphics.Bitmap;

/** Semantic filter facade. UI/export code never encodes filter meaning as booleans. */
public final class DocumentFilterEngine {
    public Bitmap apply(Bitmap input, FilterPreset preset) {
        if (input == null || input.isRecycled()) return input;
        if (preset == null) preset = FilterPreset.CLEAR_DOCUMENT;
        switch (preset) {
            case ORIGINAL: return input.copy(Bitmap.Config.ARGB_8888, false);
            case SOFT_WHITE: return ImageUtils.softWhiteDocument(input);
            case BLACK_WHITE: return ImageUtils.blackWhiteScan(input);
            case TEXT_SHARP: return ImageUtils.textSharp(input);
            case NATURAL_GRAY: return ImageUtils.naturalGray(input);
            case GRAY_CLEAR: return ImageUtils.grayClear(input);
            case ENHANCED_SHARP: return ImageUtils.enhancedSharpen(input);
            case CLEAR_DOCUMENT:
            default: return ImageUtils.clearDocument(input);
        }
    }
}
''', encoding='utf-8')

(java / 'DocumentDetector.java').write_text(r'''package com.quanyi.docscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

/** Stable boundary between scan workflow and OpenCV document detection. */
public final class DocumentDetector {
    public PointF[] detect(Bitmap bitmap) { return ImageUtils.detectDocumentCorners(bitmap); }
    public PointF[] defaultCorners(Bitmap bitmap) { return ImageUtils.defaultCorners(bitmap); }
}
''', encoding='utf-8')

(java / 'CropController.java').write_text(r'''package com.quanyi.docscanner;

import android.graphics.Bitmap;
import android.graphics.PointF;

/** Non-destructive crop state. Only normalized quad coordinates are persisted. */
public final class CropController {
    private CropController() {}

    public static float[] normalize(PointF[] points, Bitmap bitmap) {
        if (bitmap == null || points == null || points.length != 4) return null;
        float w = Math.max(1f, bitmap.getWidth());
        float h = Math.max(1f, bitmap.getHeight());
        float[] out = new float[8];
        for (int i=0;i<4;i++) {
            out[i*2] = clamp01(points[i].x / w);
            out[i*2+1] = clamp01(points[i].y / h);
        }
        return out;
    }

    public static PointF[] restore(float[] normalized, Bitmap bitmap) {
        if (bitmap == null || normalized == null || normalized.length != 8) return null;
        PointF[] out = new PointF[4];
        for (int i=0;i<4;i++)
            out[i] = new PointF(normalized[i*2] * bitmap.getWidth(), normalized[i*2+1] * bitmap.getHeight());
        return out;
    }

    public static PointF[] scale(PointF[] points, float sx, float sy) {
        if (points == null || points.length != 4) return null;
        PointF[] out = new PointF[4];
        for (int i=0;i<4;i++) out[i] = new PointF(points[i].x*sx, points[i].y*sy);
        return out;
    }

    public static float[] fullFrame() { return new float[]{0f,0f, 1f,0f, 1f,1f, 0f,1f}; }
    private static float clamp01(float value) { return Math.max(0f, Math.min(1f, value)); }
}
''', encoding='utf-8')

(java / 'ScanSession.java').write_text(r'''package com.quanyi.docscanner;

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
''', encoding='utf-8')

(java / 'DeviceProfile.java').write_text(r'''package com.quanyi.docscanner;

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
''', encoding='utf-8')

(java / 'PreviewRenderer.java').write_text(r'''package com.quanyi.docscanner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;

/** HQ current-page preview rebuilt from original URI, not lossy cache JPEG. */
public final class PreviewRenderer {
    private final ContentResolver resolver;
    private final DocumentDetector detector;
    public PreviewRenderer(ContentResolver resolver, DocumentDetector detector) {
        this.resolver = resolver;
        this.detector = detector;
    }

    public Bitmap render(Uri uri, float[] normalizedCrop, boolean aiAlreadyCropped, int maxDimension) throws Exception {
        Bitmap original = null;
        Bitmap cropped = null;
        try {
            original = ImageUtils.loadBitmapExact(resolver, uri, maxDimension);
            PointF[] points = CropController.restore(normalizedCrop, original);
            if (points == null) {
                points = aiAlreadyCropped ? detector.defaultCorners(original) : detector.detect(original);
                if (points == null) points = detector.defaultCorners(original);
            }
            cropped = ImageUtils.perspectiveCrop(original, points, maxDimension);
            Bitmap result = cropped;
            cropped = null;
            return result;
        } finally {
            if (cropped != null && !cropped.isRecycled()) cropped.recycle();
            if (original != null && !original.isRecycled()) original.recycle();
        }
    }
}
''', encoding='utf-8')

(java / 'ExportManager.java').write_text(r'''package com.quanyi.docscanner;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;

/** Full-resolution pipeline: original URI -> quad -> perspective -> filter -> one JPEG encode. */
public final class ExportManager {
    private final ContentResolver resolver;
    private final DocumentDetector detector;
    private final DocumentFilterEngine filters;
    public ExportManager(ContentResolver resolver, DocumentDetector detector, DocumentFilterEngine filters) {
        this.resolver = resolver;
        this.detector = detector;
        this.filters = filters;
    }

    public Bitmap renderFinal(Uri uri, float[] normalizedCrop, boolean aiAlreadyCropped,
                              FilterPreset preset, int maxDimension) throws Exception {
        Bitmap original = null;
        Bitmap cropped = null;
        Bitmap enhanced = null;
        try {
            original = ImageUtils.loadBitmapExact(resolver, uri, maxDimension);
            PointF[] points = CropController.restore(normalizedCrop, original);
            if (points == null) {
                points = aiAlreadyCropped ? detector.defaultCorners(original) : detector.detect(original);
                if (points == null) points = detector.defaultCorners(original);
            }
            cropped = ImageUtils.perspectiveCrop(original, points, maxDimension);
            enhanced = filters.apply(cropped, preset);
            if (enhanced == null) enhanced = cropped.copy(Bitmap.Config.ARGB_8888, false);
            Bitmap result = enhanced;
            enhanced = null;
            return result;
        } finally {
            if (enhanced != null && !enhanced.isRecycled()) enhanced.recycle();
            if (cropped != null && !cropped.isRecycled()) cropped.recycle();
            if (original != null && !original.isRecycled()) original.recycle();
        }
    }

    public void writeJpeg(Bitmap bitmap, File file, int quality) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, Math.max(90, Math.min(100, quality)), stream))
                throw new Exception("encode");
        }
    }
}
''', encoding='utf-8')

# Unit test for semantic filter IDs/names.
testdir = root / 'app/src/test/java/com/quanyi/docscanner'
testdir.mkdir(parents=True, exist_ok=True)
(testdir / 'FilterPresetTest.java').write_text(r'''package com.quanyi.docscanner;

import org.junit.Test;
import static org.junit.Assert.*;

public class FilterPresetTest {
    @Test public void idsAreStableAndNamed() {
        assertEquals(FilterPreset.ORIGINAL, FilterPreset.fromId(0));
        assertEquals(FilterPreset.CLEAR_DOCUMENT, FilterPreset.fromId(1));
        assertEquals(FilterPreset.ENHANCED_SHARP, FilterPreset.fromId(7));
        assertEquals(FilterPreset.CLEAR_DOCUMENT, FilterPreset.fromId(999));
        assertEquals("增強銳化", FilterPreset.ENHANCED_SHARP.label);
    }
}
''', encoding='utf-8')

# Version + JUnit.
build = BUILD.read_text(encoding='utf-8')
build = build.replace('versionCode 158', 'versionCode 200').replace("versionName '1.5.8'", "versionName '2.0.0'")
if "testImplementation 'junit:junit:4.13.2'" not in build:
    build = build.replace("implementation 'com.google.android.gms:play-services-mlkit-document-scanner:16.0.0'",
                          "implementation 'com.google.android.gms:play-services-mlkit-document-scanner:16.0.0'\n    testImplementation 'junit:junit:4.13.2'")
BUILD.write_text(build, encoding='utf-8')

# Migration self-check before Gradle sees the source.
final_main = MAIN.read_text(encoding='utf-8')
assert 'private final ScanSession session = new ScanSession();' in final_main
assert 'DocumentFilterEngine filterEngine' in final_main
assert 'PreviewRenderer previewRenderer' in final_main
assert 'ExportManager exportManager' in final_main
assert 'presetBright()' not in final_main and 'presetSharp()' not in final_main and 'presetMonochrome()' not in final_main
assert 'ImageUtils.enhance(base' not in final_main
assert 'session.saveToBundle(outState)' in final_main
assert 'PIXEL DOC TOOL  v2.0.0' in final_main
assert "versionName '2.0.0'" in BUILD.read_text(encoding='utf-8')
print('v2 core migration complete')
