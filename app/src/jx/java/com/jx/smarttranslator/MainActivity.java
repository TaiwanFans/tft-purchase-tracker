package com.jx.smarttranslator;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends Activity {
    public static final String PREFS = "jx_prefs";
    public static final String KEY_AUTO = "auto_translate";
    public static final String KEY_TARGET = "target_language";
    public static final String KEY_MODEL = "model";
    public static final String KEY_GLOSSARY = "glossary";
    private static final String[] LANGUAGES = {
            "繁體中文", "English", "日本語", "한국어", "Tiếng Việt",
            "ไทย", "Filipino / Tagalog", "Bahasa Indonesia"
    };

    private SharedPreferences prefs;
    private Switch autoSwitch;
    private Spinner targetSpinner;
    private EditText modelEdit;
    private EditText apiKeyEdit;
    private EditText glossaryEdit;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("JX 智慧翻譯", 28, true);
        root.addView(title);

        TextView subtitle = text(
                "照常使用 Google Chrome。JX 在背景辨識目前可見的外文網頁文字，翻譯後直接覆蓋在原文位置。",
                15, false);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        statusText = text("", 15, true);
        statusText.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(statusText, fullWidth());

        autoSwitch = new Switch(this);
        autoSwitch.setText("Chrome 自動翻譯");
        autoSwitch.setTextSize(17);
        autoSwitch.setChecked(prefs.getBoolean(KEY_AUTO, true));
        autoSwitch.setPadding(0, dp(18), 0, dp(10));
        root.addView(autoSwitch, fullWidth());

        root.addView(label("翻譯成"));
        targetSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, LANGUAGES);
        targetSpinner.setAdapter(adapter);
        String currentTarget = prefs.getString(KEY_TARGET, "繁體中文");
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(currentTarget)) targetSpinner.setSelection(i);
        }
        root.addView(targetSpinner, fullWidth());

        root.addView(label("OpenAI 模型"));
        modelEdit = edit(false, 1);
        modelEdit.setText(prefs.getString(KEY_MODEL, "gpt-6-luna"));
        modelEdit.setHint("例如 gpt-6-luna");
        root.addView(modelEdit, fullWidth());

        root.addView(label("OpenAI API Key"));
        apiKeyEdit = edit(false, 1);
        apiKeyEdit.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedKey = SecretStore.load(this);
        if (!savedKey.isEmpty()) apiKeyEdit.setText(savedKey);
        apiKeyEdit.setHint("sk-...");
        root.addView(apiKeyEdit, fullWidth());

        root.addView(label("專有名詞詞庫"));
        glossaryEdit = edit(true, 6);
        glossaryEdit.setHint(
                "一行一組，例如：\nAir Curtain => 空氣門\nJX Smart Translator => JX 智慧翻譯");
        glossaryEdit.setText(prefs.getString(KEY_GLOSSARY, ""));
        root.addView(glossaryEdit, fullWidth());

        Button save = button("儲存設定");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save, buttonParams());

        Button accessibility = button("開啟 Android 無障礙設定");
        accessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonParams());

        TextView note = text(
                "第一次只需要授權一次。之後照常開 Chrome 即可。服務預設只監看 Chrome，並略過密碼欄、輸入框、網址與純數字。往下滑時，新出現的內容會自動補翻。",
                14, false);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        return scroll;
    }

    private void saveSettings() {
        String model = modelEdit.getText().toString().trim();
        String apiKey = apiKeyEdit.getText().toString().trim();
        String glossary = glossaryEdit.getText().toString();
        String target = (String) targetSpinner.getSelectedItem();

        prefs.edit()
                .putBoolean(KEY_AUTO, autoSwitch.isChecked())
                .putString(KEY_TARGET, target)
                .putString(KEY_MODEL, model.isEmpty() ? "gpt-6-luna" : model)
                .putString(KEY_GLOSSARY, glossary)
                .apply();

        if (!apiKey.isEmpty()) {
            try {
                SecretStore.save(this, apiKey);
            } catch (Exception e) {
                Toast.makeText(
                        this,
                        "API Key 儲存失敗：" + e.getMessage(),
                        Toast.LENGTH_LONG).show();
                return;
            }
        }
        Toast.makeText(this, "設定已儲存", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void refreshStatus() {
        if (statusText == null) return;
        boolean enabled = isAccessibilityEnabled();
        boolean auto = prefs.getBoolean(KEY_AUTO, true);
        if (enabled && auto) {
            statusText.setText(
                    "狀態：已啟用。現在可以直接使用 Chrome，自動翻譯會在背景運作。");
            statusText.setBackgroundColor(0xFFE6F4EA);
        } else if (!enabled) {
            statusText.setText(
                    "狀態：尚未授權。請點下方按鈕，在 Android 無障礙設定中啟用「JX 智慧翻譯」。");
            statusText.setBackgroundColor(0xFFFFF4E5);
        } else {
            statusText.setText("狀態：已授權，但 Chrome 自動翻譯目前關閉。");
            statusText.setBackgroundColor(0xFFF1F3F4);
        }
    }

    private boolean isAccessibilityEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        List<AccessibilityServiceInfo> list =
                manager.getEnabledAccessibilityServiceList(
                        AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        String expected = getPackageName() + "/.JxTranslationService";
        for (AccessibilityServiceInfo info : list) {
            if (info.getId() != null &&
                    (info.getId().equals(expected) ||
                     info.getId().contains("JxTranslationService"))) {
                return true;
            }
        }
        return false;
    }

    private TextView label(String value) {
        TextView t = text(value, 14, true);
        t.setPadding(0, dp(16), 0, dp(6));
        return t;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(32, 33, 36));
        if (bold) {
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        }
        return t;
    }

    private EditText edit(boolean multiLine, int minLines) {
        EditText e = new EditText(this);
        e.setTextSize(16);
        e.setMinLines(minLines);
        e.setGravity(multiLine ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackgroundColor(0xFFF8F9FA);
        if (multiLine) {
            e.setInputType(
                    InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        }
        return e;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextSize(16);
        return b;
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = fullWidth();
        p.topMargin = dp(14);
        return p;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
