package com.tft.purchase;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** User-editable local dictionary for own-company aliases, vendors and commonly purchased items. */
public class KnowledgeBaseActivity extends Activity {
    private static final int BG = Color.rgb(245,248,255);
    private static final int NAVY = Color.rgb(15,42,92);
    private static final int BLUE = Color.rgb(37,99,235);
    private static final int GREEN = Color.rgb(22,163,74);
    private static final int RED = Color.rgb(220,38,38);
    private static final int GRAY = Color.rgb(71,85,105);

    private RecognitionKnowledgeDb db;
    private EditText aliasesEt;
    private LinearLayout vendorsBox;
    private final List<VendorRow> rows = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new RecognitionKnowledgeDb(this);
        render();
    }

    private void render() {
        rows.clear();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14),dp(16),dp(14),dp(40));
        page.setBackgroundColor(BG);

        LinearLayout top = row();
        Button back = mini("← 返回", Color.rgb(226,232,240)); back.setTextColor(NAVY); back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = text("辨識字典／廠商資料庫",22,NAVY,true); title.setPadding(dp(12),0,0,0);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        page.addView(top);

        page.addView(info("這些資料只協助辨識，不會強迫 AI 亂填",
                "APP 會先看圖片與 OCR。只有畫面真的出現已知廠商名稱／關鍵字時，才用這裡的資料做正規化。你人工修正過的採購單，也會把確認過的廠商與常購品項學進來。"));

        page.addView(section("我方公司名稱／別名"));
        aliasesEt = input("一行一個名稱");
        aliasesEt.setMinLines(4);
        StringBuilder aliases = new StringBuilder();
        for (String a : db.listOwnAliases()) { if (aliases.length()>0) aliases.append('\n'); aliases.append(a); }
        aliasesEt.setText(aliases.toString());
        page.addView(aliasesEt,margins(3,8));
        page.addView(text("這裡的名稱永遠不能被辨識成『供應廠商』。系統會固定保留「全益畜牧器具公司」這條安全規則。",13,GRAY,false));

        page.addView(section("既有廠商與常購品項"));
        vendorsBox = new LinearLayout(this); vendorsBox.setOrientation(LinearLayout.VERTICAL); page.addView(vendorsBox);
        List<RecognitionKnowledgeDb.Vendor> vendors = db.listVendors();
        for (RecognitionKnowledgeDb.Vendor v : vendors) addVendorRow(v);
        if (vendors.isEmpty()) addVendorRow(new RecognitionKnowledgeDb.Vendor());

        Button add = action("＋ 新增廠商", BLUE); add.setOnClickListener(v -> addVendorRow(new RecognitionKnowledgeDb.Vendor()));
        page.addView(add,margins(8,8));

        Button save = action("✓ 儲存辨識字典", GREEN); save.setTextSize(18); save.setOnClickListener(v -> saveAll());
        page.addView(save,margins(12,8));

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
    }

    private void addVendorRow(RecognitionKnowledgeDb.Vendor vendor) {
        LinearLayout card = card(Color.WHITE,Color.rgb(148,163,184));
        LinearLayout head = row();
        TextView t = text(vendor.id>0 ? "廠商資料" : "新增廠商",16,NAVY,true); head.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        Button del = mini("刪除",Color.rgb(254,226,226)); del.setTextColor(RED); head.addView(del); card.addView(head);

        EditText name = field("正式廠商名稱",vendor.name); card.addView(name,margins(4,3));
        EditText address = field("固定地址／地區",vendor.address); card.addView(address,margins(3,3));
        EditText keywords = field("辨識關鍵字（逗號分隔，例如簡稱、代號）",vendor.keywords); card.addView(keywords,margins(3,3));
        EditText products = field("常購品項／規格（一行一個）",vendor.products); products.setMinLines(3); card.addView(products,margins(3,3));

        VendorRow row = new VendorRow(vendor.id,card,name,address,keywords,products); rows.add(row);
        del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("刪除此廠商？")
                .setMessage("只會刪除辨識字典，不會刪除既有採購單。")
                .setNegativeButton("取消",null).setPositiveButton("刪除",(d,w)->{
                    if (row.id>0) db.deleteVendor(row.id);
                    rows.remove(row); vendorsBox.removeView(card);
                }).show());
        vendorsBox.addView(card,margins(5,8));
    }

    private void saveAll() {
        List<String> aliases = new ArrayList<>();
        aliases.addAll(Arrays.asList(value(aliasesEt).split("[\\r\\n]+")));
        db.saveOwnAliases(aliases);
        for (VendorRow r : rows) {
            String name = value(r.name);
            if (name.isEmpty()) continue;
            RecognitionKnowledgeDb.Vendor v = new RecognitionKnowledgeDb.Vendor();
            v.id = r.id; v.name = name; v.address = value(r.address); v.keywords = value(r.keywords); v.products = value(r.products);
            r.id = db.upsertVendor(v);
        }
        Toast.makeText(this,"辨識字典已儲存",Toast.LENGTH_LONG).show();
        render();
    }

    private String value(EditText e){return e==null?"":e.getText().toString().trim();}
    private EditText field(String hint,String value){EditText e=input(hint);e.setText(value==null?"":value);return e;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setTextColor(Color.rgb(15,23,42));e.setHintTextColor(Color.rgb(100,116,139));e.setPadding(dp(11),dp(10),dp(11),dp(10));e.setBackground(box(Color.WHITE,Color.rgb(148,163,184),1));return e;}
    private ViewGroup.LayoutParams margins(int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(2),dp(top),dp(2),dp(bottom));return p;}
    private LinearLayout card(int fill,int stroke){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(13),dp(11),dp(13),dp(11));c.setBackground(box(fill,stroke,2));return c;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private TextView section(String s){TextView v=text(s,17,NAVY,true);v.setPadding(dp(2),dp(16),dp(2),dp(8));return v;}
    private LinearLayout info(String title,String body){LinearLayout c=card(Color.WHITE,Color.rgb(203,213,225));c.addView(text(title,16,NAVY,true));c.addView(text(body,14,GRAY,false));return c;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(2),dp(3),dp(2),dp(3));return v;}
    private Button action(String s,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(12),dp(11),dp(12),dp(11));return b;}
    private Button mini(String s,int color){Button b=action(s,color);b.setTextSize(13);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),dp(7),dp(10),dp(7));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(5));return g;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}

    private static final class VendorRow {
        long id; final LinearLayout card; final EditText name,address,keywords,products;
        VendorRow(long id,LinearLayout card,EditText name,EditText address,EditText keywords,EditText products){this.id=id;this.card=card;this.name=name;this.address=address;this.keywords=keywords;this.products=products;}
    }
}
