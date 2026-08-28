package com.tft.purchase;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/** Launcher/model setup UI. */
public class GemmaSetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button download, enter;
    private boolean validating = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AiModelRegistry.setActive(this, AiModelRegistry.MINICPM_V46);
        try { PlayServicesModuleManager.prefetch(this); } catch (Throwable ignored) {}
        render(); refresh();
    }

    @Override protected void onResume(){super.onResume();handler.post(refreshLoop);}
    @Override protected void onPause(){handler.removeCallbacks(refreshLoop);super.onPause();}

    private void render() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22),dp(26),dp(22),dp(28)); root.setBackgroundColor(Color.rgb(245,248,255));
        LinearLayout titleRow=new LinearLayout(this);titleRow.setOrientation(LinearLayout.HORIZONTAL);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_app_icon);titleRow.addView(icon,new LinearLayout.LayoutParams(dp(72),dp(72)));
        LinearLayout tt=new LinearLayout(this);tt.setOrientation(LinearLayout.VERTICAL);tt.setPadding(dp(12),0,0,0);
        tt.addView(label("全益採購追蹤",27,Color.rgb(15,42,92),true));
        tt.addView(label("PP-OCRv6 Small ONNX 分區放大 + MiniCPM｜離線辨識",14,Color.rgb(37,99,235),true));
        titleRow.addView(tt,new LinearLayout.LayoutParams(0,-2,1));root.addView(titleRow);

        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(box(Color.WHITE,Color.rgb(37,99,235),2));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(18),0,dp(12));root.addView(card,cp);
        card.addView(label("V2.0.17｜AI 排隊＋高解析分區＋視覺救援",18,Color.rgb(15,42,92),true));
        card.addView(label("採購單先做方向／紙張／透視校正、去陰影與去噪，再切 H1/H2/R1/R2/R3/R4；每區放大後由 PP-OCRv6 Small ONNX Runtime + Google ML Kit 各自掃描，再把座標拼回完整採購單。",15,Color.rgb(71,85,105),false));
        card.addView(label("MiniCPM 不再只等 OCR 報衝突才看圖。它會固定核對 H1/H2/R1-R4 高解析區，再加上 OCR 衝突／低信心局部圖，用來救援漏字或自信誤讀；看不清楚仍留空，不亂猜。",14,Color.rgb(22,101,52),true));
        card.addView(label("AI 分析可排隊：分析中仍能繼續新增照片、查看等待順序與進度，等待中的項目可上移／下移；完成後可從排隊列表直接前往該張結果。",14,Color.rgb(22,101,52),true));
        card.addView(label("辨識知識庫可從設定隨時新增、修改、刪除廠商／地址／關鍵字／常購品項；我方全益／台灣電扇名稱仍固定禁止當成供應廠商。",14,Color.rgb(22,101,52),true));
        card.addView(label("MiniCPM-V 4.6 約 1.6 GB。PP-OCRv6 Small ONNX 已包在 APK；Google 文件掃描與中文 OCR 第一次有網路時由 Play services 準備，之後可離線使用。",13,Color.rgb(100,116,139),false));

        status=label("檢查 MiniCPM-V 4.6 模型中…",15,Color.rgb(71,85,105),false);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(20));pp.setMargins(0,dp(8),0,dp(10));root.addView(progress,pp);

        download=button("下載 MiniCPM-V 4.6（約 1.6 GB）",Color.rgb(37,99,235));
        download.setOnClickListener(v->{try{PlayServicesModuleManager.prefetch(this);MiniCpmV46ModelManager.startDownload(this);Toast.makeText(this,"已開始下載本機 AI；Google 離線掃描/OCR 模組也會準備",Toast.LENGTH_LONG).show();refresh();}catch(Exception e){Toast.makeText(this,"下載啟動失敗："+e.getMessage(),Toast.LENGTH_LONG).show();}});root.addView(download,margins(0,5));

        Button knowledge=button("編輯辨識知識庫／廠商資料庫",Color.rgb(8,145,178));knowledge.setOnClickListener(v->startActivity(new Intent(this,KnowledgeBaseActivity.class)));root.addView(knowledge,margins(5,5));
        enter=button("進入 APP（僅查看既有資料）",Color.rgb(71,85,105));enter.setOnClickListener(v->openMain());root.addView(enter,margins(5,5));
        root.addView(label("第一次安裝建議保持 Wi-Fi。下載 100% 後還會檢查實際檔案大小、官方 MD5，並實際載入 native MiniCPM + mmproj；三關都通過才會顯示 AI 已就緒。",13,Color.rgb(100,116,139),false));
        setContentView(root);
    }

    private final Runnable refreshLoop=new Runnable(){@Override public void run(){refresh();handler.postDelayed(this,1200);}};

    private void refresh() {
        if(MiniCpmV46ModelManager.isReady(this)){
            status.setText("MiniCPM-V 4.6 已就緒 ✓\nMD5 + native 模型載入測試 ✓\nPP-OCRv6 Small ONNX 已內建 ✓\n"+PlayServicesModuleManager.status(this));status.setTextColor(Color.rgb(22,163,74));progress.setProgress(100);download.setText("本機 AI 模型已就緒");download.setEnabled(false);enter.setText("進入 APP（分區離線辨識已就緒）");enter.setEnabled(true);return;
        }
        MiniCpmV46ModelManager.Status s=MiniCpmV46ModelManager.getStatus(this);
        if(validating){status.setText("模型下載完成，正在核對官方 MD5 並執行 native 載入測試…\n第一次載入可能需要較多記憶體。\n"+PlayServicesModuleManager.status(this));status.setTextColor(Color.rgb(202,138,4));progress.setProgress(100);download.setEnabled(false);return;}
        if(s.state==DownloadManager.STATUS_RUNNING||s.state==DownloadManager.STATUS_PENDING||s.state==DownloadManager.STATUS_PAUSED){int p=s.percent();progress.setProgress(p);status.setText("MiniCPM-V 4.6 下載中："+p+"%\n已下載 "+gb(s.downloaded)+" / "+gb(s.total)+" GB\n"+PlayServicesModuleManager.status(this));download.setText("下載進行中…");download.setEnabled(false);enter.setText("進入 APP（下載背景繼續）");return;}
        if(s.state==DownloadManager.STATUS_SUCCESSFUL&&s.needsValidation){status.setText("下載 100% 完成，準備 MD5 + native 載入驗證…");progress.setProgress(100);download.setEnabled(false);startValidation();return;}
        if((s.validationError!=null&&!s.validationError.isEmpty())||s.state==DownloadManager.STATUS_FAILED){String err=s.validationError!=null&&!s.validationError.isEmpty()?s.validationError:"Android 下載失敗，錯誤碼："+s.reason;status.setText("MiniCPM 模型目前不可使用：\n"+err+"\n請重新下載。\n"+PlayServicesModuleManager.status(this));status.setTextColor(Color.rgb(220,38,38));progress.setProgress(0);download.setText("重新下載 MiniCPM-V 4.6");download.setEnabled(true);return;}
        status.setText("MiniCPM-V 4.6 尚未安裝，需要約 1.6 GB。\nPP-OCRv6 Small ONNX 已內建。\n"+PlayServicesModuleManager.status(this));status.setTextColor(Color.rgb(71,85,105));progress.setProgress(0);download.setText("下載 MiniCPM-V 4.6（約 1.6 GB）");download.setEnabled(true);
    }

    private void startValidation(){if(validating)return;validating=true;new Thread(()->{MiniCpmV46ModelManager.ValidationResult r=MiniCpmV46ModelManager.validateDownloadedModel(this);runOnUiThread(()->{validating=false;Toast.makeText(this,r.message,Toast.LENGTH_LONG).show();refresh();});},"minicpm-md5-native-verify").start();}
    private void openMain(){Intent i=new Intent(this,EnhancedAiMainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);startActivity(i);finish();}
    private String gb(long bytes){if(bytes<0)bytes=0;return String.format(java.util.Locale.TAIWAN,"%.2f",bytes/1_000_000_000.0);}
    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,dp(5),0,dp(5));return v;}
    private Button button(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(12),dp(10),dp(12),dp(10));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(5));return g;}
    private LinearLayout.LayoutParams margins(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
