package com.tft.purchase;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Manual correction screen. AI pre-fills everything; confirmed edits improve local knowledge. */
public class PurchaseEditActivity extends Activity {
    private static final int BG = Color.rgb(245, 248, 255);
    private static final int NAVY = Color.rgb(15, 42, 92);
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int RED = Color.rgb(220, 38, 38);
    private static final int GRAY = Color.rgb(71, 85, 105);

    private PurchaseDbHelper db;
    private PurchaseDbHelper.Purchase purchase;
    private EditText vendorEt, locationEt, orderEt, purchaseDateEt;
    private LinearLayout itemsBox;
    private final List<ItemEditor> editors = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new PurchaseDbHelper(this);
        long id = getIntent().getLongExtra("purchase_id", -1L);
        purchase = db.get(id);
        if (purchase == null) {
            Toast.makeText(this, "找不到採購單", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        render();
    }

    private void render() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(12), dp(14), dp(40));
        page.setBackgroundColor(BG);

        LinearLayout top = row();
        Button back = mini("← 返回", Color.rgb(226,232,240));
        back.setTextColor(NAVY);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = text("修改 AI 辨識結果", 22, NAVY, true);
        title.setPadding(dp(12),0,0,0);
        top.addView(title, new LinearLayout.LayoutParams(0,-2,1));
        page.addView(top);

        page.addView(info("AI 先填，你只修錯的地方", "品名與規格已分開保存。儲存後會更新交貨提醒；只有你人工確認過的廠商、地址、品名與規格才會加入本機辨識知識庫。"), margins(10,10));

        if (purchase.imagePath != null && !purchase.imagePath.isEmpty() && new File(purchase.imagePath).exists()) {
            ImageView img = new ImageView(this);
            img.setAdjustViewBounds(true);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageBitmap(previewBitmap(purchase.imagePath));
            img.setBackground(box(Color.WHITE, Color.rgb(148,163,184), 1));
            page.addView(img, marginsRaw(-1, dp(280), 4, 10));
        }

        page.addView(section("AI 辨識資料｜採購單表頭"));
        vendorEt = field("供應廠商", purchase.vendorName); page.addView(vendorEt, margins(3,4));
        locationEt = field("廠商地址／地區", purchase.location); page.addView(locationEt, margins(3,4));
        orderEt = field("採購單號", purchase.orderNo); page.addView(orderEt, margins(3,4));
        purchaseDateEt = field("採購日期 YYYY-MM-DD", purchase.purchaseDate); page.addView(purchaseDateEt, margins(3,8));

        page.addView(section("AI 辨識資料｜品項"));
        page.addView(info("每一列都可修正", "可以修改項次、品名、規格、數量、單位、價格、交貨日期與備註；AI 多抓的品項可刪除，漏掉的可新增。"), margins(2,8));
        itemsBox = new LinearLayout(this);
        itemsBox.setOrientation(LinearLayout.VERTICAL);
        page.addView(itemsBox);
        List<PurchaseDbHelper.PurchaseItem> items = db.listItems(purchase.id);
        for (PurchaseDbHelper.PurchaseItem i : items) addEditor(i);
        if (items.isEmpty()) addEditor(new PurchaseDbHelper.PurchaseItem());

        Button add = action("＋ 新增品項", BLUE);
        add.setOnClickListener(v -> {
            PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
            i.lineNo = String.format(java.util.Locale.TAIWAN, "%03d", editors.size()+1);
            addEditor(i);
        });
        page.addView(add, margins(8,8));

