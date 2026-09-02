package com.tft.purchase;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_IMPORT_TXT = 1001;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LineDbHelper db;
    private LinearLayout content;
    private TextView statusText;
    private TextView statsText;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new LineDbHelper(this);
        buildUi();
        refreshDashboard();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db != null) refreshDashboard();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("全益 LINE AI 助手", 26, true);
        root.addView(title);
        TextView sub = text("LINE 通知 → 原始訊息 → 客戶/群組辨識 → AI 案件整理", 14, false);
        sub.setTextColor(Color.DKGRAY);
        root.addView(sub);

        TextView safety = text("僅讀取 Android 通知，不會點擊 LINE 通知、不會自動回覆。通知可能不是完整聊天記錄，可再匯入 LINE TXT 補資料。", 13, false);
        safety.setPadding(0, dp(10), 0, dp(12));
        root.addView(safety);

        statusText = text("", 15, true);
        statusText.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(statusText, matchWrap());

        LinearLayout row1 = buttonRow();
        Button access = button("開啟通知存取");
        access.setOnClickListener(v -> openNotificationAccess());
        row1.addView(access, weight());
        Button importBtn = button("匯入 LINE TXT");
        importBtn.setOnClickListener(v -> chooseTxt());
        row1.addView(importBtn, weight());
        root.addView(row1);

        LinearLayout row2 = buttonRow();
        Button aiSettings = button("AI 設定");
        aiSettings.setOnClickListener(v -> showAiSettings());
        row2.addView(aiSettings, weight());
        Button analyze = button("立即整理");
        analyze.setOnClickListener(v -> runAnalysis());
        row2.addView(analyze, weight());
        root.addView(row2);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.setMargins(0, dp(8), 0, dp(8));
        root.addView(progress, pp);

        statsText = text("", 16, true);
        statsText.setPadding(0, dp(16), 0, dp(10));
        root.addView(statsText);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, matchWrap());

        setContentView(scroll);
    }

    private void refreshDashboard() {
        boolean listener = isNotificationAccessEnabled();
        AiAnalyzer analyzer = new AiAnalyzer(this);
        statusText.setText((listener ? "✅ LINE 通知擷取：已授權" : "⚠️ LINE 通知擷取：尚未授權")
                + "\n" + (analyzer.isAiConfigured() ? "✅ AI：已設定" : "ℹ️ AI：未設定，會先用本機規則整理"));
        statusText.setBackgroundColor(listener ? 0xFFEAF7EE : 0xFFFFF2CC);

        long total = db.messageCount();
        int quote = db.countStatus("NEED_QUOTE");
        int info = db.countStatus("NEED_INFORMATION");
        int reply = db.countStatus("NEED_REPLY");
        int follow = db.countStatus("NEED_FOLLOWUP") + db.countStatus("WAITING_CUSTOMER");
        statsText.setText("訊息事件 " + total + "　｜　待報價 " + quote + "　｜　待補資料 " + info
                + "\n待回覆 " + reply + "　｜　待追蹤 " + follow);

        content.removeAllViews();
        List<LineDbHelper.Summary> summaries = db.listSummaries();
        if (summaries.isEmpty()) {
            TextView empty = text("目前還沒有案件整理。\n\n1. 先開啟『通知存取』\n2. 等 LINE 新訊息進來，或匯入 LINE TXT\n3. 按『立即整理』", 16, false);
            empty.setPadding(dp(8), dp(18), dp(8), dp(18));
            content.addView(empty);
            return;
        }

        for (LineDbHelper.Summary s : summaries) content.addView(caseCard(s));
    }

    private View caseCard(LineDbHelper.Summary s) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cp);

        TextView name = text(("HIGH".equals(s.priority) ? "🔴 " : "") + s.conversation, 19, true);
        card.addView(name);
        card.addView(text("狀態：" + statusZh(s.status) + "　類型：" + safe(s.conversationType), 14, true));
        if (!TextUtils.isEmpty(s.roles)) card.addView(labelValue("角色", s.roles));
        if (!TextUtils.isEmpty(s.summary)) card.addView(labelValue("整理", s.summary));
        if (!TextUtils.isEmpty(s.needs)) card.addView(labelValue("缺資料", s.needs));
        if (!TextUtils.isEmpty(s.nextAction)) card.addView(labelValue("下一步", s.nextAction));
        if (!TextUtils.isEmpty(s.evidence)) card.addView(labelValue("依據", s.evidence));

        LinearLayout actions = buttonRow();
        Button raw = button("看原始訊息");
        raw.setOnClickListener(v -> showRawMessages(s.conversation));
        actions.addView(raw, weight());
        if (!TextUtils.isEmpty(s.replyDraft)) {
            Button copy = button("複製回覆草稿");
            copy.setOnClickListener(v -> copyText(s.replyDraft));
            actions.addView(copy, weight());
        }
        card.addView(actions);

        if (!TextUtils.isEmpty(s.replyDraft)) {
            TextView draft = labelValue("AI 回覆草稿", s.replyDraft);
            draft.setTextIsSelectable(true);
            card.addView(draft);
        }
        return card;
    }

    private void runAnalysis() {
        List<String> conversations = db.listConversations(30);
        if (conversations.isEmpty()) {
            toast("目前沒有 LINE 訊息可整理");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        toast("開始整理 " + conversations.size() + " 個對話/群組");
        executor.execute(() -> {
            AiAnalyzer analyzer = new AiAnalyzer(this);
            int ok = 0;
            String lastError = null;
            for (String conversation : conversations) {
                try {
                    if (analyzer.analyzeConversation(conversation) != null) ok++;
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
            }
            final int count = ok;
            final String err = lastError;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                refreshDashboard();
                if (err == null) toast("整理完成，共 " + count + " 個對話/群組");
                else new AlertDialog.Builder(this)
                        .setTitle("整理完成，但 AI 有錯誤")
                        .setMessage("已完成 " + count + " 個。\n\n最後錯誤：" + err + "\n\n可檢查 AI API URL、金鑰、模型名稱。")
                        .setPositiveButton("知道了", null).show();
            });
        });
    }

    private void showAiSettings() {
        SharedPreferences sp = getSharedPreferences("line_ai_settings", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), 0);

        EditText url = edit("AI API 完整網址，例如 https://.../v1/chat/completions");
        url.setText(sp.getString("api_url", "https://api.openai.com/v1/chat/completions"));
        box.addView(url);
        EditText key = edit("API Key");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setText(sp.getString("api_key", ""));
        box.addView(key);
        EditText model = edit("模型名稱");
        model.setText(sp.getString("model", ""));
        box.addView(model);

        TextView note = text("APK 不內建任何 API Key。你填入的設定只存於這支手機的 App 私有資料。未設定時仍可用本機規則整理。", 12, false);
        note.setPadding(0, dp(8), 0, 0);
        box.addView(note);

        new AlertDialog.Builder(this)
                .setTitle("AI 設定（OpenAI 相容 Chat Completions）")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", (d, w) -> {
                    sp.edit().putString("api_url", url.getText().toString().trim())
                            .putString("api_key", key.getText().toString().trim())
                            .putString("model", model.getText().toString().trim()).apply();
                    refreshDashboard();
                }).show();
    }

    private void showRawMessages(String conversation) {
        List<LineDbHelper.Message> list = db.recentMessages(conversation, 30);
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("M/d HH:mm", Locale.TAIWAN);
        for (int i = list.size() - 1; i >= 0; i--) {
            LineDbHelper.Message m = list.get(i);
            sb.append(sdf.format(new Date(m.timestamp))).append("　")
                    .append(safe(m.sender)).append("\n")
                    .append(safe(m.text)).append("\n")
                    .append("[").append(m.eventId).append("] ")
                    .append(m.source).append(" / ").append(m.completeness).append("\n\n");
        }
        TextView tv = text(sb.length() == 0 ? "沒有訊息" : sb.toString(), 14, false);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(16), 0, dp(16), 0);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(conversation + "｜原始訊息")
                .setView(sv).setPositiveButton("關閉", null).show();
    }

    private void chooseTxt() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/*");
        startActivityForResult(i, REQ_IMPORT_TXT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_TXT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        progress.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                LineTxtImporter.Result r = LineTxtImporter.importUri(this, uri);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    refreshDashboard();
                    new AlertDialog.Builder(this).setTitle("匯入完成")
                            .setMessage("對話/群組：" + r.conversation + "\n匯入訊息：" + r.inserted + "\n略過：" + r.skipped + "\n\n接著按『立即整理』，AI 會用這批歷史訊息協助判斷角色與案件。")
                            .setPositiveButton("好", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    new AlertDialog.Builder(this).setTitle("匯入失敗").setMessage(e.getMessage()).setPositiveButton("關閉", null).show();
                });
            }
        });
    }

    private void openNotificationAccess() {
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
        }
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void copyText(String value) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("LINE 回覆草稿", value));
        toast("已複製回覆草稿");
    }

    private TextView labelValue(String label, String value) {
        TextView tv = text(label + "：" + safe(value), 14, false);
        tv.setPadding(0, dp(5), 0, dp(2));
        return tv;
    }

    private String statusZh(String status) {
        if (status == null) return "未知";
        switch (status) {
            case "NEW": return "新案件";
            case "NEED_INFORMATION": return "待補資料";
            case "NEED_QUOTE": return "待報價";
            case "QUOTED": return "已報價";
            case "WAITING_CUSTOMER": return "等待客戶";
            case "NEED_FOLLOWUP": return "待追蹤";
            case "ORDER_CONFIRMED": return "已確認訂單";
            case "PROCESSING": return "處理中";
            case "COMPLETED": return "已完成";
            case "NEED_REPLY": return "待回覆";
            default: return status;
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(Color.BLACK);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(14);
        return e;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout buttonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(4), 0, dp(4));
        return row;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(3), 0, dp(3), 0);
        return p;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String safe(String s) { return s == null ? "" : s; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
