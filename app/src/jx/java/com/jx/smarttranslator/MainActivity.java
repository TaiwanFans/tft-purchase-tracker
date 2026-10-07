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
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public class MainActivity extends Activity {
    public static final String PREFS = "jx_prefs";
    public static final String KEY_MODE = "translate_mode";
    public static final String KEY_AUTO = "auto_translate";
    public static final String KEY_TARGET = "target_language";
    public static final String KEY_MODEL = "model";
    public static final String KEY_GLOSSARY = "glossary";

    public static final String MODE_ASK = "ask";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_OFF = "off";

    private static final String[] LANGUAGES = {
            "繁體中文", "English", "日本語", "한국어", "Tiếng Việt",
            "ไทย", "Filipino / Tagalog", "Bahasa Indonesia"
    };

    private SharedPreferences prefs;
    private RadioGroup modeGroup;
    private RadioButton askRadio;
    private RadioButton autoRadio;
    private RadioButton offRadio;
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
                "照常使用 Google Chrome。遇到外文頁面時，JX 會先像 Google 翻譯一樣詢問你要不要翻譯。",
                15, false);
        subtitle.setPadding(0, dp(6), 0, dp(18));
        root.addView(subtitle);

        statusText = text("", 15, true);
        statusText.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(statusText, fullWidth());

        root.addView(label("Chrome 翻譯模式"));

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);

        askRadio = new RadioButton(this);
        askRadio.setText("每次詢問（建議）");
        askRadio.setTextSize(16);
        modeGroup.addView(askRadio);

        autoRadio = new RadioButton(this);
        autoRadio.setText("自動翻譯外文頁面");
        autoRadio.setTextSize(16);
        modeGroup.addView(autoRadio);

        offRadio = new RadioButton(this);
        offRadio.setText("關閉 Chrome 翻譯");
        offRadio.setTextSize(16);
        modeGroup.addView(offRadio);

        String mode = getSavedMode();
        if (MODE_AUTO.equals(mode)) {
            autoRadio.setChecked(true);
        } else if (MODE_OFF.equals(mode)) {
            offRadio.setChecked(true);
        } else {
            askRadio.setChecked(true);
        }
        root.addView(modeGroup, fullWidth());

        TextView modeHint = text(
                "「每次詢問」模式下，Chrome 偵測到外文內容後會在畫面底部出現：翻譯此頁｜自動翻譯｜×。",
                13, false);
        modeHint.setPadding(0, dp(6), 0, dp(8));
        root.addView(modeHint);

        root.addView(label("翻譯成"));
        targetSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, LANGUAGES);
        targetSpinner.setAdapter(adapter);
        String currentTarget = prefs.getString(KEY_TARGET, "繁體中文");
        for (int i = 0; i < LANGUAGES.length; i++) {
            if (LANGUAGES[i].equals(currentTarget)) {
                targetSpinner.setSelection(i);
                break;
            }
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
                "第一次安裝只需要授權一次。服務只監看 Google Chrome，並略過密碼欄、輸入框、網址列與純數字內容。",
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

        String mode = MODE_ASK;
        if (autoRadio.isChecked()) mode = MODE_AUTO;
        if (offRadio.isChecked()) mode = MODE_OFF;

        prefs.edit()
                .putString(KEY_MODE, mode)
                .putBoolean(KEY_AUTO, MODE_AUTO.equals(mode))
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

    private String getSavedMode() {
        String mode = prefs.getString(KEY_MODE, null);
        if (mode != null) return mode;

        if (prefs.contains(KEY_AUTO)) {
            return prefs.getBoolean(KEY_AUTO, false) ? MODE_AUTO : MODE_ASK;
        }
        return MODE_ASK;
    }

    private void refreshStatus() {
        if (statusText == null) return;

        boolean enabled = isAccessibilityEnabled();
        String mode = getSavedMode();

        if (!enabled) {
            statusText.setText(
                    "狀態：尚未授權。請在 Android 無障礙設定中啟用「JX 智慧翻譯」。");
            statusText.setBackgroundColor(0xFFFFF4E5);
            return;
        }

        if (MODE_AUTO.equals(mode)) {
            statusText.setText(
                    "狀態：已啟用。Chrome 遇到外文頁面會直接自動翻譯。");
            statusText.setBackgroundColor(0xFFE6F4EA);
        } else if (MODE_OFF.equals(mode)) {
            statusText.setText(
                    "狀態：已授權，但 Chrome 翻譯目前關閉。");
            statusText.setBackgroundColor(0xFFF1F3F4);
        } else {
            statusText.setText(
                    "狀態：每次詢問。Chrome 遇到外文頁面時會先顯示翻譯按鈕。");
            statusText.setBackgroundColor(0xFFE8F0FE);
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
