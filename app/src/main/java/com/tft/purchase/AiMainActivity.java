package com.tft.purchase;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * V2.0.7 AI-first UI. Gemma fills purchase-order data; user only manages the manual workflow fields.
 */
public class AiMainActivity extends Activity {
    private static final int REQ_IMAGES = 301;
    private static final int REQ_NOTIFY = 302;
    private static final int REQ_BACKUP_FOLDER = 303;
    private static final int REQ_RESTORE = 304;

    private static final int HOME = 1, PURCHASES = 2, REMINDERS = 3, SETTINGS = 4, ANALYSIS = 5, DETAIL = 6;
    private static final int BG = Color.rgb(245, 248, 255);
    private static final int NAVY = Color.rgb(15, 42, 92);
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int CYAN = Color.rgb(8, 145, 178);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int RED = Color.rgb(220, 38, 38);
    private static final int ORANGE = Color.rgb(234, 88, 12);
    private static final int AMBER = Color.rgb(202, 138, 4);
    private static final int GRAY = Color.rgb(71, 85, 105);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PurchaseDbHelper db;
    private SharedPreferences prefs;
    private int screen = HOME;
    private long detailId = -1;
    private ProgressBar analysisProgress;
    private TextView analysisStage, analysisSummary;
    private EditText signatureEt, notesEt;
    private CheckBox purchaseCompleted;
    private final List<ItemCompletion> itemCompletionRows = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new PurchaseDbHelper(this);
        prefs = getSharedPreferences("tft_settings", MODE_PRIVATE);
        if (!prefs.contains("reminder_days")) prefs.edit().putInt("reminder_days", 5).apply();
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        requestNotify();
        ReminderScheduler.schedule(this);
        DriveBackupHelper.backupIfDueAsync(this);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        route(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        route(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.post(pollJob);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(pollJob);
        super.onPause();
    }

    private final Runnable pollJob = new Runnable() {
        @Override public void run() {
            if (screen == ANALYSIS) updateAnalysisUi();
            handler.postDelayed(this, 700);
        }
    };

    private void route(Intent i) {
        String target = i == null ? "" : i.getStringExtra("screen");
        if ("analysis".equals(target)) showAnalysis();
        else if ("purchases".equals(target)) showPurchases();
        else if ("reminders".equals(target)) showReminders();
        else showHome();
    }

    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack();
    }

    private void handleBack() {
        if (screen == DETAIL) { showPurchases(); return; }
        if (screen == ANALYSIS || screen == PURCHASES || screen == REMINDERS || screen == SETTINGS) { showHome(); return; }
        finish();
    }

    private void showHome() {
        screen = HOME;
        LinearLayout page = page();
        page.addView(header());

        AiJobStore.Snapshot job = AiJobStore.get(this);
        if (job.running()) page.addView(jobCard(job));
        else if (AiJobStore.STATE_DONE.equals(job.state) || AiJobStore.STATE_ERROR.equals(job.state)) page.addView(lastJobCard(job));

        page.addView(section("交貨狀態"));
        List<PurchaseDbHelper.DueItem> due = openDue();
        int overdue=0,today=0,three=0,seven=0;
        for (PurchaseDbHelper.DueItem d : due) {
            long x = diffDays(d.deliveryDate);
            if (x < 0) overdue++; else if (x == 0) today++; else if (x <= 3) three++; else if (x <= 7) seven++;
        }
        LinearLayout r1 = row(); r1.addView(status("已逾期", overdue, RED), weight()); r1.addView(status("今天交貨", today, ORANGE), weight()); page.addView(r1);
        LinearLayout r2 = row(); r2.addView(status("1–3 天", three, AMBER), weight()); r2.addView(status("4–7 天", seven, BLUE), weight()); page.addView(r2);

        Button importBtn = action("選擇採購單照片 → AI 自動填寫", BLUE);
        importBtn.setTextSize(17);
        importBtn.setEnabled(!job.running());
        importBtn.setOnClickListener(v -> openPhotoPicker());
        page.addView(importBtn, margins(8,12));
        page.addView(info("AI 自動處理", "供應廠商、地址、採購單號、採購日期、品項、數量、單位與每列交貨日都由 Gemma 自動辨識。你只需要處理簽名回傳日期、備註與完成狀態。"));

        page.addView(section("最近要交貨"));
        int shown = 0;
        for (PurchaseDbHelper.DueItem d : due) {
            if (shown >= 6) break;
            if (diffDays(d.deliveryDate) > 14) continue;
            page.addView(dueCard(d)); shown++;
        }
        if (shown == 0) page.addView(info("目前沒有急件", "14 天內沒有未完成的交貨項目。"));
        setScreen(page, HOME);
    }