        Button save = action("✓ 儲存修改", GREEN);
        save.setTextSize(18);
        save.setOnClickListener(v -> saveAll());
        page.addView(save, margins(12,7));
        Button cancel = action("取消修改", GRAY);
        cancel.setOnClickListener(v -> finish());
        page.addView(cancel, margins(3,14));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(page);
        applyInsets(scroll);
        setContentView(scroll);
    }

    private void addEditor(PurchaseDbHelper.PurchaseItem item) {
        final LinearLayout card = card(Color.WHITE, Color.rgb(148,163,184));
        LinearLayout head = row();
        EditText line = smallField("項次", item.lineNo);
        head.addView(line, new LinearLayout.LayoutParams(dp(92), -2));
        TextView label = text("品項資料", 16, NAVY, true);
        label.setPadding(dp(10),0,0,0);
        head.addView(label, new LinearLayout.LayoutParams(0,-2,1));
        Button remove = mini("刪除此品項", Color.rgb(254,226,226));
        remove.setTextColor(RED);
        head.addView(remove);
        card.addView(head);

        EditText desc = field("品名", item.description);
        desc.setMinLines(1);
        card.addView(desc, margins(5,4));

        EditText spec = field("規格", item.specification);
        spec.setMinLines(2);
        spec.setGravity(Gravity.TOP | Gravity.START);
        card.addView(spec, margins(3,4));

        LinearLayout r1 = row();
        EditText qty = smallField("數量", item.quantity);
        EditText unit = smallField("單位", item.unit);
        EditText price = smallField("單價", item.unitPrice);
        r1.addView(qty, weight()); r1.addView(unit, weight()); r1.addView(price, weight());
        card.addView(r1);

        LinearLayout r2 = row();
        EditText subtotal = smallField("小計", item.subtotal);
        EditText delivery = smallField("交貨日 YYYY-MM-DD", item.deliveryDate);
        r2.addView(subtotal, weight());
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(0,-2,1.45f); wide.setMargins(dp(3),dp(3),dp(3),dp(3));
        r2.addView(delivery, wide);
        card.addView(r2);

        EditText note = field("品項備註／衝突提示", item.note); card.addView(note, margins(3,3));
        CheckBox completed = new CheckBox(this); completed.setText("此品項已完成"); completed.setTextColor(GRAY); completed.setChecked(item.completed); card.addView(completed);

        ItemEditor ed = new ItemEditor(card, line, desc, spec, qty, unit, price, subtotal, delivery, note, completed);
        editors.add(ed);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("刪除此品項？")
                .setMessage("儲存後才會正式刪除。")
                .setNegativeButton("取消", null).setPositiveButton("刪除", (d,w) -> {
                    editors.remove(ed); itemsBox.removeView(card);
                }).show());
        itemsBox.addView(card, margins(4,8));
    }

    private void saveAll() {
        purchase.vendorName = value(vendorEt);
        if (AiResultValidator.isSelfCompany(purchase.vendorName)) {
            vendorEt.setError("這是我方公司名稱，不能當供應廠商");
            vendorEt.requestFocus();
            Toast.makeText(this, "供應廠商不能是全益／台灣電扇等我方公司名稱", Toast.LENGTH_LONG).show();
            return;
        }
        purchase.location = value(locationEt);
        purchase.orderNo = value(orderEt).replaceAll("\\s+", "");
        purchase.purchaseDate = normalizeDate(value(purchaseDateEt));

        List<PurchaseDbHelper.PurchaseItem> items = new ArrayList<>();
        StringBuilder legacy = new StringBuilder();
        String earliest = "";
        for (ItemEditor e : editors) {
            String desc = value(e.desc);
            String spec = value(e.spec);
            if (desc.isEmpty() && spec.isEmpty() && value(e.qty).isEmpty() && value(e.delivery).isEmpty()) continue;
            PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
            i.lineNo = value(e.line);
            i.description = desc;
            i.specification = spec;
            i.quantity = value(e.qty);
            i.unit = value(e.unit);
            i.unitPrice = value(e.price);
            i.subtotal = value(e.subtotal);
            i.deliveryDate = normalizeDate(value(e.delivery));
            i.note = value(e.note);
            i.completed = e.completed.isChecked();
            items.add(i);
            if (legacy.length() > 0) legacy.append("\n");
            legacy.append(i.description);
            if (!i.specification.isEmpty()) legacy.append("｜規格：").append(i.specification);
            if (!i.quantity.isEmpty()) legacy.append(" × ").append(i.quantity).append(i.unit);
            if (!i.deliveryDate.isEmpty() && (earliest.isEmpty() || i.deliveryDate.compareTo(earliest) < 0)) earliest = i.deliveryDate;
        }
        purchase.legacyItems = legacy.toString();
        purchase.legacyDeliveryDate = earliest;
        db.update(purchase);
        db.replaceItems(purchase.id, items);
        try { new RecognitionKnowledgeDb(this).learnFromConfirmedPurchase(purchase, items); } catch (Throwable ignored) {}
        try { DriveBackupHelper.backupIfDueAsync(this); } catch (Throwable ignored) {}
        Toast.makeText(this, "已儲存人工修正；確認過的廠商、品名與規格已更新到本機辨識知識庫", Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        finish();
    }

    private String normalizeDate(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        String d = PurchaseOcrParser.extractDate(s.trim());
        return d == null || d.isEmpty() ? s.trim() : d;
    }

    private String value(EditText e) { return e == null ? "" : e.getText().toString().trim(); }
    private EditText field(String hint, String val) { EditText e = input(hint); e.setText(val == null ? "" : val); return e; }
    private EditText smallField(String hint, String val) { EditText e = input(hint); e.setText(val == null ? "" : val); e.setTextSize(14); return e; }

    private Bitmap previewBitmap(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true; BitmapFactory.decodeFile(path,o);
        int sample = 1; while (o.outWidth/sample > 1500 || o.outHeight/sample > 1800) sample *= 2;
        o.inJustDecodeBounds = false; o.inSampleSize = sample; return BitmapFactory.decodeFile(path,o);
    }

    private View info(String title,String body){LinearLayout c=card(Color.WHITE,Color.rgb(203,213,225));c.addView(text(title,16,NAVY,true));c.addView(text(body,14,GRAY,false));return c;}
    private TextView section(String s){TextView v=text(s,17,NAVY,true);v.setPadding(dp(2),dp(16),dp(2),dp(8));return v;}
    private LinearLayout card(int fill,int stroke){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(13),dp(11),dp(13),dp(11));c.setBackground(box(fill,stroke,2));return c;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),dp(3),dp(3),dp(3));return p;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setTextColor(Color.rgb(15,23,42));e.setHintTextColor(Color.rgb(100,116,139));e.setPadding(dp(11),dp(10),dp(11),dp(10));e.setBackground(box(Color.WHITE,Color.rgb(148,163,184),1));return e;}
    private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(2),dp(3),dp(2),dp(3));return v;}
    private Button action(String s,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(12),dp(11),dp(12),dp(11));return b;}
    private Button mini(String s,int color){Button b=action(s,color);b.setTextSize(13);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),dp(7),dp(10),dp(7));return b;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(5));return g;}
    private LinearLayout.LayoutParams margins(int top,int bottom){return marginsRaw(-1,-2,top,bottom);}
    private LinearLayout.LayoutParams marginsRaw(int w,int h,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(2),dp(top),dp(2),dp(bottom));return p;}
    private void applyInsets(View root){if(android.os.Build.VERSION.SDK_INT>=21)root.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());top=b.top;bottom=b.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(0,top,0,bottom);return insets;});}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}

    private static class ItemEditor {
        final LinearLayout card; final EditText line, desc, spec, qty, unit, price, subtotal, delivery, note; final CheckBox completed;
        ItemEditor(LinearLayout card, EditText line, EditText desc, EditText spec, EditText qty, EditText unit, EditText price, EditText subtotal, EditText delivery, EditText note, CheckBox completed){this.card=card;this.line=line;this.desc=desc;this.spec=spec;this.qty=qty;this.unit=unit;this.price=price;this.subtotal=subtotal;this.delivery=delivery;this.note=note;this.completed=completed;}
    }
}
