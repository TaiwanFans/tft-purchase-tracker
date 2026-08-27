from pathlib import Path

p = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'patch failed: {label}')
    s = s.replace(old, new, 1)

# ----- v1.4 AI scanner integration -----
rep(
'''    private static final int REQ_CAMERA = 1003;\n    private static final int MAX_IMAGES = 10;''',
'''    private static final int REQ_CAMERA = 1003;\n    private static final int REQ_AI_SCAN = 1004;\n    private static final int MAX_IMAGES = 10;''',
'AI request code')

rep(
'''    private static final int BG = Color.rgb(16,24,32);\n    private static final int PANEL = Color.rgb(28,39,49);\n    private static final int TEXT = Color.rgb(244,248,248);\n    private static final int MUTED = Color.rgb(176,196,201);\n    private static final int GREEN = Color.rgb(76,255,169);\n    private static final int CYAN = Color.rgb(108,224,240);''',
'''    private int BG = Color.rgb(16,24,32);\n    private int PANEL = Color.rgb(28,39,49);\n    private int TEXT = Color.rgb(244,248,248);\n    private int MUTED = Color.rgb(176,196,201);\n    private int GREEN = Color.rgb(76,255,169);\n    private int CYAN = Color.rgb(108,224,240);\n    private int themeIndex = 0;\n    private static final String UI_PREFS = "docscanner_ui";\n    private static final String UI_THEME = "theme";''',
'theme colors')

rep(
'''    private boolean pendingBatchSave = false;\n    private boolean lowMemoryMode = false;''',
'''    private boolean pendingBatchSave = false;\n    private boolean lowMemoryMode = false;\n    private boolean aiScannedBatch = false;''',
'AI batch flag')

rep(
'''    private ImageView resultImage;''',
'''    private ZoomableImageView resultImage;''',
'zoomable result type')

rep(
'''        super.onCreate(b);\n        getWindow().setStatusBarColor(BG);''',
'''        super.onCreate(b);\n        loadTheme();\n        getWindow().setStatusBarColor(BG);''',
'load theme on create')

rep(
'''    private boolean detectLowMemoryDevice() {''',
'''    private void loadTheme() {\n        themeIndex = getSharedPreferences(UI_PREFS, MODE_PRIVATE).getInt(UI_THEME, 0);\n        applyThemeColors(themeIndex);\n    }\n\n    private void applyThemeColors(int idx) {\n        themeIndex = Math.max(0, Math.min(3, idx));\n        switch (themeIndex) {\n            case 1: // Professional blue\n                BG = Color.rgb(15,23,42);\n                PANEL = Color.rgb(30,41,59);\n                TEXT = Color.rgb(248,250,252);\n                MUTED = Color.rgb(203,213,225);\n                GREEN = Color.rgb(56,189,248);\n                CYAN = Color.rgb(125,211,252);\n                break;\n            case 2: // Warm orange\n                BG = Color.rgb(28,25,23);\n                PANEL = Color.rgb(41,37,36);\n                TEXT = Color.rgb(250,250,249);\n                MUTED = Color.rgb(214,211,209);\n                GREEN = Color.rgb(251,146,60);\n                CYAN = Color.rgb(253,186,116);\n                break;\n            case 3: // High contrast\n                BG = Color.rgb(8,8,10);\n                PANEL = Color.rgb(26,26,29);\n                TEXT = Color.WHITE;\n                MUTED = Color.rgb(218,218,222);\n                GREEN = Color.WHITE;\n                CYAN = Color.rgb(190,190,196);\n                break;\n            default: // Mint green\n                BG = Color.rgb(16,24,32);\n                PANEL = Color.rgb(28,39,49);\n                TEXT = Color.rgb(244,248,248);\n                MUTED = Color.rgb(176,196,201);\n                GREEN = Color.rgb(76,255,169);\n                CYAN = Color.rgb(108,224,240);\n                break;\n        }\n    }\n\n    private void selectTheme(int idx) {\n        applyThemeColors(idx);\n        getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit().putInt(UI_THEME, themeIndex).apply();\n        getWindow().setStatusBarColor(BG);\n        getWindow().setNavigationBarColor(BG);\n        showHome();\n    }\n\n    private String themeLabel(int idx, String name) {\n        return (themeIndex == idx ? "✓ " : "") + name;\n    }\n\n    private boolean detectLowMemoryDevice() {''',
'theme methods')