    private void showAnalysis() {
        screen = ANALYSIS;
        LinearLayout page = page();
        page.addView(topBar("AI 分析進度", this::showHome));
        page.addView(info("可以放心切到背景", "Gemma 在 Android 前景服務中分析。就算你回桌面或切到別的 APP，系統通知列仍會顯示進度；不要在系統設定中按「強制停止」。"));

        LinearLayout card = card(Color.WHITE, CYAN);
        analysisStage = txt("準備中…", 18, NAVY, true); card.addView(analysisStage);
        analysisProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        analysisProgress.setMax(100);
        card.addView(analysisProgress, marginsRaw(-1, dp(22), 10, 8));
        analysisSummary = txt("", 14, GRAY, false); card.addView(analysisSummary);
        page.addView(card, margins(8,10));

        Button home = action("回首頁（AI 繼續背景分析）", NAVY); home.setOnClickListener(v -> showHome()); page.addView(home, margins(6,8));
        Button purchases = action("查看採購單", GREEN); purchases.setOnClickListener(v -> showPurchases()); page.addView(purchases, margins(4,10));
        setScreen(page, ANALYSIS);
        updateAnalysisUi();
    }

    private void updateAnalysisUi() {
        if (analysisProgress == null || analysisStage == null || analysisSummary == null) return;
        AiJobStore.Snapshot s = AiJobStore.get(this);
        analysisProgress.setProgress(s.progress);
        analysisStage.setText(s.stage == null || s.stage.isEmpty() ? "等待 AI…" : s.stage);
        if (s.running()) {
            analysisSummary.setText("進度 " + s.progress + "%　｜　成功 " + s.success + "　｜　需重試 " + s.failed + "\n目前處理第 " + Math.max(1,s.current) + " / " + Math.max(1,s.total) + " 張");
        } else if (AiJobStore.STATE_DONE.equals(s.state)) {
            analysisSummary.setText("完成 ✓　成功 " + s.success + " 張" + (s.failed > 0 ? "，另有 " + s.failed + " 張未通過可靠度檢查，沒有寫入錯誤資料。" : ""));
        } else if (AiJobStore.STATE_ERROR.equals(s.state)) {
            analysisSummary.setText("分析未完成：" + (s.error == null || s.error.isEmpty() ? s.stage : s.error));
        }
    }

    private void showPurchases() {
        screen = PURCHASES;
        LinearLayout page = page(); page.addView(topBar("採購單", this::showHome));
        Button add = action("＋ AI 分析新採購單", BLUE); add.setOnClickListener(v -> openPhotoPicker()); page.addView(add, margins(6,10));
        List<PurchaseDbHelper.Purchase> list = db.list("");
        if (list.isEmpty()) page.addView(info("還沒有採購資料", "從相簿選擇採購單，Gemma 分析通過後會自動建立。"));
        for (PurchaseDbHelper.Purchase p : list) page.addView(purchaseCard(p));
        setScreen(page, PURCHASES);
    }

    private void showReminders() {
        screen = REMINDERS;
        LinearLayout page = page(); page.addView(topBar("交貨提醒", this::showHome));
        page.addView(info("一眼看交期", "依每個品項自己的交貨日期排序。紅色先處理，完成後勾選品項完成即可停止提醒。"));
        List<PurchaseDbHelper.DueItem> due = openDue();
        if (due.isEmpty()) page.addView(info("目前沒有待交貨項目", "已完成或沒有交貨日期的項目不會出現在這裡。"));
        for (PurchaseDbHelper.DueItem d : due) page.addView(dueCard(d));
        setScreen(page, REMINDERS);
    }

