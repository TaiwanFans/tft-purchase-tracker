from pathlib import Path
import re

crop = Path('app/src/main/java/com/quanyi/docscanner/DocumentCropView.java')
s = crop.read_text(encoding='utf-8')

old_matrix = '''    private void updateMatrix() {\n        if (getWidth()==0 || getHeight()==0 || bitmap==null) return;\n        float sx=getWidth()/(float)bitmap.getWidth();\n        float sy=getHeight()/(float)bitmap.getHeight();\n        float s=Math.min(sx,sy);\n        float dx=(getWidth()-bitmap.getWidth()*s)/2f;\n        float dy=(getHeight()-bitmap.getHeight()*s)/2f;\n        imageMatrix.reset();\n        imageMatrix.postScale(s,s);\n        imageMatrix.postTranslate(dx,dy);\n        imageMatrix.invert(inverse);\n    }\n\n    private void updatePreviewMatrix(Bitmap drawBitmap) {\n        if (getWidth()==0 || getHeight()==0 || drawBitmap==null) return;\n        float sx=getWidth()/(float)drawBitmap.getWidth();\n        float sy=getHeight()/(float)drawBitmap.getHeight();\n        float s=Math.min(sx,sy);\n        float dx=(getWidth()-drawBitmap.getWidth()*s)/2f;\n        float dy=(getHeight()-drawBitmap.getHeight()*s)/2f;\n        previewMatrix.reset();\n        previewMatrix.postScale(s,s);\n        previewMatrix.postTranslate(dx,dy);\n    }\n'''

new_matrix = '''    private float cropSideInsetPx() {\n        // Keep document handles away from Android back-gesture / physical screen edges.\n        // Use a responsive inset so small phones do not lose too much document area.\n        return clamp(getWidth() * 0.060f, dp(18), dp(28));\n    }\n\n    private float cropVerticalInsetPx() {\n        return clamp(getHeight() * 0.018f, dp(8), dp(16));\n    }\n\n    private void updateMatrix() {\n        if (getWidth()==0 || getHeight()==0 || bitmap==null) return;\n        float side=cropSideInsetPx();\n        float vertical=cropVerticalInsetPx();\n        float usableW=Math.max(1f,getWidth()-side*2f);\n        float usableH=Math.max(1f,getHeight()-vertical*2f);\n        float sx=usableW/(float)bitmap.getWidth();\n        float sy=usableH/(float)bitmap.getHeight();\n        float s=Math.min(sx,sy);\n        float dx=side+(usableW-bitmap.getWidth()*s)/2f;\n        float dy=vertical+(usableH-bitmap.getHeight()*s)/2f;\n        imageMatrix.reset();\n        imageMatrix.postScale(s,s);\n        imageMatrix.postTranslate(dx,dy);\n        imageMatrix.invert(inverse);\n    }\n\n    private void updatePreviewMatrix(Bitmap drawBitmap) {\n        if (getWidth()==0 || getHeight()==0 || drawBitmap==null) return;\n        float side=cropSideInsetPx();\n        float vertical=cropVerticalInsetPx();\n        float usableW=Math.max(1f,getWidth()-side*2f);\n        float usableH=Math.max(1f,getHeight()-vertical*2f);\n        float sx=usableW/(float)drawBitmap.getWidth();\n        float sy=usableH/(float)drawBitmap.getHeight();\n        float s=Math.min(sx,sy);\n        float dx=side+(usableW-drawBitmap.getWidth()*s)/2f;\n        float dy=vertical+(usableH-drawBitmap.getHeight()*s)/2f;\n        previewMatrix.reset();\n        previewMatrix.postScale(s,s);\n        previewMatrix.postTranslate(dx,dy);\n    }\n'''

if old_matrix not in s:
    raise SystemExit('v154 patch failed: crop matrix block not found')
s = s.replace(old_matrix, new_matrix, 1)

# Larger invisible touch targets: easier to grab near paper edges without enlarging the visual handles.
s = s.replace('        float best=dp(82);', '        float best=dp(94);', 1)
s = s.replace('            if (d<best && d<dp(68)) { best=d; idx=4+i; }',
              '            if (d<best && d<dp(80)) { best=d; idx=4+i; }', 1)

crop.write_text(s, encoding='utf-8')

# Bump app version after all previous release patches have run.
build = Path('app/build.gradle')
b = build.read_text(encoding='utf-8')
b = re.sub(r'versionCode\\s+\\d+', 'versionCode 154', b, count=1)
b = re.sub(r"versionName\\s+'[^']+'", "versionName '1.5.4'", b, count=1)
build.write_text(b, encoding='utf-8')

# Update visible build label when present.
main = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
m = main.read_text(encoding='utf-8')
m = m.replace('PIXEL DOC TOOL  v1.5.3', 'PIXEL DOC TOOL  v1.5.4')
main.write_text(m, encoding='utf-8')

print('v1.5.4 edge-safe crop patch applied')
