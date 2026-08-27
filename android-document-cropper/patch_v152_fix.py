from pathlib import Path
p = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = p.read_text(encoding='utf-8')
old = '''                runOnUiThread(() -> {\n                    d.dismiss();\n                    sourceBitmap = b;\n                    lastCorners = corners != null ? corners : ImageUtils.defaultCorners(b);\n                    showCrop(lastCorners);\n                });'''
new = '''                final PointF[] resolvedCorners = corners;\n                runOnUiThread(() -> {\n                    d.dismiss();\n                    sourceBitmap = b;\n                    lastCorners = resolvedCorners != null ? resolvedCorners : ImageUtils.defaultCorners(b);\n                    showCrop(lastCorners);\n                });'''
if old not in s:
    raise SystemExit('v152 fix failed: loadCurrentDocument lambda block')
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('v1.5.2 lambda capture fix applied')
