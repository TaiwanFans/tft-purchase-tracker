package com.tft.purchase;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Launcher/setup UI for v2.1.0 Gemini network mode. */
public class GemmaSetupActivity extends Activity {
    private TextView status;
    private Button enter;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AiModelRegistry.setActive(this, AiModelRegistry.GEMINI_FIREBASE);
        try { PlayServicesModuleManager.prefetch(this); } catch (Throwable ignored) {}
        render();
        refresh();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(16));
        root.setBackgroundColor(Color.rgb(245,248,255));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_app_icon);
        titleRow.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout tt = new LinearLayout(this);
        tt.setOrientation(LinearLayout.VERTICAL);
        tt.setPadding(dp(12),0,0,0);
        tt.addView(label("採購單追蹤",24,Color.rgb(15,42,92),true));
        tt.addView(label("Gemini 雲端視覺 + 固定 8 欄 OCR 驗證",13,Color.rgb(37,99,235),true));
        titleRow.addView(tt,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        root.addView(titleRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0,dp(8),0,dp(8));
        scroll.addView(content,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16),dp(14),dp(16),dp(14));
        card.setBackground(box(Color.WHITE,Color.rgb(37,99,235),2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1,-2);
        cp.setMargins(0,dp(10),0,dp(12));
        content.addView(card,cp);
        card.addView(label("V2.1.0｜Gemini 網路辨識版",17,Color.rgb(15,42,92),true));
        card.addView(label("已取消 MiniCPM 1.6 GB 本機模型。採購單會先在手機完成方向／透視／去陰影、PP-OCRv6 Small + ML Kit 與固定 8 欄格線定位，再把校正後完整頁面交給 Firebase AI Logic 的 Gemini 3.5 Flash 讀取。",14,Color.rgb(71,85,105),false));
        card.addView(label("Gemini 回傳固定 JSON 後，APP 仍會再用供應商角色、採購單號、8 欄位置、數量×單價=小計、交貨日期與『以下空白』等規則驗證，避免 AI 自由猜測。",14,Color.rgb(22,101,52),true));
        card.addView(label("AI 分析仍支援排隊、背景處理、結果直達與失敗重試；資料庫、照片、提醒、知識庫與 Google Drive 備份都保留。",14,Color.rgb(22,101,52),true));
        card.addView(label("此版本分析需要網路。PP-OCRv6 Small 仍內建，所以即使 Gemini 暫時失敗，原圖與 OCR 證據仍會保留，之後可重新分析。",13,Color.rgb(100,116,139),false));

        status = label("檢查 Firebase 設定中…",14,Color.rgb(71,85,105),false);
        content.addView(status);

        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f);
        scrollParams.setMargins(0,dp(4),0,dp(8));
        root.addView(scroll,scrollParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0,dp(4),0,0);
        enter = button("進入採購單追蹤",Color.rgb(37,99,235));
        enter.setOnClickListener(v -> openMain());
        actions.addView(enter,margins(0,4));
        Button knowledge = button("辨識知識庫／廠商資料庫",Color.rgb(8,145,178));
        knowledge.setOnClickListener(v -> startActivity(new Intent(this,KnowledgeBaseActivity.class)));
        actions.addView(knowledge,margins(3,0));
        root.addView(actions);

        setContentView(root);
    }

    private void refresh() {
        AiModelProvider provider = AiModelRegistry.active(this);
        if (provider.isReady(this)) {
            status.setText("Firebase 專案已連結 ✓\nGemini 3.5 Flash（Firebase AI Logic）已設為主 AI ✓\nPP-OCRv6 Small ONNX 已內建 ✓\nOpenCV 固定 8 欄解析已啟用 ✓\n" + PlayServicesModuleManager.status(this));
            status.setTextColor(Color.rgb(22,163,74));
            enter.setEnabled(true);
        } else {
            status.setText("Firebase 尚未初始化。請確認 google-services.json 與 Firebase 專案設定。\n" + PlayServicesModuleManager.status(this));
            status.setTextColor(Color.rgb(220,38,38));
            enter.setEnabled(true); // Allow database access even if cloud AI is temporarily unavailable.
        }
    }

    private void openMain() {
        Intent i = new Intent(this, EnhancedAiMainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,dp(5),0,dp(5));return v;}
    private Button button(String text,int color){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),1));b.setPadding(dp(12),dp(9),dp(12),dp(9));b.setMinHeight(dp(48));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(10));return g;}
    private LinearLayout.LayoutParams margins(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);p.setMargins(0,dp(top),0,dp(bottom));return p;}
    private int dp(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
