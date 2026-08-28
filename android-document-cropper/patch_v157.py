from pathlib import Path
import re

# v1.5.7
# - ML Kit scan result is already cropped/perspective-corrected: do NOT force a second crop.
# - Manual/gallery/camera crop keeps the 8-point editor.
# - Smaller CamScanner-like visual handles while keeping large invisible hit targets.
# - Lightweight live edge snapping: precompute a small gradient map once, then snap only
#   the actively dragged edge. No full OpenCV pass is executed on MotionEvent MOVE.

MAIN = Path('app/src/main/java/com/quanyi/docscanner/MainActivity.java')
CROP = Path('app/src/main/java/com/quanyi/docscanner/DocumentCropView.java')
BUILD = Path('app/build.gradle')

m = MAIN.read_text(encoding='utf-8')
c = CROP.read_text(encoding='utf-8')


def must_replace(text, old, new, label, count=1):
    if old not in text:
        raise SystemExit(f'v157 patch failed: {label}')
    return text.replace(old, new, count)

# -----------------------------------------------------------------------------
# AI flow: Google ML Kit FULL scanner has already performed crop + perspective
# correction. Build only lightweight preview cache and go directly to result.
# The original returned URI remains the source for HQ save/share.
# -----------------------------------------------------------------------------
ai_pattern = re.compile(
    r'    private void startAiBatch\(ArrayList<Uri> picked\) \{.*?\n    \}\n\n    private void beginBatch',
    re.S,
)
ai_replacement = '''    private void startAiBatch(ArrayList<Uri> picked) {\n        aiScannedBatch = true;\n        prepareAiBatchForPreview(picked);\n    }\n\n    private void prepareAiBatchForPreview(ArrayList<Uri> picked) {\n        clearBatchFiles();\n        selectedUris.clear();\n        croppedFiles.clear();\n        cropHistory.clear();\n        returnToResultAfterEdit = false;\n        currentIndex = 0;\n        previewIndex = 0;\n        if (picked == null || picked.isEmpty()) { showHome(); return; }\n\n        ProgressDialog d = ProgressDialog.show(this, "AI 掃描完成", "正在建立快速預覽…", true, false);\n        new Thread(() -> {\n            ArrayList<Uri> okUris = new ArrayList<>();\n            ArrayList<File> okFiles = new ArrayList<>();\n            try {\n                File dir = new File(getCacheDir(), "batch_raw");\n                if (!dir.exists() && !dir.mkdirs()) throw new Exception("cache");\n                for (int i=0; i<picked.size() && i<MAX_IMAGES; i++) {\n                    final int n = i;\n                    runOnUiThread(() -> d.setMessage("建立預覽 " + (n+1) + " / " + Math.min(picked.size(), MAX_IMAGES) + "…"));\n                    Bitmap preview = null;\n                    try {\n                        Uri uri = picked.get(i);\n                        preview = ImageUtils.loadBitmap(getContentResolver(), uri, previewMaxDimension());\n                        File f = new File(dir, String.format(Locale.US, "raw_%02d.jpg", okFiles.size()+1));\n                        try (FileOutputStream stream = new FileOutputStream(f)) {\n                            if (!preview.compress(Bitmap.CompressFormat.JPEG, 96, stream)) throw new Exception("encode");\n                        }\n                        okUris.add(uri);\n                        okFiles.add(f);\n                    } catch (Throwable ignored) {\n                    } finally {\n                        if (preview != null && !preview.isRecycled()) preview.recycle();\n                    }\n                }\n            } catch (Throwable ignored) {\n            }\n\n            runOnUiThread(() -> {\n                d.dismiss();\n                selectedUris.clear();\n                selectedUris.addAll(okUris);\n                croppedFiles.clear();\n                croppedFiles.addAll(okFiles);\n                cropHistory.clear();\n                ensureCropHistorySize();\n                // ML Kit returned pages are already cropped. Preserve the whole returned page\n                // so HQ save/share never performs a destructive second crop.\n                for (int i=0; i<cropHistory.size(); i++) {\n                    cropHistory.set(i, new float[]{0f,0f, 1f,0f, 1f,1f, 0f,1f});\n                }\n                currentIndex = selectedUris.size();\n                previewIndex = 0;\n                if (!croppedFiles.isEmpty()) {\n                    showBatchResult();\n                } else {\n                    Toast.makeText(this, "AI 掃描結果無法讀取，請再試一次。", Toast.LENGTH_LONG).show();\n                    showHome();\n                }\n            });\n        }).start();\n    }\n\n    private void beginBatch'''
m, n = ai_pattern.subn(ai_replacement, m, count=1)
if n != 1:
    raise SystemExit(f'v157 patch failed: AI batch method count={n}')

