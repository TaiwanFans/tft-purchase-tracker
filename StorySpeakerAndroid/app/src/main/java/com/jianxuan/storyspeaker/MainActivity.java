package com.jianxuan.storyspeaker;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import android.graphics.drawable.GradientDrawable;

import java.text.Collator;
import java.util.*;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private EditText storyInput;
    private Spinner voiceSpinner;
    private SeekBar rateBar, pitchBar;
    private TextView rateValue, pitchValue, statusText, progressText;
    private Button playBtn, pauseBtn, stopBtn, clearBtn;
    private final List<Voice> voiceList = new ArrayList<>();
    private final List<String> chunks = new ArrayList<>();
    private int chunkIndex = 0;
    private boolean paused = false;
    private boolean userStopped = false;
    private boolean ttsReady = false;
    private SharedPreferences prefs;

    private static final int BG = Color.rgb(11,16,32);
    private static final int CARD = Color.rgb(18,26,45);
    private static final int FIELD = Color.rgb(13,21,39);
    private static final int TEXT = Color.rgb(248,250,252);
    private static final int MUTED = Color.rgb(148,163,184);
    private static final int GREEN = Color.rgb(22,101,52);
    private static final int RED = Color.rgb(127,29,29);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("story_speaker", MODE_PRIVATE);
        buildUi();
        restoreSettings();
        tts = new TextToSpeech(this, this);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("🎙️ 故事播報器", 28, TEXT, true);
        root.addView(title);
        TextView sub = text("打字或貼上故事，手機直接幫你朗讀。", 15, MUTED, false);
        sub.setPadding(0, dp(6), 0, dp(16));
        root.addView(sub);

        LinearLayout card = card();
        root.addView(card);

        card.addView(text("故事內容", 14, TEXT, true));

        storyInput = new EditText(this);
        storyInput.setTextColor(TEXT);
        storyInput.setHintTextColor(MUTED);
        storyInput.setHint("例如：很久以前，在一座被霧包圍的山城……");
        storyInput.setTextSize(17);
        storyInput.setGravity(Gravity.TOP | Gravity.START);
        storyInput.setMinLines(12);
        storyInput.setPadding(dp(14), dp(14), dp(14), dp(14));
        storyInput.setBackground(roundRect(FIELD, 14));
        LinearLayout.LayoutParams storyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        storyLp.setMargins(0, dp(8), 0, dp(14));
        card.addView(storyInput, storyLp);

        card.addView(text("語音", 14, TEXT, true));
        voiceSpinner = new Spinner(this);
        voiceSpinner.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams voiceLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        voiceLp.setMargins(0, dp(6), 0, dp(10));
        card.addView(voiceSpinner, voiceLp);

        card.addView(text("語速", 14, TEXT, true));
        LinearLayout rateRow = new LinearLayout(this);
        rateRow.setGravity(Gravity.CENTER_VERTICAL);
        rateBar = new SeekBar(this);
        rateBar.setMax(100);
        rateBar.setProgress(40);
        rateValue = text("1.00×", 14, TEXT, true);
        rateRow.addView(rateBar, new LinearLayout.LayoutParams(0, dp(48), 1));
        rateRow.addView(rateValue, new LinearLayout.LayoutParams(dp(68), dp(48)));
        card.addView(rateRow);

        card.addView(text("音調", 14, TEXT, true));
        LinearLayout pitchRow = new LinearLayout(this);
        pitchRow.setGravity(Gravity.CENTER_VERTICAL);
        pitchBar = new SeekBar(this);
        pitchBar.setMax(100);
        pitchBar.setProgress(50);
        pitchValue = text("1.00×", 14, TEXT, true);
        pitchRow.addView(pitchBar, new LinearLayout.LayoutParams(0, dp(48), 1));
        pitchRow.addView(pitchValue, new LinearLayout.LayoutParams(dp(68), dp(48)));
        card.addView(pitchRow);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        playBtn = button("▶ 播放", GREEN);
        pauseBtn = button("⏸ 暫停", Color.rgb(38,50,77));
        stopBtn = button("■ 停止", RED);
        LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(0, dp(52), 1);
        b.setMargins(dp(3), dp(8), dp(3), 0);
        buttons.addView(playBtn, b);
        buttons.addView(pauseBtn, b);
        buttons.addView(stopBtn, b);
        card.addView(buttons);

        clearBtn = button("清空故事", Color.rgb(38,50,77));
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        clearLp.setMargins(0, dp(10), 0, 0);
        card.addView(clearBtn, clearLp);

        progressText = text("", 13, MUTED, false);
        progressText.setPadding(0, dp(14), 0, 0);
        card.addView(progressText);
        statusText = text("正在初始化手機語音引擎……", 14, TEXT, false);
        statusText.setPadding(0, dp(6), 0, 0);
        card.addView(statusText);

        TextView note = text("故事只會交給手機的 TTS 引擎朗讀；是否需要網路取決於你手機安裝的語音。", 12, MUTED, false);
        note.setPadding(0, dp(14), 0, 0);
        root.addView(note);

        setContentView(scroll);

        rateBar.setOnSeekBarChangeListener(simpleSeek(() -> {
            rateValue.setText(String.format(Locale.TAIWAN, "%.2f×", getRate()));
            saveSettings();
        }));
        pitchBar.setOnSeekBarChangeListener(simpleSeek(() -> {
            pitchValue.setText(String.format(Locale.TAIWAN, "%.2f×", getPitch()));
            saveSettings();
        }));

        playBtn.setOnClickListener(v -> playOrResume());
        pauseBtn.setOnClickListener(v -> pauseStory());
        stopBtn.setOnClickListener(v -> stopStory(true));
        clearBtn.setOnClickListener(v -> {
            stopStory(true);
            storyInput.setText("");
            saveSettings();
            status("已清空故事。");
        });
    }

    private SeekBar.OnSeekBarChangeListener simpleSeek(Runnable r) {
        return new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) { r.run(); }
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            status("⚠️ 無法啟動手機語音，請確認系統有安裝文字轉語音引擎。");
            return;
        }
        ttsReady = true;
        tts.setLanguage(Locale.TAIWAN);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) {
                runOnUiThread(() -> updateProgress());
            }
            @Override public void onDone(String utteranceId) {
                if (paused || userStopped) return;
                chunkIndex++;
                if (chunkIndex < chunks.size()) {
                    speakCurrentChunk();
                } else {
                    runOnUiThread(() -> {
                        progressText.setText("完成 100%");
                        status("✅ 故事播報完成。");
                    });
                }
            }
            @Override public void onError(String utteranceId) {
                runOnUiThread(() -> status("⚠️ 播報發生錯誤，請換一個語音再試。"));
            }
        });
        loadVoices();
        status("✅ 語音引擎準備完成。");
    }

    private void loadVoices() {
        voiceList.clear();
        Set<Voice> voices = tts.getVoices();
        if (voices != null) voiceList.addAll(voices);
        final Collator collator = Collator.getInstance(Locale.TAIWAN);
        voiceList.sort((a,b) -> {
            int pa = voicePriority(a), pb = voicePriority(b);
            if (pa != pb) return Integer.compare(pa, pb);
            return collator.compare(a.getName(), b.getName());
        });
        List<String> labels = new ArrayList<>();
        for (Voice v : voiceList) {
            String label = v.getLocale().toLanguageTag() + " · " + v.getName();
            if (v.isNetworkConnectionRequired()) label += "（需網路）";
            labels.add(label);
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        voiceSpinner.setAdapter(adapter);
        String saved = prefs.getString("voice", "");
        int selected = 0;
        for (int i=0;i<voiceList.size();i++) {
            if (voiceList.get(i).getName().equals(saved)) { selected = i; break; }
            if (saved.isEmpty() && voicePriority(voiceList.get(i)) == 0) { selected = i; break; }
        }
        if (!voiceList.isEmpty()) voiceSpinner.setSelection(selected);
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { saveSettings(); }
            public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    private int voicePriority(Voice v) {
        String tag = v.getLocale().toLanguageTag().toLowerCase(Locale.ROOT);
        if (tag.startsWith("zh-tw") && !v.isNetworkConnectionRequired()) return 0;
        if (tag.startsWith("zh-tw")) return 1;
        if (tag.startsWith("zh") && !v.isNetworkConnectionRequired()) return 2;
        if (tag.startsWith("zh")) return 3;
        return 4;
    }

    private void playOrResume() {
        if (!ttsReady) { status("語音引擎還沒準備好。"); return; }
        if (paused && !chunks.isEmpty()) {
            paused = false;
            userStopped = false;
            status("▶ 繼續播報。");
            speakCurrentChunk();
            return;
        }
        String text = storyInput.getText().toString().trim();
        if (text.isEmpty()) { status("請先輸入故事內容。"); return; }
        stopStory(false);
        saveSettings();
        chunks.clear();
        chunks.addAll(splitText(text, 260));
        chunkIndex = 0;
        paused = false;
        userStopped = false;
        status("🎙️ 開始播報。");
        speakCurrentChunk();
    }

    private void speakCurrentChunk() {
        if (chunkIndex >= chunks.size() || paused || userStopped) return;
        applyVoiceSettings();
        String id = "story_" + chunkIndex + "_" + System.nanoTime();
        tts.speak(chunks.get(chunkIndex), TextToSpeech.QUEUE_FLUSH, null, id);
    }

    private void pauseStory() {
        if (!ttsReady || chunks.isEmpty()) { status("目前沒有正在播報的故事。"); return; }
        if (paused) {
            paused = false;
            userStopped = false;
            status("▶ 繼續播報。");
            speakCurrentChunk();
        } else {
            paused = true;
            tts.stop();
            status("⏸ 已暫停，會從目前這一段重新開始。");
        }
    }

    private void stopStory(boolean showStatus) {
        userStopped = true;
        paused = false;
        if (tts != null) tts.stop();
        chunks.clear();
        chunkIndex = 0;
        progressText.setText("");
        if (showStatus) status("■ 已停止。");
    }

    private void applyVoiceSettings() {
        tts.setSpeechRate(getRate());
        tts.setPitch(getPitch());
        int pos = voiceSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < voiceList.size()) tts.setVoice(voiceList.get(pos));
    }

    private float getRate() { return 0.60f + (rateBar.getProgress() / 100f) * 1.00f; }
    private float getPitch() { return 0.50f + (pitchBar.getProgress() / 100f) * 1.00f; }

    private List<String> splitText(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        String cleaned = text.replace("\r", "").trim();
        String[] sentences = cleaned.split("(?<=[。！？!?；;\\n])");
        StringBuilder current = new StringBuilder();
        for (String raw : sentences) {
            String s = raw.trim();
            if (s.isEmpty()) continue;
            if (current.length() + s.length() <= maxLen) {
                if (current.length() > 0) current.append(' ');
                current.append(s);
            } else {
                if (current.length() > 0) { out.add(current.toString()); current.setLength(0); }
                if (s.length() <= maxLen) current.append(s);
                else {
                    for (int i=0;i<s.length();i+=maxLen) out.add(s.substring(i, Math.min(i+maxLen, s.length())));
                }
            }
        }
        if (current.length() > 0) out.add(current.toString());
        if (out.isEmpty() && !cleaned.isEmpty()) out.add(cleaned);
        return out;
    }

    private void updateProgress() {
        int total = Math.max(1, chunks.size());
        int percent = Math.min(100, (int)Math.round((chunkIndex * 100.0) / total));
        progressText.setText("第 " + (chunkIndex + 1) + " / " + total + " 段 · 約 " + percent + "%");
    }

    private void status(String s) { statusText.setText(s); }

    private void saveSettings() {
        if (storyInput == null) return;
        String voice = "";
        int pos = voiceSpinner == null ? -1 : voiceSpinner.getSelectedItemPosition();
        if (pos >= 0 && pos < voiceList.size()) voice = voiceList.get(pos).getName();
        prefs.edit()
                .putString("story", storyInput.getText().toString())
                .putInt("rate", rateBar.getProgress())
                .putInt("pitch", pitchBar.getProgress())
                .putString("voice", voice)
                .apply();
    }

    private void restoreSettings() {
        storyInput.setText(prefs.getString("story", ""));
        rateBar.setProgress(prefs.getInt("rate", 40));
        pitchBar.setProgress(prefs.getInt("pitch", 50));
        rateValue.setText(String.format(Locale.TAIWAN, "%.2f×", getRate()));
        pitchValue.setText(String.format(Locale.TAIWAN, "%.2f×", getPitch()));
    }

    @Override protected void onPause() {
        super.onPause();
        saveSettings();
    }

    @Override protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        super.onDestroy();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String s, int bg) {
        Button b = new Button(this);
        b.setText(s); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false);
        b.setBackground(roundRect(bg, 12));
        return b;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(16), dp(16), dp(18));
        l.setBackground(roundRect(CARD, 20));
        return l;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
}
