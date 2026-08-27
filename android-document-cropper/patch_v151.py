from pathlib import Path

main = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = main.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'v151 patch failed: {label}')
    s = s.replace(old, new, 1)

# ---------- responsive UI / rounded buttons ----------
rep(
'''    private boolean compactUi() {\n        return getResources().getConfiguration().screenWidthDp < 380 || getResources().getConfiguration().fontScale > 1.15f;\n    }''',
'''    private boolean compactUi() {\n        return getResources().getConfiguration().screenWidthDp < 520 || getResources().getConfiguration().fontScale > 1.10f;\n    }''',
'compact ui threshold')

rep(
'''        b.setMinHeight(dp(50));''',
'''        b.setMinHeight(dp(54));''',
'button minimum height')

rep(
'''        g.setCornerRadius(dp(2));''',
'''        g.setCornerRadius(dp(12));''',
'button radius')

rep(
'''        b.setPadding(dp(8), dp(4), dp(8), dp(4));''',
'''        b.setPadding(dp(12), dp(9), dp(12), dp(9));''',
'button padding')

rep(
'''    private void addAdaptivePair(LinearLayout parent, Button left, Button right, int heightDp) {\n        if (compactUi()) {\n            parent.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));\n            gap(parent, 8);\n            parent.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));\n        } else {\n            LinearLayout row = new LinearLayout(this);\n            row.setOrientation(LinearLayout.HORIZONTAL);\n            row.addView(left, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));\n            View spacer = new View(this);\n            row.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));\n            row.addView(right, new LinearLayout.LayoutParams(0, dp(heightDp), 1f));\n            parent.addView(row);\n        }\n    }''',
'''    private void addAdaptivePair(LinearLayout parent, Button left, Button right, int heightDp) {\n        left.setMinHeight(dp(heightDp));\n        right.setMinHeight(dp(heightDp));\n        if (compactUi()) {\n            parent.addView(left, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n            gap(parent, 8);\n            parent.addView(right, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        } else {\n            LinearLayout row = new LinearLayout(this);\n            row.setOrientation(LinearLayout.HORIZONTAL);\n            row.setGravity(Gravity.CENTER_VERTICAL);\n            row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n            View spacer = new View(this);\n            row.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));\n            row.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));\n            parent.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));\n        }\n    }''',
'adaptive wrap buttons')

# Round guide/badge cards too.
s = s.replace('g.setCornerRadius(dp(4));', 'g.setCornerRadius(dp(12));')
s = s.replace('g.setColor(PANEL); g.setStroke(dp(2), GREEN);\n        v.setBackground(g);',
              'g.setColor(PANEL); g.setStroke(dp(2), GREEN); g.setCornerRadius(dp(10));\n        v.setBackground(g);')

s = s.replace('PIXEL DOC TOOL  v1.5', 'PIXEL DOC TOOL  v1.5.1')
s = s.replace('其他方式｜AI 不支援時可用', '其他方式｜同樣支援裁切與文件濾鏡')

# ---------- old/midrange phone performance ----------
rep(
'''    private boolean detectLowMemoryDevice() {\n        try {\n            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);\n            if (am == null) return false;\n            return am.isLowRamDevice() || am.getMemoryClass() <= 256;\n        } catch (Throwable ignored) {\n            return Build.VERSION.SDK_INT <= 28;\n        }\n    }\n\n    private int sourceMaxDimension() { return lowMemoryMode ? 3200 : 4096; }\n    private int previewMaxDimension() { return lowMemoryMode ? 1050 : 1500; }''',
'''    private boolean detectLowMemoryDevice() {\n        try {\n            ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);\n            if (am == null) return Build.VERSION.SDK_INT <= 28;\n            return am.isLowRamDevice() || am.getMemoryClass() <= 384 || Build.VERSION.SDK_INT <= 27;\n        } catch (Throwable ignored) {\n            return Build.VERSION.SDK_INT <= 28;\n        }\n    }\n\n    private int sourceMaxDimension() {\n        if (lowMemoryMode) return 2400;\n        return Build.VERSION.SDK_INT <= 30 ? 3100 : 3800;\n    }\n    private int previewMaxDimension() {\n        if (lowMemoryMode) return 760;\n        return Build.VERSION.SDK_INT <= 30 ? 950 : 1250;\n    }''',
'performance dimensions')