# If user wants to redo an AI scan, return to Google's scanner instead of entering
# a second in-app crop that can only make the already-cropped page smaller.
old_edit = '''    private void editCurrentPreview() {\n        if (selectedUris.isEmpty() || croppedFiles.isEmpty()) return;\n        returnToResultAfterEdit = true;\n        currentIndex = Math.max(0, Math.min(previewIndex, selectedUris.size()-1));\n        loadCurrentDocument();\n    }'''
new_edit = '''    private void editCurrentPreview() {\n        if (selectedUris.isEmpty() || croppedFiles.isEmpty()) return;\n        if (aiScannedBatch) {\n            new AlertDialog.Builder(this)\n                    .setTitle("重新 AI 掃描？")\n                    .setMessage("AI 智慧掃描已在 Google 掃描器中完成抓邊與透視校正，因此不再進行第二次裁切。若邊界不滿意，可重新開啟 AI 掃描調整。")\n                    .setNegativeButton("保留目前結果", null)\n                    .setPositiveButton("重新 AI 掃描", (dialog, which) -> startAiScan())\n                    .show();\n            return;\n        }\n        returnToResultAfterEdit = true;\n        currentIndex = Math.max(0, Math.min(previewIndex, selectedUris.size()-1));\n        loadCurrentDocument();\n    }'''
m = must_replace(m, old_edit, new_edit, 'AI edit behavior')

m = must_replace(m,
    'Button editTop = button("← 修改裁切", false);',
    'Button editTop = button(aiScannedBatch ? "↻ 重新 AI 掃描" : "← 修改裁切", false);',
    'result edit button')

# Explain why AI no longer shows a redundant crop step.
result_title_anchor = '''        title.setGravity(Gravity.CENTER);\n        page.addView(title);'''
result_title_new = '''        title.setGravity(Gravity.CENTER);\n        page.addView(title);\n        if (aiScannedBatch) {\n            TextView aiDone = text("✓ AI 已完成抓邊與透視校正｜直接預覽，不再二次裁切", 12, true);\n            aiDone.setTextColor(GREEN);\n            aiDone.setGravity(Gravity.CENTER);\n            page.addView(aiDone);\n        }'''
m = must_replace(m, result_title_anchor, result_title_new, 'AI result explanation')

# Manual crop hint: edge handles now live-snap to the nearby paper boundary.
m = m.replace('拖曳時會顯示放大鏡。', '拖曳時會顯示放大鏡；拖四邊時會即時自動貼齊附近的紙張邊緣。')
m = m.replace('畫面四周已保留手指操作空間。', '畫面四周已保留手指操作空間；拖四邊時會即時自動貼邊。')
m = m.replace('PIXEL DOC TOOL  v1.5.6', 'PIXEL DOC TOOL  v1.5.7')
MAIN.write_text(m, encoding='utf-8')

# -----------------------------------------------------------------------------
# Crop view: smaller visuals, same big hit areas, one-time lightweight edge map.
# -----------------------------------------------------------------------------
field_anchor = '''    private PointF[] dragStartCorners;'''
field_new = '''    private PointF[] dragStartCorners;\n\n    // Small precomputed gradient map for live edge snapping.\n    // It is built once per image and reused during drag, so ACTION_MOVE stays cheap.\n    private int[] snapGradX;\n    private int[] snapGradY;\n    private int snapW;\n    private int snapH;\n    private boolean snapActive = false;'''
c = must_replace(c, field_anchor, field_new, 'snap fields')

