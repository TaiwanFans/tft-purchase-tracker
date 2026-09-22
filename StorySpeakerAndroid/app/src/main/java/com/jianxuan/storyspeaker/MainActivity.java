package com.jianxuan.storyspeaker;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.text.Collator;
import java.util.*;

public class MainActivity extends Activity implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private EditText story;
    private Spinner voice;
    private SeekBar rate, pitch;
    private TextView rateValue, pitchValue, counter, state, detail, voiceHint;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private final List<Voice> voices = new ArrayList<>();
    private final List<String> parts = new ArrayList<>();
    private int part = 0;
    private boolean paused = false, stopped = true, ready = false, preview = false;

    private final int BG = Color.rgb(248,241,234);
    private final int CARD = Color.rgb(255,250,246);
    private final int FIELD = Color.rgb(255,253,251);
    private final int HEADER = Color.rgb(245,225,211);
    private final int BORDER = Color.rgb(231,210,198);
    private final int TEXT = Color.rgb(67,40,31);
    private final int MUTED = Color.rgb(131,103,92);
    private final int ACCENT = Color.rgb(215,105,85);
    private final int ORANGE = Color.rgb(240,169,109);
    private final int RED = Color.rgb(210,94,89);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(249,239,231));
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("story_speaker", MODE_PRIVATE);
        buildUi();
        restore();
        tts = new TextToSpeech(this, this);
    }

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        sv.addView(root, new ScrollView.LayoutParams(-1,-2));

        root.addView(header()); gap(root,14);
        root.addView(storyCard()); gap(root,14);
        root.addView(voiceCard()); gap(root,14);
        root.addView(controlCard()); gap(root,14);
        root.addView(statusCard());

        TextView foot = tv("好故事・讓生活多一點溫度",12,MUTED,false);
        foot.setGravity(Gravity.CENTER); foot.setPadding(0,dp(18),0,0); root.addView(foot);
        setContentView(sv);
    }

    private View header() {
        LinearLayout c = card(HEADER,24);
        TextView pill = tv("📖  故事播報器",13,ACCENT,true);
        pill.setPadding(dp(11),dp(6),dp(11),dp(6));
        pill.setBackground(shape(Color.rgb(255,247,241),999,BORDER,1));
        c.addView(pill, wrap());
        TextView title = tv("讓美好的故事，\n用聲音陪伴每一天",28,TEXT,true);
        title.setLineSpacing(0,1.08f); title.setPadding(0,dp(12),0,0); c.addView(title);
        TextView sub = tv("輸入文字，選擇聲音，讓故事被溫柔地讀出來。",14,MUTED,false);
        sub.setPadding(0,dp(9),0,0); c.addView(sub);
        TextView quote = tv("一個好故事，能讓心靈走得更遠。",13,ACCENT,false);
        quote.setPadding(0,dp(14),0,0); c.addView(quote);
        return c;
    }

    private View storyCard() {
        LinearLayout c = card(CARD,24);
        LinearLayout h = row(); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = tv("故事內容",21,TEXT,true); h.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        counter = tv("0 / 5000",14,MUTED,false); h.addView(counter); c.addView(h);
        TextView sub = tv("輸入或貼上想要朗讀的故事、文章或講稿。",13,MUTED,false);
        sub.setPadding(0,dp(5),0,dp(12)); c.addView(sub);
        story = new EditText(this); story.setTextSize(18); story.setTextColor(TEXT); story.setHintTextColor(Color.rgb(183,154,140));
        story.setHint("例如：從前，有一個小村莊……"); story.setGravity(Gravity.TOP|Gravity.START); story.setMinLines(9);
        story.setLineSpacing(0,1.2f); story.setPadding(dp(16),dp(15),dp(16),dp(15)); story.setBackground(shape(FIELD,20,BORDER,1));
        story.setFilters(new InputFilter[]{new InputFilter.LengthFilter(5000)}); c.addView(story,new LinearLayout.LayoutParams(-1,-2));
        story.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int d){} public void onTextChanged(CharSequence s,int a,int b,int d){ counter.setText(s.length()+" / 5000"); save(); } public void afterTextChanged(Editable e){}});
        return c;
    }

    private View voiceCard() {
        LinearLayout c = card(CARD,24);
        LinearLayout h=row(); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView t=tv("語音",21,TEXT,true); h.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        Button test=smallButton("試聽"); h.addView(test,new LinearLayout.LayoutParams(dp(82),dp(44))); c.addView(h);
        TextView sub=tv("挑一個你喜歡的手機語音，正式播放前可以先試聽。",13,MUTED,false); sub.setPadding(0,dp(5),0,dp(12)); c.addView(sub);
        LinearLayout box=column(); box.setPadding(dp(14),dp(10),dp(14),dp(10)); box.setBackground(shape(FIELD,18,BORDER,1)); c.addView(box);
        voice=new Spinner(this,Spinner.MODE_DROPDOWN); box.addView(voice,new LinearLayout.LayoutParams(-1,dp(52)));
        voiceHint=tv("優先顯示繁體中文語音",13,MUTED,false); voiceHint.setPadding(0,dp(7),0,0); box.addView(voiceHint);
        test.setOnClickListener(v->preview());
        return c;
    }

    private View controlCard() {
        LinearLayout c=card(CARD,24);
        c.addView(slider("語速","較慢","較快",true)); gap(c,14); c.addView(slider("音調","較低","較高",false)); gap(c,18);
        LinearLayout r=row();
        Button play=bigButton("▶  播放",ACCENT), pause=bigButton("Ⅱ  暫停",ORANGE), stop=bigButton("■  停止",RED);
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(62),1); bp.setMargins(dp(4),0,dp(4),0);
        r.addView(play,bp); r.addView(pause,bp); r.addView(stop,bp); c.addView(r);
        Button clear=smallButton("清空故事"); clear.setTextSize(17); clear.setTextColor(ACCENT);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(56)); cp.setMargins(dp(4),dp(13),dp(4),0); c.addView(clear,cp);
        play.setOnClickListener(v->play()); pause.setOnClickListener(v->pause()); stop.setOnClickListener(v->stop(true));
        clear.setOnClickListener(v->{ stop(false); story.setText(""); state.setText("準備就緒"); detail.setText("輸入故事內容後即可播放"); });
        return c;
    }

    private View slider(String name,String left,String right,boolean speed) {
        LinearLayout w=column(); LinearLayout h=row(); h.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=tv(name,19,TEXT,true); h.addView(label,new LinearLayout.LayoutParams(0,-2,1)); TextView val=tv("1.00×",18,TEXT,true); h.addView(val); w.addView(h);
        SeekBar bar=new SeekBar(this); bar.setMax(100); bar.setProgress(speed?40:50); bar.setProgressTintList(ColorStateList.valueOf(ACCENT)); bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        w.addView(bar,new LinearLayout.LayoutParams(-1,dp(46)));
        LinearLayout labs=row(); TextView l=tv(left,12,MUTED,false), mid=tv("正常",12,MUTED,false), rr=tv(right,12,MUTED,false);
        l.setGravity(Gravity.START); mid.setGravity(Gravity.CENTER); rr.setGravity(Gravity.END); labs.addView(l,new LinearLayout.LayoutParams(0,-2,1)); labs.addView(mid,new LinearLayout.LayoutParams(0,-2,1)); labs.addView(rr,new LinearLayout.LayoutParams(0,-2,1)); w.addView(labs);
        if(speed){rate=bar;rateValue=val;} else {pitch=bar;pitchValue=val;}
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean f){val.setText(String.format(Locale.TAIWAN,"%.2f×",speed?getRate():getPitch()));save();} public void onStartTrackingTouch(SeekBar s){} public void onStopTrackingTouch(SeekBar s){}});
        return w;
    }

    private View statusCard() {
        LinearLayout c=card(CARD,24); LinearLayout h=row(); h.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left=column(); state=tv("正在初始化語音引擎…",19,TEXT,true); detail=tv("請稍候",13,MUTED,false); detail.setPadding(0,dp(5),0,0); left.addView(state); left.addView(detail); h.addView(left,new LinearLayout.LayoutParams(0,-2,1));
        TextView mark=tv("♪",28,ACCENT,true); h.addView(mark); c.addView(h);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgress(0); progress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(10)); pp.setMargins(0,dp(13),0,0); c.addView(progress,pp); return c;
    }

    @Override public void onInit(int s) {
        if(s!=TextToSpeech.SUCCESS){state.setText("無法啟動語音");detail.setText("請檢查手機的文字轉語音設定");return;}
        ready=true; tts.setLanguage(Locale.TAIWAN);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
            public void onStart(String id){runOnUiThread(()->updateProgress());}
            public void onDone(String id){
                if(preview){preview=false;runOnUiThread(()->{state.setText("試聽完成");detail.setText("喜歡這個聲音，就可以直接播放故事");});return;}
                if(paused||stopped)return; part++; if(part<parts.size()) speakPart(); else runOnUiThread(()->{progress.setProgress(100);state.setText("故事播報完成");detail.setText("已讀完整篇內容");});
            }
            public void onError(String id){runOnUiThread(()->{state.setText("播報失敗");detail.setText("請換一個語音再試一次");});}
        });
        loadVoices(); state.setText("準備就緒"); detail.setText("輸入故事內容後即可播放");
    }

    private void loadVoices(){
        voices.clear(); Set<Voice> set=tts.getVoices(); if(set!=null)voices.addAll(set); Collator co=Collator.getInstance(Locale.TAIWAN);
        voices.sort((a,b)->{int x=priority(a),y=priority(b);return x!=y?Integer.compare(x,y):co.compare(a.getName(),b.getName());});
        List<String> labels=new ArrayList<>(); for(Voice v:voices){String s=v.getName()+" · "+v.getLocale().toLanguageTag(); if(v.isNetworkConnectionRequired())s+="（需網路）";labels.add(s);}
        ArrayAdapter<String> ad=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){
            public View getView(int p,View cv,ViewGroup par){TextView t=(TextView)super.getView(p,cv,par);t.setTextColor(TEXT);t.setTextSize(16);t.setPadding(dp(4),dp(7),dp(4),dp(7));return t;}
            public View getDropDownView(int p,View cv,ViewGroup par){TextView t=(TextView)super.getDropDownView(p,cv,par);t.setTextColor(TEXT);t.setTextSize(15);t.setPadding(dp(12),dp(12),dp(12),dp(12));return t;}};
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); voice.setAdapter(ad);
        String saved=prefs.getString("voice",""); int pos=0; for(int i=0;i<voices.size();i++){if(voices.get(i).getName().equals(saved)){pos=i;break;}if(saved.isEmpty()&&priority(voices.get(i))==0){pos=i;break;}}
        if(!voices.isEmpty()){voice.setSelection(pos);voiceHint.setText(hint(voices.get(pos)));}
        voice.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int x,long id){if(x>=0&&x<voices.size())voiceHint.setText(hint(voices.get(x)));save();}public void onNothingSelected(AdapterView<?> p){}});
    }

    private String hint(Voice v){String s=v.getLocale().toLanguageTag().toLowerCase(Locale.ROOT);String h=s.startsWith("zh-tw")?"繁體中文語音・適合故事朗讀":s.startsWith("zh")?"中文語音・適合一般朗讀":"其他語言語音";if(v.isNetworkConnectionRequired())h+="・需要網路";return h;}
    private int priority(Voice v){String s=v.getLocale().toLanguageTag().toLowerCase(Locale.ROOT);if(s.startsWith("zh-tw")&&!v.isNetworkConnectionRequired())return 0;if(s.startsWith("zh-tw"))return 1;if(s.startsWith("zh")&&!v.isNetworkConnectionRequired())return 2;if(s.startsWith("zh"))return 3;return 4;}

    private void preview(){if(!ready){state.setText("語音還沒準備好");return;}applyVoice();tts.stop();preview=true;paused=false;stopped=false;state.setText("正在試聽");detail.setText("這是一段溫暖的語音試聽");tts.speak("這是一段語音試聽。願每一個好故事，都能被溫柔地聽見。",TextToSpeech.QUEUE_FLUSH,null,"preview");}
    private void play(){if(!ready){state.setText("語音還沒準備好");return;}if(paused&&!parts.isEmpty()){paused=false;stopped=false;state.setText("繼續播報中");speakPart();return;}String txt=story.getText().toString().trim();if(txt.isEmpty()){state.setText("請先輸入故事內容");detail.setText("上方文字框目前是空的");return;}stop(false);parts.clear();parts.addAll(split(txt,260));part=0;paused=false;stopped=false;preview=false;state.setText("正在播報");detail.setText("第 1 / "+parts.size()+" 段");speakPart();}
    private void speakPart(){if(stopped||paused||part>=parts.size())return;applyVoice();tts.speak(parts.get(part),TextToSpeech.QUEUE_FLUSH,null,"p"+part+"_"+System.nanoTime());}
    private void pause(){if(parts.isEmpty()){state.setText("目前沒有正在播報的故事");return;}if(paused){paused=false;stopped=false;state.setText("繼續播報中");speakPart();}else{paused=true;tts.stop();state.setText("已暫停");detail.setText("會從目前這一段重新開始");}}
    private void stop(boolean show){stopped=true;paused=false;preview=false;if(tts!=null)tts.stop();parts.clear();part=0;if(progress!=null)progress.setProgress(0);if(show){state.setText("已停止");detail.setText("輸入故事內容後即可播放");}}
    private void updateProgress(){if(preview)return;int total=Math.max(1,parts.size());int pc=Math.min(100,(int)Math.round(part*100.0/total));progress.setProgress(pc);detail.setText("第 "+Math.min(total,part+1)+" / "+total+" 段・約 "+pc+"%");}
    private void applyVoice(){tts.setSpeechRate(getRate());tts.setPitch(getPitch());int p=voice.getSelectedItemPosition();if(p>=0&&p<voices.size())tts.setVoice(voices.get(p));}
    private float getRate(){return .60f+(rate.getProgress()/100f)*1.0f;} private float getPitch(){return .50f+(pitch.getProgress()/100f)*1.0f;}

    private List<String> split(String txt,int max){List<String> out=new ArrayList<>();String[] ss=txt.replace("\r","").split("(?<=[。！？!?；;\\n])");StringBuilder cur=new StringBuilder();for(String raw:ss){String s=raw.trim();if(s.isEmpty())continue;if(cur.length()+s.length()<=max){if(cur.length()>0)cur.append(' ');cur.append(s);}else{if(cur.length()>0){out.add(cur.toString());cur.setLength(0);}for(int i=0;i<s.length();i+=max)out.add(s.substring(i,Math.min(i+max,s.length())));}}if(cur.length()>0)out.add(cur.toString());if(out.isEmpty()&&!txt.trim().isEmpty())out.add(txt.trim());return out;}

    private void save(){if(story==null||rate==null||pitch==null)return;String vn="";if(voice!=null){int p=voice.getSelectedItemPosition();if(p>=0&&p<voices.size())vn=voices.get(p).getName();}prefs.edit().putString("story",story.getText().toString()).putInt("rate",rate.getProgress()).putInt("pitch",pitch.getProgress()).putString("voice",vn).apply();}
    private void restore(){story.setText(prefs.getString("story",""));rate.setProgress(prefs.getInt("rate",40));pitch.setProgress(prefs.getInt("pitch",50));rateValue.setText(String.format(Locale.TAIWAN,"%.2f×",getRate()));pitchValue.setText(String.format(Locale.TAIWAN,"%.2f×",getPitch()));counter.setText(story.length()+" / 5000");}
    @Override protected void onPause(){super.onPause();save();} @Override protected void onDestroy(){if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}

    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;} private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    private LinearLayout card(int color,int radius){LinearLayout l=column();l.setPadding(dp(18),dp(17),dp(18),dp(17));l.setBackground(shape(color,radius,BORDER,1));return l;}
    private TextView tv(String s,int sp,int c,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(c);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private Button bigButton(String s,int c){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextSize(18);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(shape(c,20,Color.TRANSPARENT,0));return b;}
    private Button smallButton(String s){Button b=new Button(this);b.setAllCaps(false);b.setText(s);b.setTextSize(14);b.setTextColor(ACCENT);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(shape(Color.rgb(255,247,243),17,BORDER,1));return b;}
    private GradientDrawable shape(int fill,int radius,int stroke,int sw){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setCornerRadius(dp(radius));if(sw>0)g.setStroke(dp(sw),stroke);return g;}
    private LinearLayout.LayoutParams wrap(){return new LinearLayout.LayoutParams(-2,-2);} private void gap(LinearLayout p,int d){View v=new View(this);p.addView(v,new LinearLayout.LayoutParams(-1,dp(d)));} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