rep(
'''    private TextView badge(String s) {''',
'''    private TextView guideBox(String s) {\n        TextView v = text(s, compactUi()?13:14, false);\n        v.setTextColor(TEXT);\n        v.setLineSpacing(dp(3), 1f);\n        v.setPadding(dp(12), dp(10), dp(12), dp(10));\n        GradientDrawable g = new GradientDrawable();\n        g.setColor(PANEL);\n        g.setStroke(dp(1), CYAN);\n        g.setCornerRadius(dp(4));\n        v.setBackground(g);\n        return v;\n    }\n\n    private TextView badge(String s) {''',
guide box')

s = s.replace('TextView title = text("文件裁切器", compactUi()?23:27, true);', 'TextView title = text("文件掃描與裁切", compactUi()?23:27, true);')
s = s.replace('PIXEL DOC TOOL  v1.3', 'PIXEL DOC TOOL  v1.5')
s = s.replace('拍照或選圖，快速變成清楚、端正的文件', '第一次使用也很簡單：掃描 → 校正 → 預覽輸出')
s = s.replace('badges.addView(badge(lowMemoryMode ? "輕量模式" : "本機處理")', 'badges.addView(badge(lowMemoryMode ? "AI＋輕量備援" : "AI＋本機備援")')

rep(
'''        TextView steps = text("[1] 拍照 / 相簿選圖\\n[2] 自動抓角，可拖四角或整條邊\\n[3] 放大鏡精準對齊文件邊緣\\n[4] 文件增強後儲存 / 分享 LINE", compactUi()?13:15, false);\n        steps.setLineSpacing(dp(3), 1f);\n        root.addView(steps);''',
'''        TextView steps = guideBox("STEP 1 / 3  選擇掃描方式\\n\\n推薦：AI 智慧掃描會自動抓邊與校正。\\n完成後只要再確認裁切範圍，就能預覽、儲存或分享。");\n        root.addView(steps);''',
'guided home steps')

rep(
'''        LinearLayout inputActions = vertical(0);\n        Button pick = button("▣ 相簿選擇（可多選）", true);\n        Button camera = button("◎ 直接拍照", false);\n        pick.setOnClickListener(v -> pickImages());\n        camera.setOnClickListener(v -> takePhoto());\n        addAdaptivePair(inputActions, pick, camera, 56);\n        root.addView(inputActions);''',
'''        LinearLayout inputActions = vertical(0);\n        TextView recommended = text("推薦方式", 13, true);\n        recommended.setTextColor(GREEN);\n        inputActions.addView(recommended);\n        Button aiScan = button("✦ AI 智慧掃描", true);\n        aiScan.setOnClickListener(v -> startAiScan());\n        inputActions.addView(aiScan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(62)));\n        gap(inputActions, 10);\n        TextView other = text("其他方式｜AI 不支援時可用", 12, true);\n        other.setTextColor(MUTED);\n        inputActions.addView(other);\n        Button pick = button("▣ 從相簿選擇（最多 10 張）", false);\n        Button camera = button("◎ 使用手機相機拍照", false);\n        pick.setOnClickListener(v -> pickImages());\n        camera.setOnClickListener(v -> takePhoto());\n        addAdaptivePair(inputActions, pick, camera, 56);\n        root.addView(inputActions);''',
'home input actions')

s = s.replace(
'隱私：所有影像只在手機內處理，不會上傳到伺服器。',
'AI 掃描由 Google Play 服務提供；首次使用可能需要下載掃描元件。文件後續裁切與增強由本 APP 處理。')
s = s.replace(
'TIP  文件四周留一點背景，自動抓角會更準。拍照功能使用手機原生相機，舊手機也較穩定。',
'TIP  若 AI 智慧掃描無法啟動，可直接改用相簿或手機相機，原本功能都會保留。')

rep(
'''        root.addView(note);\n        setSafeContentView(scroll);''',
'''        root.addView(note);\n        gap(root, 16);\n        TextView themeTitle = text("介面風格", 15, true);\n        themeTitle.setTextColor(GREEN);\n        root.addView(themeTitle);\n        TextView themeHint = text("選擇喜歡的配色，APP 會自動記住。", 12, false);\n        themeHint.setTextColor(MUTED);\n        root.addView(themeHint);\n        LinearLayout themes = vertical(0);\n        Button t0 = button(themeLabel(0, "薄荷綠"), themeIndex == 0);\n        Button t1 = button(themeLabel(1, "專業藍"), themeIndex == 1);\n        Button t2 = button(themeLabel(2, "暖陽橘"), themeIndex == 2);\n        Button t3 = button(themeLabel(3, "高對比黑"), themeIndex == 3);\n        t0.setOnClickListener(v -> selectTheme(0));\n        t1.setOnClickListener(v -> selectTheme(1));\n        t2.setOnClickListener(v -> selectTheme(2));\n        t3.setOnClickListener(v -> selectTheme(3));\n        addAdaptivePair(themes, t0, t1, 48);\n        gap(themes, 7);\n        addAdaptivePair(themes, t2, t3, 48);\n        root.addView(themes);\n        setSafeContentView(scroll);''',
'theme selector UI')

rep(
'''    private void pickImages() {''',
'''    private void startAiScan() {\n        AiDocumentScanner.start(this, REQ_AI_SCAN, () -> runOnUiThread(this::takePhoto));\n    }\n\n    private void pickImages() {''',
'AI start method')

rep(
'''        super.onActivityResult(requestCode, resultCode, data);\n\n        if (requestCode == REQ_CAMERA) {''',
'''        super.onActivityResult(requestCode, resultCode, data);\n\n        if (requestCode == REQ_AI_SCAN) {\n            if (resultCode == RESULT_OK && data != null) {\n                ArrayList<Uri> pages = AiDocumentScanner.readPages(data, MAX_IMAGES);\n                if (!pages.isEmpty()) {\n                    startAiBatch(pages);\n                } else {\n                    Toast.makeText(this, "AI 掃描沒有取得文件，請再試一次或使用其他方式。", Toast.LENGTH_LONG).show();\n                }\n            }\n            return;\n        }\n\n        if (requestCode == REQ_CAMERA) {''',
'AI activity result')

rep(
'''    private void startBatch(ArrayList<Uri> picked) {\n        clearBatchFiles();\n        selectedUris.clear();\n        selectedUris.addAll(picked);\n        croppedFiles.clear();\n        currentIndex = 0;\n        previewIndex = 0;\n        loadCurrentDocument();\n    }''',
'''    private void startBatch(ArrayList<Uri> picked) {\n        aiScannedBatch = false;\n        beginBatch(picked);\n    }\n\n    private void startAiBatch(ArrayList<Uri> picked) {\n        aiScannedBatch = true;\n        beginBatch(picked);\n    }\n\n    private void beginBatch(ArrayList<Uri> picked) {\n        clearBatchFiles();\n        selectedUris.clear();\n        selectedUris.addAll(picked);\n        croppedFiles.clear();\n        currentIndex = 0;\n        previewIndex = 0;\n        loadCurrentDocument();\n    }''',
'AI batch start')

rep(
'''                Bitmap b = ImageUtils.loadBitmap(getContentResolver(), uri, sourceMaxDimension());\n                PointF[] corners = ImageUtils.detectDocumentCorners(b);''',
'''                Bitmap b = ImageUtils.loadBitmap(getContentResolver(), uri, sourceMaxDimension());\n                PointF[] corners;\n                if (aiScannedBatch) {\n                    float ix = Math.max(2f, b.getWidth() * 0.003f);\n                    float iy = Math.max(2f, b.getHeight() * 0.003f);\n                    corners = new PointF[]{\n                            new PointF(ix, iy),\n                            new PointF(b.getWidth()-ix, iy),\n                            new PointF(b.getWidth()-ix, b.getHeight()-iy),\n                            new PointF(ix, b.getHeight()-iy)\n                    };\n                } else {\n                    corners = ImageUtils.detectDocumentCorners(b);\n                }''',
'AI precropped corners')

rep(
'''        TextView title = text("第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張｜裁切範圍", compactUi()?16:19, true);''',
'''        TextView title = text("STEP 2 / 3｜第 " + (currentIndex+1) + " / " + selectedUris.size() + " 張｜校正裁切", compactUi()?16:19, true);''',
'crop step title')

rep(
'''        TextView hint = text("拖四角精修；拖邊中間控制柄可整條移動。拖曳時會顯示放大鏡。", compactUi()?11:12, false);''',
'''        String cropHint = aiScannedBatch\n                ? "AI 已先抓邊與透視校正。若邊界正確可直接按確認；需要時再拖四角 / 四邊中點精修。"\n                : "確認綠色框是否貼齊紙張。可拖四角或四邊中點；拖曳時會顯示放大鏡。";\n        TextView hint = text(cropHint, compactUi()?11:12, false);''',
'crop guided hint')

rep(
'''        TextView title = text(croppedFiles.size() == 1 ? "文件處理完成" : "已完成 " + croppedFiles.size() + " 張文件", compactUi()?18:20, true);''',
'''        TextView title = text(croppedFiles.size() == 1 ? "STEP 3 / 3｜預覽與輸出" : "STEP 3 / 3｜預覽 " + croppedFiles.size() + " 張文件", compactUi()?18:20, true);''',
'result step title')

rep(
'''        TextView hint = text("增強設定會套用到全部照片。預覽採輕量處理，儲存與分享仍使用完整裁切畫質。", 12, false);''',
'''        TextView hint = text("先放大確認文字與邊界，再決定是否儲存 / 分享。增強設定會套用到全部照片。", 12, false);''',
'result guided hint')

rep(
'''        resultImage = new ImageView(this);\n        resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);\n        resultImage.setBackgroundColor(PANEL);\n        page.addView(resultImage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight));''',
'''        resultImage = new ZoomableImageView(this);\n        resultImage.setBackgroundColor(PANEL);\n        page.addView(resultImage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, imageHeight));\n        TextView zoomTip = text("🔍 雙指張合：放大 / 縮小　｜　放大後單指拖曳：查看位置　｜　雙擊：還原", 12, true);\n        zoomTip.setTextColor(CYAN);\n        zoomTip.setGravity(Gravity.CENTER);\n        page.addView(zoomTip);\n        Button resetZoom = button("↺ 還原完整畫面", false);\n        resetZoom.setOnClickListener(v -> { if (resultImage != null) resultImage.resetZoom(); });\n        page.addView(resetZoom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));''',
'zoom result preview')

p.write_text(s, encoding='utf-8')

# ----- Generate lightweight pinch-to-zoom preview view -----
zoom = Path('app/src/main/java/com/quanyi/docscanner/ZoomableImageView.java')
zoom.write_text(r'''package com.quanyi.docscanner;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public class ZoomableImageView extends ImageView {
    private final Matrix imageMatrixInternal = new Matrix();
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float relativeScale = 1f;
    private final float minRelativeScale = 1f;
    private final float maxRelativeScale = 5f;
    private float lastX, lastY;
    private boolean dragging = false;

    public ZoomableImageView(Context context) { super(context); init(context); }
    public ZoomableImageView(Context context, AttributeSet attrs) { super(context, attrs); init(context); }
    public ZoomableImageView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(context); }

    private void init(Context context) {
        super.setScaleType(ScaleType.MATRIX);
        setClickable(true);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) { return true; }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                float target = relativeScale * factor;
                if (target < minRelativeScale) factor = minRelativeScale / relativeScale;
                if (target > maxRelativeScale) factor = maxRelativeScale / relativeScale;
                relativeScale *= factor;
                imageMatrixInternal.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                fixTranslation();
                setImageMatrix(imageMatrixInternal);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(relativeScale > 1.01f);
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                resetZoom();
                return true;
            }
        });
        setOnTouchListener((v, event) -> handleTouch(event));
    }

    private boolean handleTouch(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX(); lastY = event.getY(); dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1 && relativeScale > 1.01f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    if (Math.abs(dx) > 0.2f || Math.abs(dy) > 0.2f) dragging = true;
                    imageMatrixInternal.postTranslate(dx, dy);
                    fixTranslation();
                    setImageMatrix(imageMatrixInternal);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastX = event.getX(); lastY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                dragging = false;
                break;
        }
        return true;
    }

    @Override public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::resetZoom);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::resetZoom);
    }

    public void resetZoom() {
        Drawable d = getDrawable();
        if (d == null || getWidth() <= 0 || getHeight() <= 0) return;
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;
        imageMatrixInternal.reset();
        float scale = Math.min(getWidth() / (float)dw, getHeight() / (float)dh);
        float dx = (getWidth() - dw * scale) * 0.5f;
        float dy = (getHeight() - dh * scale) * 0.5f;
        imageMatrixInternal.postScale(scale, scale);
        imageMatrixInternal.postTranslate(dx, dy);
        relativeScale = 1f;
        setImageMatrix(imageMatrixInternal);
        invalidate();
    }

    private void fixTranslation() {
        Drawable d = getDrawable();
        if (d == null) return;
        RectF rect = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
        imageMatrixInternal.mapRect(rect);
        float dx = 0f, dy = 0f;
        if (rect.width() <= getWidth()) dx = getWidth() * 0.5f - rect.centerX();
        else if (rect.left > 0) dx = -rect.left;
        else if (rect.right < getWidth()) dx = getWidth() - rect.right;
        if (rect.height() <= getHeight()) dy = getHeight() * 0.5f - rect.centerY();
        else if (rect.top > 0) dy = -rect.top;
        else if (rect.bottom < getHeight()) dy = getHeight() - rect.bottom;
        imageMatrixInternal.postTranslate(dx, dy);
    }
}
''', encoding='utf-8')

# ----- v1.5 version -----
gradle = Path('app/build.gradle')
g = gradle.read_text(encoding='utf-8')
g = g.replace("versionCode 9", "versionCode 10", 1)
g = g.replace("versionName '1.4.0'", "versionName '1.5.0'", 1)
gradle.write_text(g, encoding='utf-8')

print('v1.5.0 zoom/themes/guided UI patch applied')
