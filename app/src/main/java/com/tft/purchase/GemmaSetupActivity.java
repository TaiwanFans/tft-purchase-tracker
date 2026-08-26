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

public class GemmaSetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button download;
    private Button enter;
    private boolean launchedMain = false;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(refreshLoop);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshLoop);
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(30), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(245, 248, 255));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_app_icon);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(72),dp(72)));
        LinearLayout tt = new LinearLayout(this); tt.setOrientation(LinearLayout.VERTICAL); tt.setPadding(dp(12),0,0,0);
        tt.addView(label("全益採購追蹤", 27, Color.rgb(15,42,92), true));
        tt.addView(label("GEMMA AI 採購單辨識", 15, Color.rgb(37,99,235), true));
        titleRow.addView(tt,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(titleRow);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(16),dp(16),dp(16));
        card.setBackground(box(Color.WHITE, Color.rgb(37,99,235), 2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,-2);
        cp.setMargins(0,dp(20),0,dp(14));
        root.addView(card, cp);

        card.addView(label("AI 模型只需安裝一次", 18, Color.rgb(15,42,92), true));
        card.addView(label("V2.0.7 由 Gemma 直接看採購單圖片，依序讀取表頭、品項表格，再用整張圖片做最後稽核。OCR 不會再直接填寫欄位。", 15, Color.rgb(71,85,105), false));
        card.addView(label("模型約 2.6 GB。下載完成後可離線辨識，建議使用 Wi‑Fi。", 14, Color.rgb(220,38,38), true));

        status = label("檢查 AI 模型中…", 15, Color.rgb(71,85,105), false);
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1,dp(20));
        pp.setMargins(0,dp(8),0,dp(12));
        root.addView(progress, pp);

        download = button("下載 Gemma AI 模型（約 2.6 GB）", Color.rgb(37,99,235));
        download.setOnClickListener(v -> {
            try {
                GemmaModelManager.startDownload(this);
                Toast.makeText(this,"已開始下載 Gemma AI 模型",Toast.LENGTH_LONG).show();
                refresh();
            } catch (Exception e) {
                Toast.makeText(this,"下載啟動失敗："+e.getMessage(),Toast.LENGTH_LONG).show();
            }
        });
        root.addView(download, margins(0,6));

        enter = button("進入 APP（僅查看既有資料）", Color.rgb(71,85,105));
        enter.setOnClickListener(v -> openMain());
        root.addView(enter, margins(8,6));
        root.addView(label("未安裝 Gemma 時仍可進入查看提醒與既有資料，但新增採購單需要 AI 模型。", 13, Color.rgb(100,116,139), false));
        setContentView(root);
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1200); }
    };

    private void refresh() {
        if (GemmaModelManager.isReady(this)) {
            status.setText("Gemma AI 模型已安裝完成 ✓");
            status.setTextColor(Color.rgb(22,163,74));
            progress.setProgress(100);
            download.setText("Gemma AI 已就緒");
            download.setEnabled(false);
            enter.setText("進入 APP（AI 已就緒）");
            if (!launchedMain) { launchedMain = true; handler.postDelayed(this::openMain, 350); }
            return;
        }
        GemmaModelManager.Status s = GemmaModelManager.getStatus(this);
        if (s.state == DownloadManager.STATUS_RUNNING || s.state == DownloadManager.STATUS_PENDING || s.state == DownloadManager.STATUS_PAUSED) {
            int p = s.percent(); progress.setProgress(p);
            status.setText("Gemma AI 模型下載中：" + p + "%  （" + gb(s.downloaded) + " / " + gb(s.total) + " GB）");
            download.setText("下載進行中…"); download.setEnabled(false);
            enter.setText("進入 APP（下載在背景繼續）");
        } else if (s.state == DownloadManager.STATUS_FAILED) {
            status.setText("模型下載失敗，請確認網路與儲存空間後重試。錯誤碼：" + s.reason);
            status.setTextColor(Color.rgb(220,38,38)); progress.setProgress(0);
            download.setText("重新下載 Gemma AI 模型"); download.setEnabled(true);
        } else {
            status.setText("Gemma AI 模型尚未安裝"); progress.setProgress(0); download.setEnabled(true);
        }
    }

    private void openMain() {
        Intent i = new Intent(this, AiMainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private String gb(long bytes) {
        if (bytes <= 0) bytes = GemmaModelManager.EXPECTED_SIZE;
        return String.format(java.util.Locale.TAIWAN,"%.2f", bytes / 1073741824.0);
    }
    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,dp(5),0,dp(5));return v;}
    private Button button(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(12),dp(10),dp(12),dp(10));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(5));return g;}
    private LinearLayout.LayoutParams margins(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