c = must_replace(c,
    '''        handleSize = dp(17);\n        edgeLong = dp(25);\n        edgeShort = dp(11);''',
    '''        // CamScanner-style compact visual handles. Invisible touch targets remain large.\n        handleSize = dp(10);\n        edgeLong = dp(18);\n        edgeShort = dp(7);''',
    'smaller handles')

c = must_replace(c,
    '''        linePaint.setStyle(Paint.Style.STROKE);\n        linePaint.setStrokeWidth(dp(3));''',
    '''        linePaint.setStyle(Paint.Style.STROKE);\n        linePaint.setStrokeWidth(dp(2));''',
    'thin crop line')

c = must_replace(c,
    '''        previewBitmap = buildPreviewBitmap(b);\n        corners = p;''',
    '''        previewBitmap = buildPreviewBitmap(b);\n        buildSnapEdgeMap(b);\n        corners = p;''',
    'build snap map')

old_corner_draw = '''        for (int i=0;i<4;i++) {\n            float x=v[i*2], y=v[i*2+1];\n            c.drawRect(x-handleSize,y-handleSize,x+handleSize,y+handleSize,handlePaint);\n            c.drawRect(x-handleSize,y-handleSize,x+handleSize,y+handleSize,handleBorder);\n            c.drawLine(x-dp(6),y,x+dp(6),y,handleBorder);\n            c.drawLine(x,y-dp(6),x,y+dp(6),handleBorder);\n        }'''
new_corner_draw = '''        for (int i=0;i<4;i++) {\n            float x=v[i*2], y=v[i*2+1];\n            c.drawCircle(x, y, handleSize, handlePaint);\n            c.drawCircle(x, y, handleSize, handleBorder);\n        }'''
c = must_replace(c, old_corner_draw, new_corner_draw, 'round corner handles')

old_edge_draw = '''    private void drawEdgeHandle(Canvas c, PointF p, boolean horizontal) {\n        float hw = horizontal ? edgeLong : edgeShort;\n        float hh = horizontal ? edgeShort : edgeLong;\n        c.drawRect(p.x-hw,p.y-hh,p.x+hw,p.y+hh,handlePaint);\n        c.drawRect(p.x-hw,p.y-hh,p.x+hw,p.y+hh,handleBorder);\n        if (horizontal) c.drawLine(p.x-dp(10),p.y,p.x+dp(10),p.y,handleBorder);\n        else c.drawLine(p.x,p.y-dp(10),p.x,p.y+dp(10),handleBorder);\n    }'''
new_edge_draw = '''    private void drawEdgeHandle(Canvas c, PointF p, boolean horizontal) {\n        float hw = horizontal ? edgeLong : edgeShort;\n        float hh = horizontal ? edgeShort : edgeLong;\n        RectF r = new RectF(p.x-hw,p.y-hh,p.x+hw,p.y+hh);\n        float radius = dp(7);\n        c.drawRoundRect(r, radius, radius, handlePaint);\n        c.drawRoundRect(r, radius, radius, handleBorder);\n    }'''
c = must_replace(c, old_edge_draw, new_edge_draw, 'pill edge handles')

c = must_replace(c,
    'c.drawText("精細 0.36×", lensX, lensY + radius + dp(16), lensLabel);',
    'c.drawText(active >= 4 ? (snapActive ? "自動貼邊 ✓" : "自動貼邊") : "精細 0.36×", lensX, lensY + radius + dp(16), lensLabel);',
    'magnifier snap label')

# Set/clear snap status in touch flow.
c = must_replace(c,
    '''            active=nearestHandle(e.getX(),e.getY());\n            if (active >= 0) {''',
    '''            active=nearestHandle(e.getX(),e.getY());\n            snapActive=false;\n            if (active >= 0) {''',
    'touch down snap reset')

