from pathlib import Path

p = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = p.read_text(encoding='utf-8')

def rep(old, new, label):
    global s
    if old not in s:
        raise SystemExit(f'patch failed: {label}')
    s = s.replace(old, new, 1)

rep(
'''    private static final int REQ_CAMERA = 1003;\n    private static final int MAX_IMAGES = 10;''',
'''    private static final int REQ_CAMERA = 1003;\n    private static final int REQ_AI_SCAN = 1004;\n    private static final int MAX_IMAGES = 10;''',
'AI request code')

rep(
'''    private boolean pendingBatchSave = false;\n    private boolean lowMemoryMode = false;''',
'''    private boolean pendingBatchSave = false;\n    private boolean lowMemoryMode = false;\n    private boolean aiScannedBatch = false;''',
'AI batch flag')

s = s.replace('PIXEL DOC TOOL  v1.3', 'PIXEL DOC TOOL  v1.4 AI')
s = s.replace('拍照或選圖，快速變成清楚、端正的文件', 'AI 自動掃描＋精細裁切，快速變成清楚、端正的文件')
s = s.replace('badges.addView(badge(lowMemoryMode ? "輕量模式" : "本機處理")', 'badges.addView(badge(lowMemoryMode ? "AI＋輕量備援" : "AI＋本機備援")')

rep(
'''        TextView steps = text("[1] 拍照 / 相簿選圖\\n[2] 自動抓角，可拖四角或整條邊\\n[3] 放大鏡精準對齊文件邊緣\\n[4] 文件增強後儲存 / 分享 LINE", compactUi()?13:15, false);''',
'''        TextView steps = text("[1] AI 智慧掃描：自動抓邊、自動校正\\n[2] 可一次掃描 / 匯入最多 10 張\\n[3] 回到 APP 後仍可用 8 點＋放大鏡精修\\n[4] 文件增強後儲存 / 分享 LINE", compactUi()?13:15, false);''',
'home steps')

rep(
'''        LinearLayout inputActions = vertical(0);\n        Button pick = button("▣ 相簿選擇（可多選）", true);\n        Button camera = button("◎ 直接拍照", false);\n        pick.setOnClickListener(v -> pickImages());\n        camera.setOnClickListener(v -> takePhoto());\n        addAdaptivePair(inputActions, pick, camera, 56);\n        root.addView(inputActions);''',
'''        LinearLayout inputActions = vertical(0);\n        Button aiScan = button("✦ AI 智慧掃描（推薦）", true);\n        aiScan.setOnClickListener(v -> startAiScan());\n        inputActions.addView(aiScan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));\n        gap(inputActions, 8);\n        Button pick = button("▣ 相簿批次（相容模式）", false);\n        Button camera = button("◎ 一般拍照（相容模式）", false);\n        pick.setOnClickListener(v -> pickImages());\n        camera.setOnClickListener(v -> takePhoto());\n        addAdaptivePair(inputActions, pick, camera, 56);\n        root.addView(inputActions);''',
'home input actions')

s = s.replace(
'隱私：所有影像只在手機內處理，不會上傳到伺服器。',
'AI 掃描由 Google Play 服務提供；首次使用可能需要下載掃描元件。文件後續裁切與增強由本 APP 處理。')
s = s.replace(
'TIP  文件四周留一點背景，自動抓角會更準。拍照功能使用手機原生相機，舊手機也較穩定。',
'TIP  建議優先使用 AI 智慧掃描。若手機不支援或服務初始化失敗，APP 會保留原本相簿 / 相機相容模式。')

rep(
'''    private void pickImages() {''',
'''    private void startAiScan() {\n        AiDocumentScanner.start(this, REQ_AI_SCAN, () -> runOnUiThread(this::takePhoto));\n    }\n\n    private void pickImages() {''',
'AI start method')

rep(
'''        super.onActivityResult(requestCode, resultCode, data);\n\n        if (requestCode == REQ_CAMERA) {''',
'''        super.onActivityResult(requestCode, resultCode, data);\n\n        if (requestCode == REQ_AI_SCAN) {\n            if (resultCode == RESULT_OK && data != null) {\n                ArrayList<Uri> pages = AiDocumentScanner.readPages(data, MAX_IMAGES);\n                if (!pages.isEmpty()) {\n                    startAiBatch(pages);\n                } else {\n                    Toast.makeText(this, "AI 掃描沒有取得文件，請再試一次或使用相容模式。", Toast.LENGTH_LONG).show();\n                }\n            }\n            return;\n        }\n\n        if (requestCode == REQ_CAMERA) {''',
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
'''        TextView hint = text("拖四角精修；拖邊中間控制柄可整條移動。拖曳時會顯示放大鏡。", compactUi()?11:12, false);''',
'''        String cropHint = aiScannedBatch\n                ? "AI 已先抓邊與透視校正；若需要，可再拖四角 / 四邊中點精修。"\n                : "拖四角精修；拖邊中間控制柄可整條移動。拖曳時會顯示放大鏡。";\n        TextView hint = text(cropHint, compactUi()?11:12, false);''',
'AI crop hint')

p.write_text(s, encoding='utf-8')
print('v1.4.0 MainActivity patch applied')
