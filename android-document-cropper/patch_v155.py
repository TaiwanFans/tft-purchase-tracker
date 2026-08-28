from pathlib import Path
import re

# v1.5.5: accuracy-first document detection + true crop safe area.
# This patch is intentionally applied AFTER v1.5.4.

image = Path('app/src/main/java/com/quanyi/docscanner/ImageUtils.java')
s = image.read_text(encoding='utf-8')

def rep(old, new, label, count=1):
    global s
    if old not in s:
        raise SystemExit(f'v155 patch failed: {label}')
    s = s.replace(old, new, count)

# ---------- Detection: keep it fast, but broaden candidate generation ----------
rep('final int maxDetectDim = 1280;', 'final int maxDetectDim = 1440;', 'detect resolution')
rep('int[][] canny = {{24,72},{36,108},{52,156},{72,216}};',
    'int[][] canny = {{16,48},{24,72},{36,108},{52,156},{72,216},{96,255}};',
    'multi canny thresholds')

# Broken outer page edges are common with shadows / table-heavy purchase orders.
anchor = '''            Mat adaptive = new Mat();\n'''
wide_edge = '''            Mat edgeWide = edgeReference.clone();\n            Mat edgeWideKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(11,11));\n            Imgproc.morphologyEx(edgeWide, edgeWide, Imgproc.MORPH_CLOSE, edgeWideKernel);\n            edgeWideKernel.release();\n            maps.add(edgeWide);\n            findQuadCandidates(edgeWide, rgba.cols(), rgba.rows(), 0, candidates);\n\n'''
if anchor not in s:
    raise SystemExit('v155 patch failed: adaptive anchor')
s = s.replace(anchor, wide_edge + anchor, 1)

# A second adaptive map catches uneven lighting where a single block size misses the paper perimeter.
anchor = '''            Mat otsu = new Mat();\n'''
adaptive2 = '''            Mat adaptive2 = new Mat();\n            Imgproc.adaptiveThreshold(gray, adaptive2, 255,\n                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 51, 7);\n            Mat adaptiveKernel2 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(13,13));\n            Imgproc.morphologyEx(adaptive2, adaptive2, Imgproc.MORPH_CLOSE, adaptiveKernel2);\n            adaptiveKernel2.release();\n            maps.add(adaptive2);\n            findQuadCandidates(adaptive2, rgba.cols(), rgba.rows(), 1, candidates);\n\n'''
if anchor not in s:
    raise SystemExit('v155 patch failed: otsu anchor')
s = s.replace(anchor, adaptive2 + anchor, 1)

# Shadow-tolerant paper mask. The previous V>=108/S<=125 gate was too strict for warm lights and shaded paper.
rep('Core.inRange(hsv, new Scalar(0, 0, 108), new Scalar(180, 125, 255), paperMask);',
    'Core.inRange(hsv, new Scalar(0, 0, 92), new Scalar(180, 155, 255), paperMask);',
    'paper hsv mask')

anchor = '''            Candidate best = chooseBestCandidate(candidates, gray, hsv, edgeReference, rgba.cols(), rgba.rows());\n'''
shadow_mask = '''            Mat paperShadow = new Mat();\n            Core.inRange(hsv, new Scalar(0, 0, 72), new Scalar(180, 185, 255), paperShadow);\n            Mat paperShadowClose = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(31,31));\n            Mat paperShadowOpen = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7,7));\n            Imgproc.morphologyEx(paperShadow, paperShadow, Imgproc.MORPH_CLOSE, paperShadowClose);\n            Imgproc.morphologyEx(paperShadow, paperShadow, Imgproc.MORPH_OPEN, paperShadowOpen);\n            paperShadowClose.release();\n            paperShadowOpen.release();\n            maps.add(paperShadow);\n            findQuadCandidates(paperShadow, rgba.cols(), rgba.rows(), 3, candidates);\n\n'''
if anchor not in s:
    raise SystemExit('v155 patch failed: best candidate anchor')
s = s.replace(anchor, shadow_mask + anchor, 1)

# Always compare the dominant-line candidate. Previously a strong internal table rectangle could suppress Hough fallback.
rep('if (best == null || best.score < 54.0) {', 'if (best == null || best.score < 120.0) {', 'always evaluate hough')
rep('if (best == null || best.score < 39.0) return fallbackDetectDocumentCorners(src);',
    'if (best == null || best.score < 36.0) return fallbackDetectDocumentCorners(src);',
    'final candidate confidence')

# Near-full-frame documents are valid scans. Do not reject them just because they occupy ~99% of the photo.
s = s.replace('contourArea < imageArea * 0.07 || contourArea > imageArea * 0.992',
              'contourArea < imageArea * 0.05 || contourArea > imageArea * 0.9995')
s = s.replace('areaRatio < 0.07 || areaRatio > 0.992',
              'areaRatio < 0.05 || areaRatio > 0.9995')

# When every detector fails, a conservative near-frame crop is more useful for an obvious full-page scan than a 5% inset.
rep('float mx = b.getWidth() * 0.05f, my = b.getHeight() * 0.05f;',
    'float mx = b.getWidth() * 0.025f, my = b.getHeight() * 0.025f;',
    'default corner inset')

image.write_text(s, encoding='utf-8')

# ---------- Crop UI: real safe canvas margin for ALL sources ----------
crop = Path('app/src/main/java/com/quanyi/docscanner/DocumentCropView.java')
c = crop.read_text(encoding='utf-8')

old = '''    private float cropSideInsetPx() {\n        // Keep document handles away from Android back-gesture / physical screen edges.\n        // Use a responsive inset so small phones do not lose too much document area.\n        return clamp(getWidth() * 0.060f, dp(18), dp(28));\n    }\n\n    private float cropVerticalInsetPx() {\n        return clamp(getHeight() * 0.018f, dp(8), dp(16));\n    }\n'''
new = '''    private float cropSideInsetPx() {\n        // True finger-safe canvas. The document itself is rendered inward,\n        // while all crop coordinates remain in original-image coordinates.\n        return clamp(getWidth() * 0.090f, dp(30), dp(46));\n    }\n\n    private float cropVerticalInsetPx() {\n        return clamp(getHeight() * 0.030f, dp(14), dp(28));\n    }\n'''
if old not in c:
    raise SystemExit('v155 patch failed: crop safe inset block')
c = c.replace(old, new, 1)

# Large invisible hit targets; visual handles stay the same size.
c = c.replace('float best=dp(94);', 'float best=dp(110);', 1)
c = c.replace('d<dp(80)', 'd<dp(92)', 1)
crop.write_text(c, encoding='utf-8')

# ---------- Version / user hint ----------
build = Path('app/build.gradle')
b = build.read_text(encoding='utf-8')
b = re.sub(r'versionCode\\s+\\d+', 'versionCode 155', b, count=1)
b = re.sub(r"versionName\\s+'[^']+'", "versionName '1.5.5'", b, count=1)
build.write_text(b, encoding='utf-8')

main = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
m = main.read_text(encoding='utf-8')
m = m.replace('PIXEL DOC TOOL  v1.5.4', 'PIXEL DOC TOOL  v1.5.5')
m = m.replace('四邊中點精修。', '四邊中點精修。畫面四周已保留手指操作空間。')
main.write_text(m, encoding='utf-8')

print('v1.5.5 stronger detection + finger-safe crop applied')