c = must_replace(c,
    '''            if (active < 4) moveCornerPrecise(active,dx,dy);\n            else moveEdgePrecise(active,dx,dy);''',
    '''            if (active < 4) {\n                snapActive=false;\n                moveCornerPrecise(active,dx,dy);\n            } else {\n                moveEdgePrecise(active,dx,dy);\n                snapActive=snapEdgeToDocument(active);\n            }''',
    'live snap on edge drag')

c = must_replace(c,
    '''            active=-1;\n            dragStartCorners=null;\n            showMagnifier=false;''',
    '''            active=-1;\n            dragStartCorners=null;\n            showMagnifier=false;\n            snapActive=false;''',
    'touch up snap reset')

# Insert lightweight gradient/snap helpers before focus helper.
snap_anchor = '''    private void updateFocusFromActive() {'''
snap_helpers = '''    private void buildSnapEdgeMap(Bitmap source) {\n        snapGradX = null;\n        snapGradY = null;\n        snapW = snapH = 0;\n        if (source == null || source.isRecycled()) return;\n        Bitmap small = null;\n        try {\n            int longest = Math.max(source.getWidth(), source.getHeight());\n            int target = 720;\n            float scale = longest > target ? target / (float)longest : 1f;\n            snapW = Math.max(32, Math.round(source.getWidth() * scale));\n            snapH = Math.max(32, Math.round(source.getHeight() * scale));\n            small = scale < 1f ? Bitmap.createScaledBitmap(source, snapW, snapH, true) : source;\n            int[] px = new int[snapW * snapH];\n            int[] gray = new int[snapW * snapH];\n            small.getPixels(px, 0, snapW, 0, 0, snapW, snapH);\n            for (int i=0;i<px.length;i++) {\n                int color=px[i];\n                gray[i]=(Color.red(color)*30 + Color.green(color)*59 + Color.blue(color)*11)/100;\n            }\n            snapGradX = new int[snapW * snapH];\n            snapGradY = new int[snapW * snapH];\n            for (int y=1;y<snapH-1;y++) {\n                int row=y*snapW;\n                for (int x=1;x<snapW-1;x++) {\n                    int i=row+x;\n                    snapGradX[i]=Math.abs(gray[i+1]-gray[i-1]);\n                    snapGradY[i]=Math.abs(gray[i+snapW]-gray[i-snapW]);\n                }\n            }\n        } catch (Throwable ignored) {\n            snapGradX = null; snapGradY = null; snapW = snapH = 0;\n        } finally {\n            if (small != null && small != source && !small.isRecycled()) small.recycle();\n        }\n    }\n\n    private boolean snapEdgeToDocument(int edge) {\n        if (bitmap == null || snapGradX == null || snapGradY == null || snapW < 8 || snapH < 8) return false;\n        if (edge < 4 || edge > 7) return false;\n        float radius=Math.max(8f, Math.min(bitmap.getWidth(),bitmap.getHeight())*0.022f);\n        float step=Math.max(1f, radius/12f);\n        float base=edgeSnapScore(edge,0f);\n        float bestRaw=base;\n        float bestAdjusted=base;\n        float bestDelta=0f;\n        for (float d=-radius; d<=radius; d+=step) {\n            if (Math.abs(d) < step*0.45f) continue;\n            float raw=edgeSnapScore(edge,d);\n            float adjusted=raw-(Math.abs(d)/radius)*18f;\n            if (adjusted > bestAdjusted) {\n                bestAdjusted=adjusted;\n                bestRaw=raw;\n                bestDelta=d;\n            }\n        }\n        if (bestRaw < 22f) return false;\n        if (Math.abs(bestDelta) >= 0.5f && bestAdjusted > base + 3f) {\n            shiftCurrentEdge(edge,bestDelta);\n            return true;\n        }\n        // Already sitting on a strong edge: show the user that auto-align is active.\n        return base >= 30f;\n    }\n\n    private float edgeSnapScore(int edge, float delta) {\n        PointF a,b;\n        if (edge==4) { a=corners[0]; b=corners[1]; }\n        else if (edge==5) { a=corners[1]; b=corners[2]; }\n        else if (edge==6) { a=corners[3]; b=corners[2]; }\n        else { a=corners[0]; b=corners[3]; }\n        boolean horizontal=(edge==4 || edge==6);\n        int[] grad=horizontal ? snapGradY : snapGradX;\n        float sx=snapW/(float)Math.max(1,bitmap.getWidth());\n        float sy=snapH/(float)Math.max(1,bitmap.getHeight());\n        float sum=0f;\n        int count=0;\n        final int samples=30;\n        for (int i=0;i<samples;i++) {\n            float t=0.08f + (0.84f*i)/(samples-1f);\n            float ox=a.x+(b.x-a.x)*t + (horizontal ? 0f : delta);\n            float oy=a.y+(b.y-a.y)*t + (horizontal ? delta : 0f);\n            int x=Math.round(ox*sx);\n            int y=Math.round(oy*sy);\n            if (x<2 || x>=snapW-2 || y<2 || y>=snapH-2) continue;\n            int idx=y*snapW+x;\n            int v=grad[idx];\n            if (horizontal) v=Math.max(v,Math.max(grad[idx-snapW],grad[idx+snapW]));\n            else v=Math.max(v,Math.max(grad[idx-1],grad[idx+1]));\n            sum+=v;\n            count++;\n        }\n        return count>0 ? sum/count : 0f;\n    }\n\n    private void shiftCurrentEdge(int edge, float delta) {\n        float gap=Math.max(12f,Math.min(bitmap.getWidth(),bitmap.getHeight())*0.025f);\n        if (edge==4) {\n            float min=-Math.min(corners[0].y,corners[1].y);\n            float max=Math.min(corners[3].y-gap-corners[0].y,corners[2].y-gap-corners[1].y);\n            float d=clamp(delta,min,max);\n            corners[0].y+=d; corners[1].y+=d;\n        } else if (edge==6) {\n            float min=Math.max(corners[1].y+gap-corners[2].y,corners[0].y+gap-corners[3].y);\n            float max=bitmap.getHeight()-Math.max(corners[2].y,corners[3].y);\n            float d=clamp(delta,min,max);\n            corners[2].y+=d; corners[3].y+=d;\n        } else if (edge==5) {\n            float min=Math.max(corners[0].x+gap-corners[1].x,corners[3].x+gap-corners[2].x);\n            float max=bitmap.getWidth()-Math.max(corners[1].x,corners[2].x);\n            float d=clamp(delta,min,max);\n            corners[1].x+=d; corners[2].x+=d;\n        } else if (edge==7) {\n            float min=-Math.min(corners[0].x,corners[3].x);\n            float max=Math.min(corners[1].x-gap-corners[0].x,corners[2].x-gap-corners[3].x);\n            float d=clamp(delta,min,max);\n            corners[0].x+=d; corners[3].x+=d;\n        }\n    }\n\n'''
if snap_anchor not in c:
    raise SystemExit('v157 patch failed: snap helper anchor')
c = c.replace(snap_anchor, snap_helpers + snap_anchor, 1)

# Clear the snap buffers together with preview resources.
recycle_old = '''        previewBitmap = null;\n    }'''
recycle_new = '''        previewBitmap = null;\n        snapGradX = null;\n        snapGradY = null;\n        snapW = snapH = 0;\n    }'''
# Use the occurrence inside recyclePreview (last matching small block).
pos = c.rfind(recycle_old)
if pos < 0:
    raise SystemExit('v157 patch failed: recyclePreview block')
c = c[:pos] + recycle_new + c[pos+len(recycle_old):]

CROP.write_text(c, encoding='utf-8')

# -----------------------------------------------------------------------------
# Version.
# -----------------------------------------------------------------------------
b = BUILD.read_text(encoding='utf-8')
b, n1 = re.subn(r'versionCode\s+\d+', 'versionCode 157', b, count=1)
b, n2 = re.subn(r"versionName\s+'[^']+'", "versionName '1.5.7'", b, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit(f'v157 version patch failed: code={n1}, name={n2}')
BUILD.write_text(b, encoding='utf-8')

print('v1.5.7 single-pass AI + compact handles + live edge snapping applied')