# single preview worker prevents several filter taps from running OpenCV at once
rep(
'''import java.util.Locale;''',
'''import java.util.Locale;\nimport java.util.concurrent.ExecutorService;\nimport java.util.concurrent.Executors;''',
'concurrency imports')

rep(
'''    private int previewToken = 0;''',
'''    private int previewToken = 0;\n    private int filterPreset = 1; // 0 original, 1 clear, 2 soft white, 3 B&W\n    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor();''',
'filter and preview executor fields')

rep(
'''            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;''',
'''            opts.inPreferredConfig = lowMemoryMode ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;''',
'preview low memory bitmap config')

# ---------- common filters for AI / gallery / manual camera ----------
rep(
'''        TextView mode = text("文件增強", 14, true);\n        mode.setTextColor(GREEN);\n        page.addView(mode);\n\n        LinearLayout switches = new LinearLayout(this);\n        switches.setOrientation(compactUi()?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);\n        brightSwitch = new Switch(this);\n        brightSwitch.setText("亮度＋｜紙張白化、陰影校正");\n        brightSwitch.setTextColor(TEXT); brightSwitch.setTextSize(compactUi()?13:14);\n        brightSwitch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); brightSwitch.setChecked(true);\n        sharpSwitch = new Switch(this);\n        sharpSwitch.setText("清晰＋｜文字與表格線強化");\n        sharpSwitch.setTextColor(TEXT); sharpSwitch.setTextSize(compactUi()?13:14);\n        sharpSwitch.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); sharpSwitch.setChecked(true);\n        if (compactUi()) {\n            switches.addView(brightSwitch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));\n            switches.addView(sharpSwitch, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));\n        } else {\n            switches.addView(brightSwitch, new LinearLayout.LayoutParams(0,dp(54),1f));\n            switches.addView(sharpSwitch, new LinearLayout.LayoutParams(0,dp(54),1f));\n        }\n        page.addView(switches);\n\n        TextView note = text("建議：一般紙本文件同時開啟兩項，可得到最接近掃描器的效果。", 12, false);\n        note.setTextColor(MUTED);\n        page.addView(note);''',
'''        TextView mode = text("文件濾鏡｜AI、相簿、一般拍照都可使用", 14, true);\n        mode.setTextColor(GREEN);\n        page.addView(mode);\n        TextView filterHint = text("選一種即可，會套用到目前這批全部文件。", 12, false);\n        filterHint.setTextColor(MUTED);\n        page.addView(filterHint);\n        TextView filterStatus = text("目前：" + filterName(filterPreset), 13, true);\n        filterStatus.setTextColor(CYAN);\n        page.addView(filterStatus);\n\n        LinearLayout filterButtons = vertical(0);\n        Button f0 = button("原色", filterPreset == 0);\n        Button f1 = button("清晰文件", filterPreset == 1);\n        Button f2 = button("柔白文件", filterPreset == 2);\n        Button f3 = button("黑白文件", filterPreset == 3);\n        f0.setOnClickListener(v -> selectFilterPreset(0, filterStatus));\n        f1.setOnClickListener(v -> selectFilterPreset(1, filterStatus));\n        f2.setOnClickListener(v -> selectFilterPreset(2, filterStatus));\n        f3.setOnClickListener(v -> selectFilterPreset(3, filterStatus));\n        addAdaptivePair(filterButtons, f0, f1, 48);\n        gap(filterButtons, 7);\n        addAdaptivePair(filterButtons, f2, f3, 48);\n        page.addView(filterButtons);\n\n        TextView note = text("推薦：一般文件用「清晰文件」；陰影偏重可用「柔白文件」；影印資料可試「黑白文件」。", 12, false);\n        note.setTextColor(MUTED);\n        page.addView(note);''',
'filter preset UI')