    private void showSettings() {
        screen = SETTINGS;
        LinearLayout page = page(); page.addView(topBar("設定", this::showHome));
        page.addView(section("AI 辨識"));
        page.addView(info(GemmaBridge.isReady(this) ? "Gemma AI 已就緒 ✓" : "Gemma AI 尚未就緒",
                "V2.0.7 使用三階段視覺辨識：表頭 → 品項表格 → 整張稽核。OCR 不再直接填寫欄位。只有通過一致性檢查的資料才會自動寫入。"));
        if (!GemmaBridge.isReady(this)) {
            Button model = action("前往安裝 Gemma 模型", BLUE); model.setOnClickListener(v -> startActivity(new Intent(this, GemmaSetupActivity.class))); page.addView(model, margins(5,10));
        }

        page.addView(section("提醒時間"));
        int days = prefs.getInt("reminder_days",5);
        page.addView(txt("目前提前提醒：" + days + " 天；背景排程每天約 08:30 檢查。",14,GRAY,false));
        LinearLayout daysRow = row();
        for (int d : new int[]{3,5,7,14}) {
            Button b = mini(d + " 天", d == days ? BLUE : Color.rgb(226,232,240));
            if (d != days) b.setTextColor(NAVY);
            b.setOnClickListener(v -> { prefs.edit().putInt("reminder_days",d).apply(); ReminderScheduler.schedule(this); showSettings(); });
            daysRow.addView(b, weight());
        }
        page.addView(daysRow);

        page.addView(section("Google Drive 備份"));
        String tree = prefs.getString("backup_tree_uri","");
        String last = prefs.getString("last_backup_label","尚未備份");
        page.addView(info(tree.isEmpty()?"尚未設定備份資料夾":"Google Drive 備份已設定","上次備份：" + last));
        Button choose = action(tree.isEmpty()?"選擇 Google Drive 備份資料夾":"更換 Google Drive 備份資料夾",BLUE); choose.setOnClickListener(v -> chooseBackupFolder()); page.addView(choose,margins(5,6));
        Button backup = action("立即備份",GREEN); backup.setEnabled(!tree.isEmpty()); backup.setOnClickListener(v -> backupNow()); page.addView(backup,margins(4,6));
        Button restore = action("從備份 ZIP 還原",Color.rgb(124,58,237)); restore.setOnClickListener(v -> chooseRestore()); page.addView(restore,margins(4,10));
        page.addView(info("版本 2.0.7","Pixel 10 Pro XL / Android 17 優先；AI 分析可在背景持續執行。"));
        setScreen(page, SETTINGS);
    }

    private View purchaseCard(PurchaseDbHelper.Purchase p) {
        LinearLayout c = card(Color.WHITE, p.completed ? GREEN : BLUE);
        LinearLayout top = row(); top.addView(txt(safe(p.vendorName,"未辨識廠商"),17,NAVY,true),weight()); top.addView(chip(p.completed?"已完成":statusFor(p),p.completed?GREEN:colorFor(p))); c.addView(top);
        c.addView(txt("採購單號　" + safe(p.orderNo,"—") + "\n採購日期　" + safe(p.purchaseDate,"—"),14,GRAY,false));
        List<PurchaseDbHelper.PurchaseItem> items = db.listItems(p.id);
        c.addView(txt("AI 品項　" + items.size() + " 項\n最近交貨　" + safe(earliest(items),"—"),14,Color.rgb(30,41,59),true));
        c.setOnClickListener(v -> showDetail(p.id));
        return c;
    }

    private View dueCard(PurchaseDbHelper.DueItem d) {
        long diff = diffDays(d.deliveryDate); int color = diff<0?RED:diff==0?ORANGE:diff<=3?AMBER:BLUE;
        LinearLayout c = card(Color.WHITE,color);
        LinearLayout top=row(); top.addView(txt(safe(d.vendorName,"未辨識廠商"),16,NAVY,true),weight()); top.addView(chip(labelDiff(diff),color)); c.addView(top);
        c.addView(txt("交貨日　"+d.deliveryDate+"\n採購單　"+safe(d.orderNo,"—"),14,color,true));
        c.addView(txt(safe(d.description,"未辨識品項") + (empty(d.quantity)?"":"\n數量　"+d.quantity+safe(d.unit,"")),14,Color.rgb(30,41,59),false));
        c.setOnClickListener(v -> showDetail(d.purchaseId));
        return c;
    }

