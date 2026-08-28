from pathlib import Path

p = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
s = p.read_text(encoding='utf-8')

signature = '    private Bitmap buildFinalDocumentBitmap(int index, boolean bright, boolean sharp, boolean monochrome) throws Exception {'
if signature not in s:
    anchor = '    private File createEnhancedShareFile('
    if anchor not in s:
        raise SystemExit('v158 fix failed: share file anchor missing')
    method = '''    private Bitmap buildFinalDocumentBitmap(int index, boolean bright, boolean sharp, boolean monochrome) throws Exception {\n        if (index < 0 || index >= selectedUris.size()) throw new Exception("index");\n        Bitmap original = null;\n        Bitmap cropped = null;\n        Bitmap enhanced = null;\n        try {\n            // Final output is rebuilt independently from the original URI.\n            original = ImageUtils.loadBitmapExact(getContentResolver(), selectedUris.get(index), outputMaxDimension());\n            PointF[] points = restoreCornerState(index, original);\n            if (points == null) {\n                if (aiScannedBatch) {\n                    // ML Kit page is already cropped/perspective-corrected. Preserve full returned page.\n                    points = new PointF[]{\n                            new PointF(0,0), new PointF(original.getWidth(),0),\n                            new PointF(original.getWidth(),original.getHeight()), new PointF(0,original.getHeight())\n                    };\n                } else {\n                    points = ImageUtils.detectDocumentCorners(original);\n                    if (points == null) points = ImageUtils.defaultCorners(original);\n                }\n            }\n            cropped = ImageUtils.perspectiveCrop(original, points, outputMaxDimension());\n            enhanced = ImageUtils.enhance(cropped, bright, sharp, monochrome);\n            if (enhanced == null) enhanced = cropped.copy(Bitmap.Config.ARGB_8888, false);\n            Bitmap result = enhanced;\n            enhanced = null;\n            return result;\n        } finally {\n            if (enhanced != null && !enhanced.isRecycled()) enhanced.recycle();\n            if (cropped != null && !cropped.isRecycled()) cropped.recycle();\n            if (original != null && !original.isRecycled()) original.recycle();\n        }\n    }\n\n'''
    s = s.replace(anchor, method + anchor, 1)
    p.write_text(s, encoding='utf-8')

check = p.read_text(encoding='utf-8')
assert signature in check
assert 'ImageUtils.loadBitmapExact' in check
assert 'ImageUtils.perspectiveCrop(original, points, outputMaxDimension())' in check
print('v1.5.8 HQ final output helper restored')