rep(
'''        brightSwitch.setOnCheckedChangeListener((b,c) -> refreshPreview());\n        sharpSwitch.setOnCheckedChangeListener((b,c) -> refreshPreview());\n        share.setOnClickListener(v -> shareAll());''',
'''        share.setOnClickListener(v -> shareAll());''',
'remove switch listeners')

rep(
'''    private Bitmap decodePreview(File file) {''',
'''    private String filterName(int preset) {\n        switch (preset) {\n            case 0: return "原色";\n            case 2: return "柔白文件";\n            case 3: return "黑白文件";\n            default: return "清晰文件";\n        }\n    }\n\n    private void selectFilterPreset(int preset, TextView status) {\n        filterPreset = Math.max(0, Math.min(3, preset));\n        if (status != null) status.setText("目前：" + filterName(filterPreset));\n        refreshPreview();\n    }\n\n    private boolean presetBright() { return filterPreset == 1 || filterPreset == 2 || filterPreset == 3; }\n    private boolean presetSharp() { return filterPreset == 1 || filterPreset == 3; }\n    private boolean presetMonochrome() { return filterPreset == 3; }\n\n    private Bitmap decodePreview(File file) {''',
'filter helpers')

# Replace preview with serial executor + stale-token checks
start = s.index('    private void refreshPreview() {')
end = s.index('    private File createEnhancedShareFile(', start)
s = s[:start] + '''    private void refreshPreview() {\n        if (resultImage == null || croppedFiles.isEmpty()) return;\n        if (previewCounter != null) previewCounter.setText((previewIndex+1) + " / " + croppedFiles.size());\n        final int token = ++previewToken;\n        final int idx = previewIndex;\n        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();\n        previewExecutor.execute(() -> {\n            if (token != previewToken) return;\n            Bitmap raw = decodePreview(croppedFiles.get(idx));\n            if (raw == null) return;\n            if (token != previewToken) { raw.recycle(); return; }\n            Bitmap enhanced = null;\n            try { enhanced = ImageUtils.enhance(raw, bright, sharp, monochrome); }\n            catch (Throwable ignored) {}\n            if (enhanced == null) enhanced = raw.copy(Bitmap.Config.ARGB_8888, false);\n            if (token != previewToken) {\n                if (enhanced != raw && !enhanced.isRecycled()) enhanced.recycle();\n                if (!raw.isRecycled()) raw.recycle();\n                return;\n            }\n            Bitmap finalEnhanced = enhanced;\n            runOnUiThread(() -> {\n                if (token != previewToken || resultImage == null) {\n                    if (!finalEnhanced.isRecycled()) finalEnhanced.recycle();\n                    return;\n                }\n                if (displayBitmap != null && !displayBitmap.isRecycled()) displayBitmap.recycle();\n                displayBitmap = finalEnhanced;\n                resultImage.setImageBitmap(displayBitmap);\n            });\n            if (!raw.isRecycled()) raw.recycle();\n        });\n    }\n\n''' + s[end:]

# sharing uses common preset
rep(
'''    private File createEnhancedShareFile(File rawFile, int index, boolean bright, boolean sharp) throws Exception {''',
'''    private File createEnhancedShareFile(File rawFile, int index, boolean bright, boolean sharp, boolean monochrome) throws Exception {''',
'share file signature')
rep(
'''            out = ImageUtils.enhance(raw, bright, sharp);''',
'''            out = ImageUtils.enhance(raw, bright, sharp, monochrome);''',
'share preset enhance')
rep(
'''        final boolean bright = brightSwitch != null && brightSwitch.isChecked();\n        final boolean sharp = sharpSwitch != null && sharpSwitch.isChecked();''',
'''        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();''',
'share preset flags')
rep(
'''                    File f = createEnhancedShareFile(croppedFiles.get(i), i, bright, sharp);''',
'''                    File f = createEnhancedShareFile(croppedFiles.get(i), i, bright, sharp, monochrome);''',
'share preset call')