    private void showDetail(long id) {
        PurchaseDbHelper.Purchase p = db.get(id); if (p == null) { showPurchases(); return; }
        detailId = id; screen = DETAIL; itemCompletionRows.clear();
        LinearLayout page = page(); page.addView(topBar("採購單明細", this::showPurchases));
        page.addView(info("AI 自動填寫欄位", "下方採購資料由 Gemma 直接讀圖片產生，不需要你逐欄輸入。若內容不對，請按「重新 AI 分析」，不要手動改 AI 欄位。"));
        if (!empty(p.imagePath) && new File(p.imagePath).exists()) {
            ImageView img = new ImageView(this); img.setAdjustViewBounds(true); img.setScaleType(ImageView.ScaleType.FIT_CENTER); img.setImageBitmap(previewBitmap(p.imagePath)); img.setBackground(box(Color.WHITE,Color.rgb(148,163,184),1)); page.addView(img,marginsRaw(-1,dp(300),6,10));
        }
        page.addView(aiField("供應廠商", p.vendorName));
        page.addView(aiField("廠商地址", p.location));
        page.addView(aiField("採購單號", p.orderNo));
        page.addView(aiField("採購日期", p.purchaseDate));
        page.addView(section("AI 品項／數量／交貨日期"));
        List<PurchaseDbHelper.PurchaseItem> items = db.listItems(id);
        for (PurchaseDbHelper.PurchaseItem item : items) page.addView(itemCard(item));

        page.addView(section("你要手動管理的欄位"));
        signatureEt = input("廠務簽名回傳日期 YYYY-MM-DD"); signatureEt.setText(p.signatureReturnDate); page.addView(signatureEt,margins(4,5));
        notesEt = input("內部備註"); notesEt.setMinLines(2); notesEt.setText(p.notes); page.addView(notesEt,margins(4,5));
        purchaseCompleted = new CheckBox(this); purchaseCompleted.setText("整張採購單已完成"); purchaseCompleted.setTextColor(NAVY); purchaseCompleted.setChecked(p.completed); page.addView(purchaseCompleted,margins(3,7));
        Button save = action("儲存人工狀態",GREEN); save.setOnClickListener(v -> saveManual(p,items)); page.addView(save,margins(6,7));
        Button again = action("重新 AI 分析這張採購單",CYAN); again.setOnClickListener(v -> reanalyze(p)); page.addView(again,margins(4,7));
        Button del = action("刪除這張採購單",RED); del.setOnClickListener(v -> confirmDelete(p)); page.addView(del,margins(4,14));
        setEditorScreen(page);
    }

    private View itemCard(PurchaseDbHelper.PurchaseItem item) {
        LinearLayout c = card(Color.WHITE,Color.rgb(148,163,184));
        c.addView(txt(item.lineNo + "　" + safe(item.description,"未辨識品項"),16,NAVY,true));
        String line = "數量　"+safe(item.quantity,"—")+safe(item.unit,"")+"　　單價　"+safe(item.unitPrice,"—")+"\n小計　"+safe(item.subtotal,"—")+"　　交貨日　"+safe(item.deliveryDate,"—");
        c.addView(txt(line,14,Color.rgb(30,41,59),false));
        if (!empty(item.note)) c.addView(txt("備註　"+item.note,13,GRAY,false));
        CheckBox cb = new CheckBox(this); cb.setText("此品項已完成"); cb.setTextColor(GRAY); cb.setChecked(item.completed); c.addView(cb);
        itemCompletionRows.add(new ItemCompletion(item,cb));
        return c;
    }

    private void saveManual(PurchaseDbHelper.Purchase p, List<PurchaseDbHelper.PurchaseItem> items) {
        p.signatureReturnDate = normalizeDate(signatureEt.getText().toString());
        p.notes = notesEt.getText().toString().trim();
        p.completed = purchaseCompleted.isChecked();
        db.update(p);
        for (ItemCompletion r : itemCompletionRows) r.item.completed = r.check.isChecked();
        db.replaceItems(p.id, items);
        toast("已儲存人工狀態");
        showDetail(p.id);
    }

