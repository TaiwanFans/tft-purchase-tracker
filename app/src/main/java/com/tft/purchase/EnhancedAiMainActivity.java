package com.tft.purchase;

import android.content.ClipData;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.documentscanner.GmsDocumentScanner;
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning;
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enhanced layer over the existing procurement UI.
 * V2.0.17 adds a persistent/reorderable AI queue, direct result links and a permanent
 * recognition-database settings entry.
 */
public class EnhancedAiMainActivity extends AiMainActivity {
    private static final int REQ_DOCUMENT_SCAN = 901;
    private static final int REQ_FALLBACK_GALLERY = 902;
    private static final String HOOK_TAG = "tft_doc_scan_v217";
    private static final String KNOWLEDGE_TAG = "tft_knowledge_v217";
    private static final String QUEUE_PANEL_TAG = "tft_queue_panel_v217";
    private static final String QUEUE_SUMMARY_TAG = "tft_queue_summary_v217";
    private static final String QUEUE_LIST_TAG = "tft_queue_list_v217";

    private GmsDocumentScanner scanner;
    private final Handler enhancedHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> thumbCache = new HashMap<>();
    private String queueStructureFingerprint = "";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PlayServicesModuleManager.prefetch(this);
        scanner = GmsDocumentScanning.getClient(new GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(20)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build());
    }

    @Override protected void onResume() {
        super.onResume();
        enhancedHandler.removeCallbacks(enhancedRefresh);
        enhancedHandler.post(enhancedRefresh);
    }

    @Override protected void onPause() {
        enhancedHandler.removeCallbacks(enhancedRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        enhancedHandler.removeCallbacks(enhancedRefresh);
        thumbCache.clear();
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refreshInjectedUi();
    }

    private final Runnable enhancedRefresh = new Runnable() {
        @Override public void run() {
            refreshInjectedUi();
            enhancedHandler.postDelayed(this, 900);
        }
    };

    private void refreshInjectedUi() {
        if (getWindow() == null || getWindow().getDecorView() == null) return;
        View root = getWindow().getDecorView();
        hookRecursive(root);
        injectKnowledgeButton(root);
        injectQueuePanel(root);
        rewriteOldVersionLabel(root);
    }

    /** Replaces parent photo buttons and, importantly, re-enables them while AI is already running. */
    private void hookRecursive(View view) {
        if (view == null) return;
        if (view instanceof Button) {
            Button button = (Button) view;
            CharSequence cs = button.getText();
            String text = cs == null ? "" : cs.toString();
            boolean acquisition = text.contains("選擇採購單照片") || text.contains("AI 分析新採購單")
                    || text.contains("掃描／相簿匯入採購單") || text.contains("掃描／匯入採購單");
            if (acquisition) {
                button.setEnabled(true);
                if (!HOOK_TAG.equals(button.getTag())) {
                    button.setTag(HOOK_TAG);
                    if (text.contains("AI 分析新採購單") || text.startsWith("＋")) button.setText("＋ 掃描／匯入採購單（可排隊）");
                    else button.setText("掃描／相簿匯入採購單 → AI 排隊分析");
                    button.setOnClickListener(v -> launchDocumentScanner());
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hookRecursive(group.getChildAt(i));
        }
    }

    /** Permanent and prominent database entry inside the normal Settings page. */
    private void injectKnowledgeButton(View root) {
        LinearLayout settings = findContainerWithDirectText(root, "AI 辨識");
        if (settings == null || settings.findViewWithTag(KNOWLEDGE_TAG) != null) return;
        int aiIndex = directTextIndex(settings, "AI 辨識");

        LinearLayout block = new LinearLayout(this);
        block.setTag(KNOWLEDGE_TAG);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(13), dp(11), dp(13), dp(11));
        block.setBackground(box(Color.WHITE, Color.rgb(8,145,178), 2));

        block.addView(label("辨識資料庫設定", 17, Color.rgb(15,42,92), true));
        int vendorCount = 0;
        try { vendorCount = new RecognitionKnowledgeDb(this).listVendors().size(); } catch (Throwable ignored) {}
        block.addView(label("可隨時新增、修改、刪除廠商、地址、辨識關鍵字與常購品項。目前廠商資料：" + vendorCount + " 筆。", 13, Color.rgb(71,85,105), false));
        Button b = actionButton("開啟辨識知識庫／廠商資料庫", Color.rgb(8,145,178));
        b.setOnClickListener(v -> startActivity(new Intent(this, KnowledgeBaseActivity.class)));
        block.addView(b, fullMargins(8,2));

        int insertAt = Math.min(settings.getChildCount(), Math.max(0, aiIndex + 2));
        settings.addView(block, insertAt, fullMargins(6,8));
    }

    /** Adds the live queue to the parent Analysis screen without rebuilding the existing screen. */
    private void injectQueuePanel(View root) {
        LinearLayout page = findAnalysisPage(root);
        if (page == null) {
            queueStructureFingerprint = "";
            return;
        }

        LinearLayout panel = null;
        View tagged = page.findViewWithTag(QUEUE_PANEL_TAG);
        if (tagged instanceof LinearLayout) panel = (LinearLayout) tagged;
        if (panel == null) {
            panel = new LinearLayout(this);
            panel.setTag(QUEUE_PANEL_TAG);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(13),dp(12),dp(13),dp(12));
            panel.setBackground(box(Color.WHITE, Color.rgb(8,145,178), 2));
            panel.addView(label("AI 分析排隊", 18, Color.rgb(15,42,92), true));
            TextView summary = label("",14,Color.rgb(71,85,105),false);
            summary.setTag(QUEUE_SUMMARY_TAG);
            panel.addView(summary);
            Button add = actionButton("＋ 繼續掃描／選照片加入排隊", Color.rgb(37,99,235));
            add.setOnClickListener(v -> launchDocumentScanner());
            panel.addView(add, fullMargins(8,8));
            LinearLayout list = new LinearLayout(this);
            list.setTag(QUEUE_LIST_TAG);
            list.setOrientation(LinearLayout.VERTICAL);
            panel.addView(list);

            int homeIndex = directButtonIndex(page, "回首頁");
            if (homeIndex < 0) homeIndex = page.getChildCount();
            page.addView(panel, homeIndex, fullMargins(8,10));
        }

        AiJobStore.Snapshot s = AiJobStore.get(this);
        View summaryView = panel.findViewWithTag(QUEUE_SUMMARY_TAG);
        if (summaryView instanceof TextView) {
            int waiting = s.running() ? Math.max(0, s.queue.size() - s.nextIndex - 1) : 0;
            String state = s.running()
                    ? "總進度 " + s.progress + "%｜目前第 " + Math.max(1, s.current) + "/" + Math.max(1,s.queue.size()) + " 張｜等待 " + waiting + " 張"
                    : (AiJobStore.STATE_DONE.equals(s.state) ? "本次排隊已完成｜共 " + s.queue.size() + " 張｜點每張的『查看結果』直接核對" : "目前沒有進行中的排隊工作");
            ((TextView) summaryView).setText(state);
        }

        LinearLayout list = null;
        View listView = panel.findViewWithTag(QUEUE_LIST_TAG);
        if (listView instanceof LinearLayout) list = (LinearLayout) listView;
        if (list == null) return;

        String structure = structureFingerprint(s);
        if (!structure.equals(queueStructureFingerprint)) {
            list.removeAllViews();
            for (int i = 0; i < s.queue.size(); i++) list.addView(queueRow(s, i), fullMargins(3,4));
            if (s.queue.isEmpty()) list.addView(label("尚未加入採購單圖片。",14,Color.rgb(100,116,139),false));
            queueStructureFingerprint = structure;
        }
        updateQueueStatusTexts(list, s);
    }

    private LinearLayout queueRow(AiJobStore.Snapshot s, int index) {
        AiJobStore.QueueItem q = s.queue.get(index);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        outer.setPadding(dp(8),dp(8),dp(8),dp(8));
        outer.setBackground(box(Color.rgb(248,250,252), Color.rgb(203,213,225), 1));

        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(box(Color.WHITE,Color.rgb(148,163,184),1));
        Bitmap bm = queueThumb(q.path);
        if (bm != null) thumb.setImageBitmap(bm);
        outer.addView(thumb, new LinearLayout.LayoutParams(dp(62),dp(78)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(10),0,dp(6),0);
        TextView name = label("#" + (index + 1) + (q.replaceId > 0 ? "｜重新分析既有採購單" : "｜新採購單"),14,Color.rgb(15,42,92),true);
        mid.addView(name);
        TextView status = label(queueStatus(s,index),13,statusColor(s,index),false);
        status.setTag("queue_status_" + q.id);
        mid.addView(status);
        outer.addView(mid,new LinearLayout.LayoutParams(0,-2,1));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);

        long resultId = resultIdFor(s,index);
        if (resultId > 0) {
            Button result = smallButton("查看結果");
            result.setTextColor(Color.rgb(22,101,52));
            result.setOnClickListener(v -> openResult(resultId));
            controls.addView(result);
            outer.setOnClickListener(v -> openResult(resultId));
        }

        boolean waiting = s.running() && index > s.nextIndex;
        Button up = smallButton("↑ 上移");
        up.setEnabled(waiting && index > s.nextIndex + 1);
        up.setOnClickListener(v -> { if (AiJobStore.movePending(this,index,index-1)) { queueStructureFingerprint=""; refreshInjectedUi(); } });
        controls.addView(up);
        Button down = smallButton("↓ 下移");
        down.setEnabled(waiting && index < s.queue.size()-1);
        down.setOnClickListener(v -> { if (AiJobStore.movePending(this,index,index+1)) { queueStructureFingerprint=""; refreshInjectedUi(); } });
        controls.addView(down);
        outer.addView(controls);
        return outer;
    }

    private long resultIdFor(AiJobStore.Snapshot s,int index){
        if(s==null||s.lastIds==null||index<0||index>=s.lastIds.size())return -1;
        Long id=s.lastIds.get(index);return id==null?-1:id;
    }

    private void openResult(long id){
        if(id<=0)return;
        Intent i=new Intent(this,PurchaseEditActivity.class);
        i.putExtra("purchase_id",id);
        startActivity(i);
    }

    private void updateQueueStatusTexts(LinearLayout list, AiJobStore.Snapshot s) {
        for (int i=0;i<s.queue.size();i++) {
            AiJobStore.QueueItem q=s.queue.get(i);
            View v=list.findViewWithTag("queue_status_"+q.id);
            if(v instanceof TextView){((TextView)v).setText(queueStatus(s,i));((TextView)v).setTextColor(statusColor(s,i));}
        }
    }

    private String queueStatus(AiJobStore.Snapshot s, int index) {
        long resultId=resultIdFor(s,index);
        if (resultId>0 || AiJobStore.STATE_DONE.equals(s.state) || index < s.nextIndex) return "✓ 已完成｜可直接查看 AI 結果";
        if (s.running() && index == s.nextIndex) return "● 分析中 " + s.itemProgress + "%｜" + shortStage(s.stage);
        if (s.running() && index > s.nextIndex) return "等待中｜前面還有 " + (index - s.nextIndex) + " 張";
        if (AiJobStore.STATE_ERROR.equals(s.state) && index >= s.nextIndex) return "！尚未完成，可重新進入後再分析";
        return "等待處理";
    }

    private int statusColor(AiJobStore.Snapshot s,int index){
        if(resultIdFor(s,index)>0||AiJobStore.STATE_DONE.equals(s.state)||index<s.nextIndex)return Color.rgb(22,163,74);
        if(s.running()&&index==s.nextIndex)return Color.rgb(8,145,178);
        if(AiJobStore.STATE_ERROR.equals(s.state))return Color.rgb(220,38,38);
        return Color.rgb(100,116,139);
    }

    private String shortStage(String s){if(s==null)return"";int p=s.indexOf('｜');if(p>=0&&p+1<s.length())return s.substring(p+1);return s;}

    private String structureFingerprint(AiJobStore.Snapshot s){
        StringBuilder b=new StringBuilder(s.state).append('|').append(s.nextIndex).append('|').append(s.lastIds==null?0:s.lastIds.size()).append('|');
        for(AiJobStore.QueueItem q:s.queue)b.append(q.id).append(';');
        return b.toString();
    }

    private Bitmap queueThumb(String path) {
        if (path == null || path.isEmpty()) return null;
        Bitmap cached = thumbCache.get(path);
        if (cached != null && !cached.isRecycled()) return cached;
        try {
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,bounds);
            int sample=1;while(bounds.outWidth/sample>240||bounds.outHeight/sample>320)sample*=2;
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=Math.max(1,sample);opts.inPreferredConfig=Bitmap.Config.RGB_565;
            Bitmap bm=BitmapFactory.decodeFile(path,opts);if(bm!=null&&thumbCache.size()<40)thumbCache.put(path,bm);return bm;
        }catch(Throwable ignored){return null;}
    }

    private LinearLayout findAnalysisPage(View view) {
        if (view instanceof LinearLayout) {
            LinearLayout g=(LinearLayout)view;
            if(directButtonIndex(g,"回首頁")>=0 && containsDirectOrNestedText(g,"AI 分析進度")) return g;
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){LinearLayout x=findAnalysisPage(g.getChildAt(i));if(x!=null)return x;}}
        return null;
    }

    private LinearLayout findContainerWithDirectText(View view,String wanted){
        if(view instanceof LinearLayout){LinearLayout g=(LinearLayout)view;if(directTextIndex(g,wanted)>=0)return g;}
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){LinearLayout x=findContainerWithDirectText(g.getChildAt(i),wanted);if(x!=null)return x;}}
        return null;
    }

    private int directTextIndex(LinearLayout g,String wanted){for(int i=0;i<g.getChildCount();i++){View c=g.getChildAt(i);if(c instanceof TextView){String t=String.valueOf(((TextView)c).getText());if(t.equals(wanted)||t.contains(wanted))return i;}}return -1;}
    private int directButtonIndex(LinearLayout g,String wanted){for(int i=0;i<g.getChildCount();i++){View c=g.getChildAt(i);if(c instanceof Button&&String.valueOf(((Button)c).getText()).contains(wanted))return i;}return -1;}
    private boolean containsDirectOrNestedText(View v,String wanted){if(v instanceof TextView&&String.valueOf(((TextView)v).getText()).contains(wanted))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(containsDirectOrNestedText(g.getChildAt(i),wanted))return true;}return false;}

    private void rewriteOldVersionLabel(View view){
        if(view instanceof TextView){TextView t=(TextView)view;String s=String.valueOf(t.getText());if(s.startsWith("版本 2.0.12"))t.setText("版本 2.0.17｜PP-OCRv6 Small ONNX｜AI 排隊｜完成結果直達｜辨識資料庫設定");}
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)rewriteOldVersionLabel(g.getChildAt(i));}
    }

    private void launchDocumentScanner() {
        AiModelProvider provider = AiModelRegistry.active(this);
        if (!provider.isReady(this)) {
            Toast.makeText(this, "請先完成 MiniCPM-V 4.6 模型下載與驗證", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, GemmaSetupActivity.class));
            return;
        }
        PlayServicesModuleManager.prefetch(this);
        if (scanner == null) { fallbackGallery("Google 文件掃描器尚未就緒，改用相簿匯入"); return; }
        scanner.getStartScanIntent(this)
                .addOnSuccessListener(sender -> {
                    try { startIntentSenderForResult(sender, REQ_DOCUMENT_SCAN, null, 0, 0, 0); }
                    catch (IntentSender.SendIntentException e) { fallbackGallery("文件掃描器啟動失敗，改用相簿匯入"); }
                })
                .addOnFailureListener(e -> fallbackGallery("Google 文件掃描模組尚未完成下載，先改用相簿；有網路時會自動補齊"));
    }

    private void fallbackGallery(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_FALLBACK_GALLERY);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_DOCUMENT_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    GmsDocumentScanningResult scan = GmsDocumentScanningResult.fromActivityResultIntent(data);
                    List<GmsDocumentScanningResult.Page> pages = scan == null ? null : scan.getPages();
                    if (pages == null || pages.isEmpty()) { Toast.makeText(this, "沒有取得掃描頁面", Toast.LENGTH_LONG).show(); return; }
                    ArrayList<String> paths = new ArrayList<>();
                    for (GmsDocumentScanningResult.Page page : pages) if (page != null && page.getImageUri() != null) paths.add(copyToPurchaseImages(page.getImageUri()));
                    startAnalysis(paths);
                } catch (Throwable t) { Toast.makeText(this, "掃描結果處理失敗：" + safe(t), Toast.LENGTH_LONG).show(); }
            }
            return;
        }
        if (requestCode == REQ_FALLBACK_GALLERY) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    ArrayList<Uri> uris=new ArrayList<>(); ClipData clip=data.getClipData();
                    if(clip!=null)for(int i=0;i<clip.getItemCount();i++)uris.add(clip.getItemAt(i).getUri()); else if(data.getData()!=null)uris.add(data.getData());
                    ArrayList<String> paths=new ArrayList<>(); for(Uri uri:uris)paths.add(copyToPurchaseImages(uri)); startAnalysis(paths);
                } catch(Throwable t){Toast.makeText(this,"相簿匯入失敗："+safe(t),Toast.LENGTH_LONG).show();}
            }
            return;
        }
        super.onActivityResult(requestCode,resultCode,data);
    }

    private String copyToPurchaseImages(Uri uri) throws Exception {
        File dir=new File(getFilesDir(),"purchase_images"); if(!dir.exists()&&!dir.mkdirs())throw new Exception("無法建立採購單圖片資料夾");
        File out=new File(dir,UUID.randomUUID()+".jpg");
        try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream fos=new FileOutputStream(out)){
            if(in==null)throw new Exception("無法讀取掃描圖片");byte[] buf=new byte[32768];int n;while((n=in.read(buf))>0)fos.write(buf,0,n);
        }
        return out.getAbsolutePath();
    }

    private void startAnalysis(ArrayList<String> paths) {
        if(paths==null||paths.isEmpty()){Toast.makeText(this,"沒有可分析的採購單圖片",Toast.LENGTH_LONG).show();return;}
        boolean wasRunning=AiJobStore.get(this).running();
        AiAnalysisService.start(this,paths,-1);
        Toast.makeText(this,wasRunning?"已加入 "+paths.size()+" 張到 AI 排隊，可繼續新增或調整等待順序":"已加入 "+paths.size()+" 張並開始 AI 分析",Toast.LENGTH_LONG).show();
        queueStructureFingerprint="";
        openAnalysisScreen();
    }

    private void openAnalysisScreen() {
        Intent i=new Intent(this,EnhancedAiMainActivity.class);i.putExtra("screen","analysis");i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);
    }

    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setLineSpacing(0,1.06f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(2),dp(3),dp(2),dp(3));return v;}
    private Button actionButton(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(10),dp(9),dp(10),dp(9));return b;}
    private Button smallButton(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(11);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(7),dp(5),dp(7),dp(5));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(6));return g;}
    private LinearLayout.LayoutParams fullMargins(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(2),dp(top),dp(2),dp(bottom));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private static String safe(Throwable t){if(t==null)return"未知錯誤";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
}