# save uses common preset (second occurrence of old switch flags remains)
rep(
'''        final boolean bright = brightSwitch != null && brightSwitch.isChecked();\n        final boolean sharp = sharpSwitch != null && sharpSwitch.isChecked();''',
'''        final boolean bright = presetBright();\n        final boolean sharp = presetSharp();\n        final boolean monochrome = presetMonochrome();''',
'save preset flags')
rep(
'''                    out = ImageUtils.enhance(raw, bright, sharp);''',
'''                    out = ImageUtils.enhance(raw, bright, sharp, monochrome);''',
'save preset enhance')

# Slightly faster JPEG encode on low-memory devices.
s = s.replace('lowMemoryMode ? 95 : 97', 'lowMemoryMode ? 93 : 96')

rep(
'''    @Override protected void onDestroy() {\n        super.onDestroy();\n        releaseWorkingBitmaps();\n    }''',
'''    @Override protected void onDestroy() {\n        super.onDestroy();\n        previewToken++;\n        try { previewExecutor.shutdownNow(); } catch (Throwable ignored) {}\n        releaseWorkingBitmaps();\n    }''',
'executor shutdown')

main.write_text(s, encoding='utf-8')

# ---------- ImageUtils: monochrome preset + no-op fast path ----------
image = Path('app/src/main/java/com/quanyi/docscanner/ImageUtils.java')
u = image.read_text(encoding='utf-8')
old = '''    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper) {\n        if (input == null || input.isRecycled()) return input;\n        if (ensureOpenCv()) {'''
new = '''    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper) {\n        if (input == null || input.isRecycled()) return input;\n        if (!brighter && !sharper) return input.copy(Bitmap.Config.ARGB_8888, false);\n        if (ensureOpenCv()) {'''
if old not in u:
    raise SystemExit('v151 patch failed: ImageUtils fast path')
u = u.replace(old, new, 1)

anchor = '''    private static Bitmap enhanceOpenCv(Bitmap input, boolean brighter, boolean sharper) {'''
overload = '''    static Bitmap enhance(Bitmap input, boolean brighter, boolean sharper, boolean monochrome) {\n        Bitmap out = enhance(input, brighter, sharper);\n        if (!monochrome || out == null || out.isRecycled()) return out;\n        try {\n            int w = out.getWidth(), h = out.getHeight();\n            int[] px = new int[w*h];\n            out.getPixels(px, 0, w, 0, 0, w, h);\n            for (int i=0;i<px.length;i++) {\n                int c = px[i];\n                int y = (Color.red(c)*30 + Color.green(c)*59 + Color.blue(c)*11) / 100;\n                y = clamp(Math.round((y - 128) * 1.20f + 128));\n                px[i] = Color.argb(Color.alpha(c), y, y, y);\n            }\n            out.setPixels(px, 0, w, 0, 0, w, h);\n            return out;\n        } catch (Throwable ignored) {\n            return out;\n        }\n    }\n\n'''
if anchor not in u:
    raise SystemExit('v151 patch failed: ImageUtils overload anchor')
u = u.replace(anchor, overload + anchor, 1)
image.write_text(u, encoding='utf-8')

# ---------- version ----------
gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
g = g.replace('versionCode 10', 'versionCode 11', 1)
g = g.replace("versionName '1.5.0'", "versionName '1.5.1'", 1)
gradle.write_text(g, encoding='utf-8')

print('v1.5.1 responsive UI / common filters / performance patch applied')
