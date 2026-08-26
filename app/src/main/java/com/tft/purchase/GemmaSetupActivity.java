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

/** Gemma 4 E4B setup with real DownloadManager completion + SHA-256 validation. */
public class GemmaSetupActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button download;
    private Button enter;
    private boolean validating = false;

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
        handler.removeCallbacks(refreshLoop);
        super.onPause();
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
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(12), 0, 0, 0);
        tt.addView(label("全益採購追蹤", 27, Color.rgb(15,42,92), true));
        tt.addView(label("GEMMA 4 E4B｜本機免費辨識", 15, Color.rgb(37,99,235), true));
        titleRow.addView(tt, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(titleRow);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(box(Color.WHITE, Color.rgb(37,99,235), 2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.setMargins(0, dp(20), 0, dp(14));
        root.addView(card, cp);

        card.addView(label("V2.0.10 修正模型下載誤判", 18, Color.rgb(15,42,92), true));
        card.addView(label("模型真正大小為 3,659,530,240 bytes（約 3.66 GB）。APP 現在只有在 Android 系統確認下載完成、檔案大小正確、SHA-256 完整性驗證通過後，才會顯示 AI 已就緒。", 15, Color.rgb(71,85,105), false));
        card.addView(label("如果幾秒內就顯示完成，這版會把它判定為不完整並要求重新下載，不會再拿壞掉的模型做辨識。", 14, Color.rgb(220,38,38), true));

        status = label("檢查 Gemma 4 E4B 模型中…", 15, Color.rgb(71,85,105), false);
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(20));
        pp.setMargins(0, dp(8), 0, dp(12));
        root.addView(progress, pp);

        download = button("下載 Gemma 4 E4B（約 3.66 GB）", Color.rgb(37,99,235));
        download.setOnClickListener(v -> {
            try {
                GemmaModelManager.startDownload(this);
                Toast.makeText(this, "已開始重新下載 Gemma 4 E4B", Toast.LENGTH_LONG).show();
                refresh();
            } catch (Exception e) {
                Toast.makeText(this, "下載啟動失敗：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        root.addView(download, margins(0,6));

        enter = button("進入 APP（僅查看既有資料）", Color.rgb(71,85,105));
        enter.setOnClickListener(v -> openMain());
        root.addView(enter, margins(8,6));
        root.addView(label("下載期間可以進入 APP 查看既有採購資料與提醒，但新增／重新 AI 分析必須等模型驗證通過。", 13, Color.rgb(100,116,139), false));
        setContentView(root);
    }

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1200);
        }
    };

    private void refresh() {
        if (GemmaModelManager.isReady(this)) {
            status.setText("Gemma 4 E4B 已完整下載並驗證通過 ✓\n模型大小：3.66 GB｜SHA-256：通過");
            status.setTextColor(Color.rgb(22,163,74));
            progress.setProgress(100);
            download.setText("Gemma 4 E4B 已就緒");
            download.setEnabled(false);
            enter.setText("進入 APP（AI 已就緒）");
            enter.setEnabled(true);
            return;
        }

        GemmaModelManager.Status s = GemmaModelManager.getStatus(this);
        if (validating) {
            status.setText("下載已完成，正在驗證 3.66 GB 模型完整性…\n這一步會完整讀取模型並核對官方 SHA-256，請稍候。");
            status.setTextColor(Color.rgb(202,138,4));
            progress.setProgress(100);
            download.setEnabled(false);
            enter.setText("進入 APP（模型仍在驗證）");
            return;
        }

        if (s.state == DownloadManager.STATUS_RUNNING || s.state == DownloadManager.STATUS_PENDING || s.state == DownloadManager.STATUS_PAUSED) {
            int p = s.percent();
            progress.setProgress(p);
            long total = s.total > 0 ? s.total : GemmaModelManager.EXPECTED_SIZE;
            status.setText("Gemma 4 E4B 真正下載中：" + p + "%\n已下載 " + gb(s.downloaded) + " / " + gb(total) + " GB\n注意：目的檔可能預先顯示很大，但只有系統下載進度才算數。");
            status.setTextColor(Color.rgb(71,85,105));
            download.setText("下載進行中…");
            download.setEnabled(false);
            enter.setText("進入 APP（下載在背景繼續）");
            return;
        }

        if (s.state == DownloadManager.STATUS_SUCCESSFUL && s.needsValidation) {
            status.setText("下載 100% 完成，準備驗證模型完整性…");
            progress.setProgress(100);
            download.setEnabled(false);
            startValidation();
            return;
        }

        if ((s.validationError != null && !s.validationError.isEmpty()) || s.state == DownloadManager.STATUS_FAILED) {
            String err = s.validationError != null && !s.validationError.isEmpty()
                    ? s.validationError
                    : "Android 下載失敗，錯誤碼：" + s.reason;
            status.setText("模型不可使用：\n" + err + "\n請按下方重新下載。");
            status.setTextColor(Color.rgb(220,38,38));
            progress.setProgress(0);
            download.setText("刪除錯誤檔並重新下載 Gemma 4 E4B");
            download.setEnabled(true);
            enter.setText("進入 APP（AI 尚不可用）");
            return;
        }

        status.setText("Gemma 4 E4B 尚未完整安裝\n需要真正下載約 3.66 GB，不可能在一般網路下數秒完成。");
        status.setTextColor(Color.rgb(71,85,105));
        progress.setProgress(0);
        download.setText("下載 Gemma 4 E4B（約 3.66 GB）");
        download.setEnabled(true);
        enter.setText("進入 APP（僅查看既有資料）");
    }

    private void startValidation() {
        if (validating) return;
        validating = true;
        new Thread(() -> {
            GemmaModelManager.ValidationResult r = GemmaModelManager.validateDownloadedModel(this);
            runOnUiThread(() -> {
                validating = false;
                Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
                refresh();
            });
        }, "gemma-sha256-verify").start();
    }

    private void openMain() {
        Intent i = new Intent(this, AiMainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private String gb(long bytes) {
        if (bytes < 0) bytes = 0;
        return String.format(java.util.Locale.TAIWAN, "%.2f", bytes / 1_000_000_000.0);
    }

    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,dp(5),0,dp(5));return v;}
    private Button button(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(12),dp(10),dp(12),dp(10));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(5));return g;}
    private LinearLayout.LayoutParams margins(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
