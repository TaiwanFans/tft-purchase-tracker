package com.tft.purchase;

import android.content.ClipData;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
import java.util.List;
import java.util.UUID;

/**
 * Enhanced layer over the existing procurement UI. Acquisition uses Google document scanning;
 * settings also exposes the local vendor/product recognition dictionary.
 */
public class EnhancedAiMainActivity extends AiMainActivity {
    private static final int REQ_DOCUMENT_SCAN = 901;
    private static final int REQ_FALLBACK_GALLERY = 902;
    private static final String HOOK_TAG = "tft_doc_scan_v215";
    private static final String KNOWLEDGE_TAG = "tft_knowledge_v215";

    private GmsDocumentScanner scanner;

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

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getWindow() != null && getWindow().getDecorView() != null) {
            getWindow().getDecorView().post(() -> {
                View root = getWindow().getDecorView();
                hookRecursive(root);
                injectKnowledgeButton(root);
            });
        }
    }

    private void hookRecursive(View view) {
        if (view == null) return;
        if (view instanceof Button) {
            Button button = (Button) view;
            CharSequence cs = button.getText();
            String text = cs == null ? "" : cs.toString();
            if ((text.contains("選擇採購單照片") || text.contains("AI 分析新採購單") || text.contains("掃描／相簿匯入採購單"))
                    && !HOOK_TAG.equals(button.getTag())) {
                button.setTag(HOOK_TAG);
                if (text.contains("AI 分析新採購單")) button.setText("＋ 掃描／匯入採購單");
                else button.setText("掃描／相簿匯入採購單 → 分區放大高精度 AI");
                button.setOnClickListener(v -> launchDocumentScanner());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) hookRecursive(group.getChildAt(i));
        }
    }

    /** Add one button to the existing Settings page without rebuilding the parent UI. */
    private void injectKnowledgeButton(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        boolean settingsGroup = false;
        for (int i=0;i<group.getChildCount();i++) {
            View c = group.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence cs=((TextView)c).getText();
                if (cs!=null && cs.toString().contains("Google Drive 備份")) { settingsGroup=true; break; }
            }
        }
        if (settingsGroup && group instanceof LinearLayout && group.findViewWithTag(KNOWLEDGE_TAG)==null) {
            Button b = new Button(this);
            b.setTag(KNOWLEDGE_TAG);
            b.setAllCaps(false);
            b.setText("辨識字典／廠商與常購品項資料庫");
            b.setTextSize(15);
            b.setTextColor(Color.WHITE);
            b.setBackgroundColor(Color.rgb(37,99,235));
            b.setOnClickListener(v -> startActivity(new Intent(this, KnowledgeBaseActivity.class)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            int m=(int)(8*getResources().getDisplayMetrics().density+0.5f);
            lp.setMargins(m,m,m,m);
            ((LinearLayout)group).addView(b, lp);
        }
        for (int i=0;i<group.getChildCount();i++) injectKnowledgeButton(group.getChildAt(i));
    }

    private void launchDocumentScanner() {
        if (AiJobStore.get(this).running()) { openAnalysisScreen(); return; }
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
        AiAnalysisService.start(this,paths,-1);openAnalysisScreen();
    }

    private void openAnalysisScreen() {
        Intent i=new Intent(this,EnhancedAiMainActivity.class);i.putExtra("screen","analysis");i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(i);
    }

    private static String safe(Throwable t){if(t==null)return"未知錯誤";String m=t.getMessage();return m==null||m.trim().isEmpty()?t.getClass().getSimpleName():m;}
}
