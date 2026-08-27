from pathlib import Path

p = Path('app/src/main/java/com/tft/purchase/AiMainActivity.java')
s = p.read_text(encoding='utf-8')

old = '''    @Override protected void onResume() {\n        super.onResume();\n        handler.post(pollJob);\n    }'''
new = '''    @Override protected void onResume() {\n        super.onResume();\n        handler.post(pollJob);\n        if (screen == DETAIL && detailId > 0) handler.post(() -> showDetail(detailId));\n    }'''
if old not in s:
    raise SystemExit('onResume pattern not found')
s = s.replace(old, new, 1)

old = '''        page.addView(info("AI 自動填寫欄位", "下方採購資料由 Gemma 直接讀圖片產生，不需要你逐欄輸入。若內容不對，請按「重新 AI 分析」，不要手動改 AI 欄位。"));'''
new = '''        page.addView(info("AI 自動填寫，可人工修正", "Gemma 會先填入採購資料；如果 AI 判錯，請直接按下方『修改 AI 辨識結果』修正，不需要重新從零輸入。"));\n        Button editAi = action("✎ 修改 AI 辨識結果", BLUE);\n        editAi.setOnClickListener(v -> {\n            Intent edit = new Intent(this, PurchaseEditActivity.class);\n            edit.putExtra("purchase_id", p.id);\n            startActivity(edit);\n        });\n        page.addView(editAi, margins(6,8));'''
if old not in s:
    raise SystemExit('detail info pattern not found')
s = s.replace(old, new, 1)

s = s.replace('GEMMA AI 自動填寫｜背景分析｜交貨提醒', 'GEMMA 4 E4B｜可人工修正｜交貨提醒')
s = s.replace('版本 2.0.7', '版本 2.0.9')
s = s.replace('V2.0.7 使用三階段視覺辨識：表頭 → 品項表格 → 整張稽核。OCR 不再直接填寫欄位。只有通過一致性檢查的資料才會自動寫入。', 'V2.0.9 使用 Gemma 4 E4B，先放大切分供應商區、單號日期區與品項表格，再做上半頁交叉驗證。AI 欄位可人工修正。')
s = s.replace('Pixel 10 Pro XL / Android 17 優先；AI 分析可在背景持續執行。', 'Pixel 10 Pro XL / Android 17 優先；Gemma 4 E4B 完全在手機本機執行，不使用付費 API。')

p.write_text(s, encoding='utf-8')
print('patched', p)