    private void reanalyze(PurchaseDbHelper.Purchase p) {
        if (empty(p.imagePath) || !new File(p.imagePath).exists()) { toast("找不到原始照片"); return; }
        if (AiJobStore.get(this).running()) { toast("目前已有 AI 分析工作，請先等它完成"); return; }
        ArrayList<String> paths = new ArrayList<>(); paths.add(p.imagePath);
        AiAnalysisService.start(this,paths,p.id);
        showAnalysis();
    }

    private void confirmDelete(PurchaseDbHelper.Purchase p) {
        new AlertDialog.Builder(this).setTitle("刪除採購單？").setMessage("會刪除這筆資料；原始照片也會一併移除。")
                .setNegativeButton("取消",null).setPositiveButton("刪除",(d,w)->{ try { if(!empty(p.imagePath)) new File(p.imagePath).delete(); }catch(Exception ignored){} db.delete(p.id); showPurchases(); }).show();
    }

    private View aiField(String label, String value) {
        LinearLayout c=card(Color.WHITE,Color.rgb(191,219,254)); c.addView(txt(label+"　AI",12,BLUE,true)); c.addView(txt(safe(value,"AI 未可靠辨識"),17,NAVY,true)); return c;
    }

    private void openPhotoPicker() {
        if (AiJobStore.get(this).running()) { showAnalysis(); return; }
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("image/*");
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(20, MediaStore.getPickImagesMaxLimit()));
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.addCategory(Intent.CATEGORY_OPENABLE); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        }
        startActivityForResult(i,REQ_IMAGES);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null)return;
        try {
            if(requestCode==REQ_IMAGES){
                ArrayList<Uri> uris=new ArrayList<>(); ClipData clip=data.getClipData();
                if(clip!=null)for(int x=0;x<clip.getItemCount();x++)uris.add(clip.getItemAt(x).getUri()); else if(data.getData()!=null)uris.add(data.getData());
                if(uris.isEmpty())return;
                ArrayList<String> paths=new ArrayList<>(); for(Uri u:uris)paths.add(copyImage(u));
                AiAnalysisService.start(this,paths,-1); showAnalysis();
            } else if(requestCode==REQ_BACKUP_FOLDER&&data.getData()!=null){
                Uri tree=data.getData(); int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try{getContentResolver().takePersistableUriPermission(tree,flags);}catch(Exception ignored){}
                prefs.edit().putString("backup_tree_uri",tree.toString()).apply(); backupNow();
            } else if(requestCode==REQ_RESTORE&&data.getData()!=null){
                Uri u=data.getData(); new AlertDialog.Builder(this).setTitle("從備份還原？").setMessage("目前 APP 資料會被備份內容取代。")
                        .setNegativeButton("取消",null).setPositiveButton("還原",(d,w)->restore(u)).show();
            }
        } catch(Exception e){toast("操作失敗："+e.getMessage());}
    }

    private String copyImage(Uri uri) throws Exception {
        File dir=new File(getFilesDir(),"purchase_images"); if(!dir.exists())dir.mkdirs(); File out=new File(dir,UUID.randomUUID()+".jpg");
        try(InputStream in=getContentResolver().openInputStream(uri); FileOutputStream fos=new FileOutputStream(out)){
            if(in==null)throw new Exception("無法讀取圖片"); byte[] buf=new byte[16384];int n;while((n=in.read(buf))>0)fos.write(buf,0,n);
        }
        return out.getAbsolutePath();
    }

    private void chooseBackupFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);startActivityForResult(i,REQ_BACKUP_FOLDER);}
    private void backupNow(){toast("正在備份…");new Thread(()->{try{String n=DriveBackupHelper.backupNow(this);runOnUiThread(()->{toast("備份完成："+n);if(screen==SETTINGS)showSettings();});}catch(Exception e){runOnUiThread(()->toast("備份失敗："+e.getMessage()));}}).start();}
    private void chooseRestore(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,REQ_RESTORE);}
    private void restore(Uri u){new Thread(()->{try{int n=DriveBackupHelper.restore(this,u);runOnUiThread(()->{toast("還原完成，共 "+n+" 張");showHome();});}catch(Exception e){runOnUiThread(()->toast("還原失敗："+e.getMessage()));}}).start();}

    private List<PurchaseDbHelper.DueItem> openDue(){List<PurchaseDbHelper.DueItem>o=new ArrayList<>();for(PurchaseDbHelper.DueItem d:db.listDueItems())if(!d.purchaseCompleted&&!d.itemCompleted&&!empty(d.deliveryDate))o.add(d);Collections.sort(o,Comparator.comparing(a->a.deliveryDate));return o;}
    private long diffDays(String s){try{SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.TAIWAN);Date a=f.parse(f.format(new Date())),b=f.parse(s);return a==null||b==null?99999:TimeUnit.MILLISECONDS.toDays(b.getTime()-a.getTime());}catch(Exception e){return 99999;}}
    private String labelDiff(long d){if(d<0)return"逾期 "+(-d)+" 天";if(d==0)return"今天交貨";return d+" 天後";}
    private String statusFor(PurchaseDbHelper.Purchase p){String e=earliest(db.listItems(p.id));if(empty(e))return"未設定交貨日";return labelDiff(diffDays(e));}
    private int colorFor(PurchaseDbHelper.Purchase p){String s=statusFor(p);if(s.startsWith("逾期"))return RED;if(s.startsWith("今天"))return ORANGE;if(s.contains("天後"))return AMBER;return BLUE;}
    private String earliest(List<PurchaseDbHelper.PurchaseItem>xs){String e="";for(PurchaseDbHelper.PurchaseItem i:xs)if(!empty(i.deliveryDate)&&(e.isEmpty()||i.deliveryDate.compareTo(e)<0))e=i.deliveryDate;return e;}
    private String normalizeDate(String s){if(empty(s))return"";String d=PurchaseOcrParser.extractDate(s);return empty(d)?s.trim():d;}

    private View header(){LinearLayout c=card(Color.rgb(219,234,254),BLUE);LinearLayout r=row();ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_app_icon);r.addView(icon,new LinearLayout.LayoutParams(dp(64),dp(64)));LinearLayout t=new LinearLayout(this);t.setOrientation(LinearLayout.VERTICAL);t.setPadding(dp(12),0,0,0);t.addView(txt("全益採購追蹤",25,NAVY,true));t.addView(txt("GEMMA AI 自動填寫｜背景分析｜交貨提醒",13,GRAY,true));r.addView(t,weight());c.addView(r);return c;}
    private View jobCard(AiJobStore.Snapshot s){LinearLayout c=card(Color.WHITE,CYAN);c.addView(txt("Gemma AI 正在背景分析",17,NAVY,true));ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setMax(100);p.setProgress(s.progress);c.addView(p,marginsRaw(-1,dp(18),7,6));c.addView(txt(s.stage+"　"+s.progress+"%",14,GRAY,false));c.setOnClickListener(v->showAnalysis());return c;}
    private View lastJobCard(AiJobStore.Snapshot s){String title=AiJobStore.STATE_DONE.equals(s.state)?"上次 AI 分析完成":"上次 AI 分析需處理";LinearLayout c=card(Color.WHITE,s.failed>0?AMBER:GREEN);c.addView(txt(title,16,NAVY,true));c.addView(txt("成功 "+s.success+" 張｜未通過 "+s.failed+" 張",14,GRAY,false));c.setOnClickListener(v->showAnalysis());return c;}
    private View status(String label,int count,int color){LinearLayout c=card(color,Color.rgb(15,23,42));c.addView(txt(label,14,Color.WHITE,true));c.addView(txt(String.valueOf(count),30,Color.WHITE,true));c.setOnClickListener(v->showReminders());return c;}
    private View info(String title,String body){LinearLayout c=card(Color.WHITE,Color.rgb(203,213,225));c.addView(txt(title,16,NAVY,true));c.addView(txt(body,14,GRAY,false));return c;}
    private TextView section(String s){TextView v=txt(s,17,NAVY,true);v.setPadding(dp(2),dp(16),dp(2),dp(8));return v;}
    private View topBar(String title,Runnable back){LinearLayout r=row();Button b=mini("返回",Color.rgb(226,232,240));b.setTextColor(NAVY);b.setOnClickListener(v->back.run());r.addView(b);TextView t=txt(title,22,NAVY,true);t.setPadding(dp(12),dp(5),0,dp(5));r.addView(t,weight());return r;}
    private View chip(String s,int color){TextView v=txt(s,12,Color.WHITE,true);v.setPadding(dp(8),dp(5),dp(8),dp(5));v.setGravity(Gravity.CENTER);v.setBackground(box(color,Color.rgb(15,23,42),1));return v;}
    private LinearLayout card(int fill,int stroke){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(13),dp(11),dp(13),dp(11));c.setBackground(box(fill,stroke,2));return c;}
    private GradientDrawable box(int fill,int stroke,int width){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(width),stroke);g.setCornerRadius(dp(5));return g;}
    private Button action(String s,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD);b.setBackground(box(color,Color.rgb(15,23,42),2));b.setPadding(dp(12),dp(11),dp(12),dp(11));return b;}
    private Button mini(String s,int color){Button b=action(s,color);b.setTextSize(13);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(10),dp(7),dp(10),dp(7));return b;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setTextColor(Color.rgb(15,23,42));e.setHintTextColor(Color.rgb(100,116,139));e.setPadding(dp(11),dp(10),dp(11),dp(10));e.setBackground(box(Color.WHITE,Color.rgb(148,163,184),1));return e;}
    private TextView txt(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(2),dp(3),dp(2),dp(3));return v;}
    private LinearLayout page(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(14),dp(12),dp(14),dp(26));p.setBackgroundColor(BG);return p;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),dp(3),dp(3),dp(3));return p;}
    private LinearLayout.LayoutParams margins(int top,int bottom){return marginsRaw(-1,-2,top,bottom);}
    private LinearLayout.LayoutParams marginsRaw(int w,int h,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(2),dp(top),dp(2),dp(bottom));return p;}

    private void setScreen(LinearLayout page,int selected){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));if(selected!=ANALYSIS)shell.addView(nav(selected),new LinearLayout.LayoutParams(-1,-2));applyInsets(shell);setContentView(shell);}
    private void setEditorScreen(LinearLayout page){ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);scroll.setBackgroundColor(BG);applyInsets(scroll);setContentView(scroll);}
    private View nav(int selected){LinearLayout n=row();n.setPadding(dp(5),dp(5),dp(5),dp(7));n.setBackground(box(Color.WHITE,Color.rgb(148,163,184),1));String[] names={"首頁","採購單","提醒","設定"};int[] ids={HOME,PURCHASES,REMINDERS,SETTINGS};for(int x=0;x<ids.length;x++){Button b=new Button(this);b.setAllCaps(false);b.setText(names[x]);b.setTextSize(13);b.setTextColor(ids[x]==selected?BLUE:GRAY);b.setTypeface(Typeface.DEFAULT,ids[x]==selected?Typeface.BOLD:Typeface.NORMAL);b.setBackgroundColor(Color.TRANSPARENT);final int id=ids[x];b.setOnClickListener(v->{if(id==HOME)showHome();else if(id==PURCHASES)showPurchases();else if(id==REMINDERS)showReminders();else showSettings();});n.addView(b,new LinearLayout.LayoutParams(0,dp(54),1));}return n;}
    private void applyInsets(View root){if(Build.VERSION.SDK_INT>=21)root.setOnApplyWindowInsetsListener((v,insets)->{int top,bottom;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets b=insets.getInsets(WindowInsets.Type.systemBars());top=b.top;bottom=b.bottom;}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(0,top,0,bottom);return insets;});}

    private Bitmap previewBitmap(String path){BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(path,o);int sample=1;while(o.outWidth/sample>1600||o.outHeight/sample>1800)sample*=2;o.inJustDecodeBounds=false;o.inSampleSize=sample;return BitmapFactory.decodeFile(path,o);}
    private void requestNotify(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);}
    private boolean empty(String s){return s==null||s.trim().isEmpty();}
    private String safe(String s,String f){return empty(s)?f:s;}
    private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}

    private static class ItemCompletion { PurchaseDbHelper.PurchaseItem item; CheckBox check; ItemCompletion(PurchaseDbHelper.PurchaseItem i,CheckBox c){item=i;check=c;} }
}
