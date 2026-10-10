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
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TftActivityV204 extends Activity {
    private static final int REQ_PICK_IMAGES = 101, REQ_NOTIFY = 103, REQ_CREATE_BACKUP = 201, REQ_RESTORE = 202;
    private static final int HOME=1, PURCHASES=2, REMINDERS=3, SETTINGS_SCREEN=4, QUEUE=5, EDITOR=6;
    private static final int BLUE=Color.rgb(37,99,235), NAVY=Color.rgb(15,42,92), CYAN=Color.rgb(8,145,178), RED=Color.rgb(220,38,38), ORANGE=Color.rgb(234,88,12), AMBER=Color.rgb(217,119,6), GREEN=Color.rgb(22,163,74), PURPLE=Color.rgb(124,58,237), GRAY=Color.rgb(71,85,105);

    private PurchaseDbHelper db;
    private SharedPreferences prefs;
    private int currentScreen = HOME;
    private long editingId = -1;
    private Draft editingDraft;
    private String currentImagePath = "";
    private EditText vendorEt, locationEt, orderNoEt, purchaseEt, signatureEt, notesEt;
    private CheckBox completedCb;
    private ImageView preview;
    private TextView ocrTv;
    private LinearLayout itemsContainer;
    private ScrollView editorScroll;
    private final List<ItemRow> itemRows = new ArrayList<>();
    private final List<Draft> pendingDrafts = new ArrayList<>();
    private OnBackInvokedCallback systemBackCallback;
    private boolean systemBackRegistered;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        db = new PurchaseDbHelper(this);
        prefs = getSharedPreferences("tft_settings", MODE_PRIVATE);
        if (!prefs.contains("reminder_days")) prefs.edit().putInt("reminder_days",5).apply();
        if (Build.VERSION.SDK_INT >= 33) systemBackCallback = this::navigateBack;
        requestNotificationPermission();
        ReminderWorkScheduler.schedule(this);
        String open = getIntent()==null?"":getIntent().getStringExtra("screen");
        if ("reminders".equals(open)) showReminders(); else showHome();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (intent!=null && "reminders".equals(intent.getStringExtra("screen"))) showReminders();
    }

    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) { super.onBackPressed(); return; }
        if (currentScreen == HOME) super.onBackPressed(); else navigateBack();
    }

    private void updateSystemBack() {
        if (Build.VERSION.SDK_INT < 33 || systemBackCallback == null) return;
        boolean intercept = currentScreen != HOME;
        try {
            if (intercept && !systemBackRegistered) { getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemBackCallback); systemBackRegistered=true; }
            else if (!intercept && systemBackRegistered) { getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(systemBackCallback); systemBackRegistered=false; }
        } catch (Throwable ignored) {}
    }

    private void navigateBack() {
        if (currentScreen == EDITOR) {
            new AlertDialog.Builder(this).setTitle("離開目前畫面？").setMessage("尚未儲存的修改會消失。")
                    .setNegativeButton("繼續編輯",null).setPositiveButton("離開",(d,w)->{ if(!pendingDrafts.isEmpty()) showQueue(); else showPurchases(""); }).show();
        } else if (currentScreen==QUEUE || currentScreen==PURCHASES || currentScreen==REMINDERS || currentScreen==SETTINGS_SCREEN) showHome();
    }

    private void showHome() {
        currentScreen=HOME;
        LinearLayout page=pageColumn(); page.addView(appHeader("全益採購追蹤","PIXEL DELIVERY RADAR")); page.addView(section("交貨狀態｜一眼看懂"));
        int[] c=dueCounts(); LinearLayout a=row(); a.addView(statusCard("☠","已逾期",c[0],RED),weight()); a.addView(statusCard("!","今天交貨",c[1],ORANGE),weight()); page.addView(a);
        LinearLayout b=row(); b.addView(statusCard("★","1–3天",c[2],AMBER),weight()); b.addView(statusCard("◆","4–7天",c[3],BLUE),weight()); page.addView(b);
        Button imp=gameButton("＋ 從相簿／Google 相簿匯入",BLUE); imp.setTextSize(18); imp.setOnClickListener(v->openImagePicker()); page.addView(imp,margins(-1,-2,10,8));
        if(!pendingDrafts.isEmpty()){ Button q=gameButton("待確認清單  "+pendingDrafts.size()+" 張",CYAN); q.setOnClickListener(v->showQueue()); page.addView(q,margins(-1,-2,4,10)); }
        page.addView(section("最近要交貨")); int shown=0; for(PurchaseDbHelper.DueItem d:openDueItems()){ if(shown>=8)break; if(diffDays(d.deliveryDate)>14)continue; page.addView(dueCard(d,true)); shown++; }
        if(shown==0) page.addView(infoCard("目前 14 天內沒有未完成交貨項目。","新的採購單匯入後會自動出現在這裡。"));
        setMainScreen(page,HOME);
    }

    private void showPurchases(String search){
        currentScreen=PURCHASES; LinearLayout page=pageColumn(); page.addView(topBar("採購單",this::showHome));
        LinearLayout r=row(); EditText q=input("搜尋廠商／採購單號／品項"); q.setText(search==null?"":search); r.addView(q,weight()); Button find=miniButton("搜尋",BLUE); find.setOnClickListener(v->showPurchases(q.getText().toString())); r.addView(find); page.addView(r,margins(-1,-2,4,8));
        Button add=gameButton("＋ 相簿匯入",BLUE); add.setOnClickListener(v->openImagePicker()); page.addView(add,margins(-1,-2,4,10));
        List<PurchaseDbHelper.Purchase> list=db.list(search==null?"":search); if(list.isEmpty()) page.addView(infoCard("目前沒有採購資料","匯入採購單後會顯示在這裡。")); for(PurchaseDbHelper.Purchase p:list) page.addView(purchaseCard(p)); setMainScreen(page,PURCHASES);
    }

    private void showReminders(){
        currentScreen=REMINDERS; LinearLayout page=pageColumn(); page.addView(topBar("交貨提醒",this::showHome)); page.addView(text("依交貨日期由近到遠。紅色先處理，橘色代表今天。",14,GRAY));
        int[] c=dueCounts(); LinearLayout a=row(); a.addView(statusCard("☠","已逾期",c[0],RED),weight()); a.addView(statusCard("!","今天",c[1],ORANGE),weight()); page.addView(a); LinearLayout b=row(); b.addView(statusCard("★","1–3天",c[2],AMBER),weight()); b.addView(statusCard("◆","4–7天",c[3],BLUE),weight()); page.addView(b);
        page.addView(section("廠商／品項交貨清單")); List<PurchaseDbHelper.DueItem> due=openDueItems(); if(due.isEmpty()) page.addView(infoCard("目前沒有未完成交貨項目","完成的品項不會再提醒。")); for(PurchaseDbHelper.DueItem d:due) page.addView(dueCard(d,false)); setMainScreen(page,REMINDERS);
    }

    private void showSettings(){
        currentScreen=SETTINGS_SCREEN; LinearLayout page=pageColumn(); page.addView(topBar("設定",this::showHome));
        page.addView(section("背景提醒")); int days=prefs.getInt("reminder_days",5); page.addView(infoCard("背景提醒已啟用","使用 Android WorkManager。關閉 APP 或從最近使用清單滑掉後仍保留排程，每日約 08:30 檢查交貨。若在系統設定按『強制停止』，需重新開啟 APP 才恢復。"));
        LinearLayout dr=row(); for(int d:new int[]{3,5,7,14}){ Button x=miniButton(d+"天",d==days?BLUE:Color.rgb(226,232,240)); if(d!=days)x.setTextColor(NAVY); final int value=d; x.setOnClickListener(v->{prefs.edit().putInt("reminder_days",value).apply(); ReminderWorkScheduler.schedule(this); showSettings();}); dr.addView(x,weight()); } page.addView(dr); Button test=gameButton("立即測試提醒",ORANGE); test.setOnClickListener(v->{ReminderWorkScheduler.runNow(this);toast("已送出背景提醒測試");}); page.addView(test,margins(-1,-2,6,12));
        page.addView(section("Google Drive 雲端備份")); boolean configured=BackupFileHelper.isConfigured(this); String last=prefs.getString("last_backup_label","尚未備份"); page.addView(infoCard(configured?"Google Drive 備份已設定":"尚未設定雲端備份","按『設定備份位置』後選 Google Drive → 進入資料夾 → 儲存固定備份 ZIP。\n上次備份："+last)); Button choose=gameButton(configured?"更換 Google Drive 備份位置":"設定 Google Drive 備份位置",BLUE); choose.setOnClickListener(v->createBackupDocument()); page.addView(choose,margins(-1,-2,6,6)); Button backup=gameButton("立即備份",GREEN); backup.setEnabled(configured); backup.setOnClickListener(v->backupNow()); page.addView(backup,margins(-1,-2,4,6)); Button restore=gameButton("從備份 ZIP 還原",PURPLE); restore.setOnClickListener(v->chooseRestoreZip()); page.addView(restore,margins(-1,-2,4,12));
        page.addView(section("相簿／Google 相簿")); page.addView(infoCard("使用 Android 系統 Photo Picker","Android 13 以上會使用系統相片選擇器，能顯示手機照片與已設定的雲端相片來源。Pixel 可在系統相片選擇器設定中選擇 Google 相簿。")); if(Build.VERSION.SDK_INT>=33){ Button cloud=gameButton("開啟 Google 相簿／雲端相片設定",CYAN); cloud.setOnClickListener(v->{ try{startActivity(new Intent(MediaStore.ACTION_PICK_IMAGES_SETTINGS));}catch(Exception e){toast("此系統沒有提供雲端相片設定頁");}}); page.addView(cloud,margins(-1,-2,5,12)); }
        page.addView(section("OCR 辨識")); page.addView(infoCard("V2.0.4 雙路文件辨識","同一張採購單會跑『原圖』與『高對比文件版』兩次中文 OCR，再比較並合併結果。採購單號會額外修正 O/0、I/1、S/5、B/8 等常見誤判；品項、數量、日期仍會先帶入供你確認。"));
        setMainScreen(page,SETTINGS_SCREEN);
    }

    private void showQueue(){
        currentScreen=QUEUE; LinearLayout page=pageColumn(); page.addView(topBar("待確認清單",this::showHome)); page.addView(text("OCR 先幫你帶入，確認後才正式儲存。放錯照片可以直接移除。",14,GRAY));
        if(!pendingDrafts.isEmpty()){ Button clear=miniButton("全部清除待確認",RED); clear.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("清除全部待確認？").setMessage("只會刪除尚未儲存的匯入照片。") .setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{for(Draft x:new ArrayList<>(pendingDrafts))deleteDraftFile(x);pendingDrafts.clear();showQueue();}).show()); page.addView(clear,margins(-1,-2,4,8)); }
        if(pendingDrafts.isEmpty()) page.addView(infoCard("沒有待確認照片","可以從相簿一次選多張採購單。")); int idx=1; for(Draft d:new ArrayList<>(pendingDrafts)){ LinearLayout card=pixelCard(Color.WHITE,d.warning?ORANGE:CYAN); card.addView(text("待確認 "+idx+" / "+pendingDrafts.size(),12,GRAY)); card.addView(text(safe(d.vendor,"未辨識廠商"),18,NAVY,true)); card.addView(text("採購單號："+safe(d.orderNo,"待確認")+"\n採購日："+safe(d.purchaseDate,"待確認")+"\n辨識明細："+d.items.size()+" 項"+(d.warning?"\n⚠ 有欄位需要人工確認":""),14,d.warning?ORANGE:GRAY)); LinearLayout acts=row(); Button review=miniButton("檢查並儲存",GREEN); review.setOnClickListener(v->showEditor(null,d)); acts.addView(review,weight()); Button remove=miniButton("移除此張",RED); remove.setOnClickListener(v->{deleteDraftFile(d);pendingDrafts.remove(d);showQueue();}); acts.addView(remove,weight()); card.addView(acts); page.addView(card,margins(-1,-2,4,8)); idx++; }
        Button more=gameButton("＋ 再從相簿匯入",BLUE); more.setOnClickListener(v->openImagePicker()); page.addView(more,margins(-1,-2,8,12)); setMainScreen(page,QUEUE);
    }

    private View purchaseCard(PurchaseDbHelper.Purchase p){ int color=p.completed?GREEN:statusColorForPurchase(p); LinearLayout card=pixelCard(Color.WHITE,color); LinearLayout top=row(); top.addView(text(safe(p.vendorName,"未填廠商"),17,NAVY,true),weight()); top.addView(chip(p.completed?"已完成":purchaseStatusLabel(p),color)); card.addView(top); List<PurchaseDbHelper.PurchaseItem> items=db.listItems(p.id); card.addView(text("採購單："+safe(p.orderNo,"未填")+"\n採購日："+safe(p.purchaseDate,"未填")+"\n品項："+(items.isEmpty()?safe(p.legacyItems,"未填"):items.size()+" 項")+"\n最近交貨："+safe(earliestDelivery(items,p.legacyDeliveryDate),"未設定"),14,GRAY)); LinearLayout actions=row(); Button open=miniButton("查看／編輯",BLUE); open.setOnClickListener(v->showEditor(p,null)); actions.addView(open,weight()); Button del=miniButton("刪除",RED); del.setOnClickListener(v->confirmDeletePurchase(p.id,PURCHASES)); actions.addView(del,weight()); card.addView(actions); return card; }

    private View dueCard(PurchaseDbHelper.DueItem d,boolean compact){ long diff=diffDays(d.deliveryDate); int color=diff<0?RED:diff==0?ORANGE:diff<=3?AMBER:BLUE; LinearLayout card=pixelCard(Color.WHITE,color); LinearLayout top=row(); top.addView(text(safe(d.vendorName,"未填廠商"),16,NAVY,true),weight()); top.addView(chip(statusLabel(diff),color)); card.addView(top); card.addView(text("交貨日："+d.deliveryDate+"　採購單："+safe(d.orderNo,"未填"),14,color,true)); card.addView(text(safe(d.description,"未填品項")+(empty(d.quantity)?"":"\n數量："+d.quantity+safe(d.unit,"")),14,GRAY)); if(!compact){ LinearLayout actions=row(); Button open=miniButton("查看採購單",BLUE); open.setOnClickListener(v->{PurchaseDbHelper.Purchase p=db.get(d.purchaseId);if(p!=null)showEditor(p,null);}); actions.addView(open,weight()); Button del=miniButton("刪除採購單",RED); del.setOnClickListener(v->confirmDeletePurchase(d.purchaseId,REMINDERS)); actions.addView(del,weight()); card.addView(actions);} else card.setOnClickListener(v->{PurchaseDbHelper.Purchase p=db.get(d.purchaseId);if(p!=null)showEditor(p,null);}); return card; }

    private void showEditor(PurchaseDbHelper.Purchase p,Draft draft){
        currentScreen=EDITOR; editingId=p==null?-1:p.id; editingDraft=draft; currentImagePath=p!=null?safe(p.imagePath,""):draft!=null?safe(draft.imagePath,""):""; itemRows.clear();
        LinearLayout page=pageColumn(); page.addView(topBar(p==null?"確認採購單":"採購單明細",this::navigateBack)); page.addView(text("請確認『採購單號、品項、數量、交貨日期』。",14,RED,true)); preview=new ImageView(this); preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.setBackground(pixelBackground(Color.rgb(241,245,249),Color.rgb(148,163,184),2)); page.addView(preview,margins(-1,dp(260),4,8)); refreshPreview();
        vendorEt=input("供應廠商"); locationEt=input("廠商地址"); orderNoEt=input("採購單號"); purchaseEt=input("採購日期 YYYY-MM-DD"); signatureEt=input("廠務簽名回傳日期 YYYY-MM-DD（人工）"); notesEt=input("整張採購單備註"); notesEt.setMinLines(2); completedCb=new CheckBox(this); completedCb.setText("整張採購單已完成"); completedCb.setTextColor(NAVY); for(View v:new View[]{vendorEt,locationEt,orderNoEt,purchaseEt,signatureEt,notesEt,completedCb}) page.addView(v,margins(-1,-2,3,5));
        if(p!=null){vendorEt.setText(p.vendorName);locationEt.setText(p.location);orderNoEt.setText(p.orderNo);purchaseEt.setText(p.purchaseDate);signatureEt.setText(p.signatureReturnDate);notesEt.setText(p.notes);completedCb.setChecked(p.completed);} else if(draft!=null){vendorEt.setText(draft.vendor);locationEt.setText(draft.location);orderNoEt.setText(draft.orderNo);purchaseEt.setText(draft.purchaseDate);}
        page.addView(section("品項／數量／交貨日期")); itemsContainer=new LinearLayout(this); itemsContainer.setOrientation(LinearLayout.VERTICAL); page.addView(itemsContainer); List<PurchaseDbHelper.PurchaseItem> initial=new ArrayList<>(); if(p!=null)initial.addAll(db.listItems(p.id)); if(draft!=null)initial.addAll(draft.items); if(p!=null&&initial.isEmpty()&&(!empty(p.legacyItems)||!empty(p.legacyDeliveryDate))){PurchaseDbHelper.PurchaseItem i=new PurchaseDbHelper.PurchaseItem();i.lineNo="001";i.description=p.legacyItems;i.deliveryDate=p.legacyDeliveryDate;initial.add(i);} if(initial.isEmpty())initial.add(new PurchaseDbHelper.PurchaseItem()); for(PurchaseDbHelper.PurchaseItem i:initial)addItemRow(i);
        Button addItem=miniButton("＋ 新增品項",BLUE); addItem.setOnClickListener(v->addItemRow(new PurchaseDbHelper.PurchaseItem())); page.addView(addItem,margins(-1,-2,6,8)); ocrTv=text("",11,GRAY); ocrTv.setVisibility(View.GONE); if(p!=null)ocrTv.setText(p.rawOcr);else if(draft!=null)ocrTv.setText(draft.rawOcr);page.addView(ocrTv); Button save=gameButton("✓ 確認儲存",GREEN);save.setOnClickListener(v->savePurchase());page.addView(save,margins(-1,-2,8,6)); if(draft!=null){Button remove=gameButton("移除這張匯入照片",RED);remove.setOnClickListener(v->{deleteDraftFile(draft);pendingDrafts.remove(draft);showQueue();});page.addView(remove,margins(-1,-2,4,8));} if(p!=null){Button del=gameButton("刪除這張採購單",RED);del.setOnClickListener(v->confirmDeletePurchase(p.id,PURCHASES));page.addView(del,margins(-1,-2,4,12));} setEditorScreen(page);
    }

    private void addItemRow(PurchaseDbHelper.PurchaseItem item){ ItemRow r=new ItemRow(); LinearLayout card=pixelCard(Color.rgb(248,250,252),Color.rgb(148,163,184)); r.root=card; LinearLayout head=row(); r.lineNo=input("項次");r.lineNo.setText(item.lineNo);head.addView(r.lineNo,new LinearLayout.LayoutParams(dp(76),-2));head.addView(text("品項明細",14,NAVY,true),weight());Button del=miniButton("移除品項",Color.rgb(226,232,240));del.setTextColor(RED);head.addView(del);card.addView(head);r.description=input("品名規格明細");r.description.setMinLines(2);r.description.setText(item.description);card.addView(r.description,margins(-1,-2,2,4));LinearLayout one=row();r.qty=smallInput("數量",item.quantity);one.addView(r.qty,weight());r.unit=smallInput("單位",item.unit);one.addView(r.unit,weight());r.unitPrice=smallInput("單價",item.unitPrice);one.addView(r.unitPrice,weight());card.addView(one);LinearLayout two=row();r.subtotal=smallInput("小計",item.subtotal);two.addView(r.subtotal,weight());r.delivery=smallInput("交貨日 YYYY-MM-DD",item.deliveryDate);two.addView(r.delivery,new LinearLayout.LayoutParams(0,-2,2));card.addView(two);r.note=input("備註");r.note.setText(item.note);card.addView(r.note);r.completed=new CheckBox(this);r.completed.setText("此品項已完成");r.completed.setChecked(item.completed);card.addView(r.completed);del.setOnClickListener(v->{if(itemRows.size()<=1){toast("至少保留一個品項");return;}itemRows.remove(r);itemsContainer.removeView(card);});itemRows.add(r);itemsContainer.addView(card,margins(-1,-2,4,8)); }
    private EditText smallInput(String hint,String value){EditText e=input(hint);e.setText(value);e.setTextSize(13);return e;}

    private void savePurchase(){ PurchaseDbHelper.Purchase p=editingId>0?db.get(editingId):new PurchaseDbHelper.Purchase();if(p==null)p=new PurchaseDbHelper.Purchase();p.vendorName=trim(vendorEt);p.location=trim(locationEt);p.orderNo=trim(orderNoEt);p.purchaseDate=normalizeDate(trim(purchaseEt));p.signatureReturnDate=normalizeDate(trim(signatureEt));p.notes=trim(notesEt);p.completed=completedCb.isChecked();p.imagePath=currentImagePath;p.rawOcr=ocrTv==null?"":String.valueOf(ocrTv.getText());p.createdAt=p.createdAt==0?System.currentTimeMillis():p.createdAt;if(editingId>0)db.update(p);else{editingId=db.insert(p);p.id=editingId;}List<PurchaseDbHelper.PurchaseItem> items=new ArrayList<>();for(ItemRow r:itemRows){PurchaseDbHelper.PurchaseItem i=new PurchaseDbHelper.PurchaseItem();i.lineNo=trim(r.lineNo);i.description=trim(r.description);i.quantity=trim(r.qty);i.unit=trim(r.unit);i.unitPrice=trim(r.unitPrice);i.subtotal=trim(r.subtotal);i.deliveryDate=normalizeDate(trim(r.delivery));i.note=trim(r.note);i.completed=r.completed.isChecked();if(empty(i.description)&&empty(i.deliveryDate)&&empty(i.quantity))continue;items.add(i);}db.replaceItems(editingId,items);if(editingDraft!=null)pendingDrafts.remove(editingDraft);editingDraft=null;ReminderWorkScheduler.runNow(this);autoBackupSoon();toast("已儲存採購單");if(!pendingDrafts.isEmpty())showQueue();else showPurchases(""); }

    private void confirmDeletePurchase(long id,int returnScreen){ PurchaseDbHelper.Purchase p=db.get(id);if(p==null)return;new AlertDialog.Builder(this).setTitle("刪除採購單？").setMessage("將刪除『"+safe(p.vendorName,"未填廠商")+"』這張採購單與本機照片。") .setNegativeButton("取消",null).setPositiveButton("確定刪除",(d,w)->{if(!empty(p.imagePath)){try{new File(p.imagePath).delete();}catch(Exception ignored){}}db.delete(id);ReminderWorkScheduler.runNow(this);autoBackupSoon();toast("已刪除");if(returnScreen==REMINDERS)showReminders();else showPurchases("");}).show(); }

    private void openImagePicker(){
        try{
            Intent i;
            if(Build.VERSION.SDK_INT>=33){ i=new Intent(MediaStore.ACTION_PICK_IMAGES); i.setType("image/*"); int max=Math.min(20,MediaStore.getPickImagesMaxLimit()); if(max>1)i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,max); }
            else { i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); }
            startActivityForResult(i,REQ_PICK_IMAGES);
        }catch(Exception e){ Intent fallback=new Intent(Intent.ACTION_OPEN_DOCUMENT);fallback.setType("image/*");fallback.addCategory(Intent.CATEGORY_OPENABLE);fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(fallback,REQ_PICK_IMAGES); }
    }

    private void createBackupDocument(){Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,"全益採購追蹤_自動備份.zip");i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,REQ_CREATE_BACKUP);}
    private void chooseRestoreZip(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/zip");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,REQ_RESTORE);}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(resultCode!=RESULT_OK||data==null)return;if(requestCode==REQ_PICK_IMAGES){List<Uri> uris=new ArrayList<>();ClipData clip=data.getClipData();if(clip!=null)for(int x=0;x<clip.getItemCount();x++)uris.add(clip.getItemAt(x).getUri());else if(data.getData()!=null)uris.add(data.getData());if(uris.isEmpty())return;toast("已選 "+uris.size()+" 張，開始雙路 OCR…");List<String> paths=new ArrayList<>();for(Uri uri:uris){try{paths.add(copyAndNormalizeImage(uri));}catch(Exception e){toast("有一張圖片無法讀取："+e.getMessage());}}processOcr(paths,0);}else if(requestCode==REQ_CREATE_BACKUP&&data.getData()!=null){Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{getContentResolver().takePersistableUriPermission(uri,flags);}catch(Exception ignored){}prefs.edit().putString(BackupFileHelper.PREF_URI,uri.toString()).apply();toast("已設定備份位置");backupNow();}else if(requestCode==REQ_RESTORE&&data.getData()!=null)restoreFrom(data.getData());}

    private String copyAndNormalizeImage(Uri uri)throws Exception{File dir=new File(getFilesDir(),"purchase_images");if(!dir.exists()&&!dir.mkdirs())throw new Exception("無法建立圖片資料夾");Bitmap bm;if(Build.VERSION.SDK_INT>=28){ImageDecoder.Source source=ImageDecoder.createSource(getContentResolver(),uri);bm=ImageDecoder.decodeBitmap(source,(decoder,info,src)->{Size s=info.getSize();int max=Math.max(s.getWidth(),s.getHeight());int sample=1;while(max/sample>3200)sample*=2;decoder.setTargetSampleSize(sample);decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});}else{try(InputStream in=getContentResolver().openInputStream(uri)){bm=BitmapFactory.decodeStream(in);}}if(bm==null)throw new Exception("圖片解碼失敗");File out=new File(dir,UUID.randomUUID()+".jpg");try(FileOutputStream fos=new FileOutputStream(out)){if(!bm.compress(Bitmap.CompressFormat.JPEG,96,fos))throw new Exception("圖片儲存失敗");}bm.recycle();return out.getAbsolutePath();}

    private void processOcr(List<String> paths,int index){if(paths==null||index>=paths.size()){if(!pendingDrafts.isEmpty())showQueue();return;}String path=paths.get(index);Bitmap bm=BitmapFactory.decodeFile(path);if(bm==null){Draft d=new Draft();d.imagePath=path;d.rawOcr="圖片解碼失敗";d.warning=true;pendingDrafts.add(d);processOcr(paths,index+1);return;}toast("辨識 "+(index+1)+" / "+paths.size());OcrPipelineV204.recognize(bm,new OcrPipelineV204.Callback(){@Override public void onSuccess(PurchaseOcrParser.ParsedPurchase p){Draft d=new Draft();d.imagePath=path;d.rawOcr=p.rawText;d.vendor=p.vendor;d.location=p.location;d.orderNo=p.orderNo;d.purchaseDate=p.purchaseDate;d.items.addAll(p.items);boolean missingDelivery=false;for(PurchaseDbHelper.PurchaseItem it:d.items)if(empty(it.deliveryDate)){missingDelivery=true;break;}d.warning=empty(d.vendor)||empty(d.orderNo)||empty(d.purchaseDate)||d.items.isEmpty()||missingDelivery;pendingDrafts.add(d);bm.recycle();processOcr(paths,index+1);}@Override public void onFailure(Exception e){Draft d=new Draft();d.imagePath=path;d.rawOcr="OCR 失敗："+e.getMessage();d.warning=true;pendingDrafts.add(d);bm.recycle();processOcr(paths,index+1);}});}

    private void backupNow(){if(!BackupFileHelper.isConfigured(this)){toast("請先設定 Google Drive 備份位置");return;}toast("正在備份…");new Thread(()->{try{String label=BackupFileHelper.backupNow(this);runOnUiThread(()->{toast("備份完成："+label);if(currentScreen==SETTINGS_SCREEN)showSettings();});}catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("備份失敗").setMessage(e.getMessage()+"\n\n請重新設定 Google Drive 備份位置。").setPositiveButton("知道了",null).show());}}).start();}
    private void autoBackupSoon(){if(BackupFileHelper.isConfigured(this))ReminderWorkScheduler.runNow(this);}
    private void restoreFrom(Uri uri){new AlertDialog.Builder(this).setTitle("從備份還原？").setMessage("目前手機內的採購資料會被備份內容取代。") .setNegativeButton("取消",null).setPositiveButton("開始還原",(d,w)->new Thread(()->{try{int count=BackupFileHelper.restore(this,uri);runOnUiThread(()->{toast("還原完成，共 "+count+" 張採購單");ReminderWorkScheduler.runNow(this);showHome();});}catch(Exception e){runOnUiThread(()->new AlertDialog.Builder(this).setTitle("還原失敗").setMessage(e.getMessage()).setPositiveButton("知道了",null).show());}}).start()).show();}
    private void deleteDraftFile(Draft d){if(d!=null&&!empty(d.imagePath)){try{new File(d.imagePath).delete();}catch(Exception ignored){}}}
    private void refreshPreview(){if(preview==null||empty(currentImagePath)||!new File(currentImagePath).exists())return;BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;BitmapFactory.decodeFile(currentImagePath,o);int sample=1;while(o.outWidth/sample>1600||o.outHeight/sample>1600)sample*=2;o.inJustDecodeBounds=false;o.inSampleSize=sample;preview.setImageBitmap(BitmapFactory.decodeFile(currentImagePath,o));}

    private List<PurchaseDbHelper.DueItem> openDueItems(){List<PurchaseDbHelper.DueItem> out=new ArrayList<>();for(PurchaseDbHelper.DueItem d:db.listDueItems())if(!d.purchaseCompleted&&!d.itemCompleted&&!empty(d.deliveryDate))out.add(d);Collections.sort(out,Comparator.comparing(x->x.deliveryDate));return out;}
    private int[] dueCounts(){int overdue=0,today=0,three=0,seven=0;for(PurchaseDbHelper.DueItem d:openDueItems()){long diff=diffDays(d.deliveryDate);if(diff<0)overdue++;else if(diff==0)today++;else if(diff<=3)three++;else if(diff<=7)seven++;}return new int[]{overdue,today,three,seven};}
    private long diffDays(String date){try{SimpleDateFormat sdf=new SimpleDateFormat("yyyy-MM-dd",Locale.TAIWAN);Date target=sdf.parse(date);Date today=sdf.parse(sdf.format(new Date()));if(target==null||today==null)return 99999;return TimeUnit.MILLISECONDS.toDays(target.getTime()-today.getTime());}catch(Exception e){return 99999;}}
    private String statusLabel(long diff){if(diff<0)return"已逾期 "+(-diff)+"天";if(diff==0)return"今天交貨";if(diff<=7)return diff+"天後";return"尚有 "+diff+"天";}
    private String purchaseStatusLabel(PurchaseDbHelper.Purchase p){long min=99999;List<PurchaseDbHelper.PurchaseItem> items=db.listItems(p.id);if(!items.isEmpty()){for(PurchaseDbHelper.PurchaseItem i:items)if(!i.completed&&!empty(i.deliveryDate))min=Math.min(min,diffDays(i.deliveryDate));}else if(!empty(p.legacyDeliveryDate))min=diffDays(p.legacyDeliveryDate);return min==99999?"未設定交貨日":statusLabel(min);}
    private int statusColorForPurchase(PurchaseDbHelper.Purchase p){String s=purchaseStatusLabel(p);if(s.startsWith("已逾期"))return RED;if(s.startsWith("今天"))return ORANGE;if(s.contains("天後"))return AMBER;return BLUE;}
    private String earliestDelivery(List<PurchaseDbHelper.PurchaseItem> items,String fallback){String out="";for(PurchaseDbHelper.PurchaseItem i:items){if(empty(i.deliveryDate))continue;if(empty(out)||i.deliveryDate.compareTo(out)<0)out=i.deliveryDate;}return empty(out)?fallback:out;}
    private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIFY);}

    private void setMainScreen(LinearLayout page,int selected){editorScroll=null;ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(Color.rgb(244,249,255));shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));shell.addView(bottomNav(selected),new LinearLayout.LayoutParams(-1,-2));applyInsets(shell,false);setContentView(shell);updateSystemBack();}
    private void setEditorScreen(LinearLayout page){editorScroll=new ScrollView(this);editorScroll.setFillViewport(true);editorScroll.setClipToPadding(false);editorScroll.addView(page);applyInsets(editorScroll,true);setContentView(editorScroll);updateSystemBack();}
    private void applyInsets(View root,boolean includeIme){if(Build.VERSION.SDK_INT>=21)root.setOnApplyWindowInsetsListener((v,insets)->{int top=0,bottom=0;if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());top=bars.top;bottom=bars.bottom;if(includeIme){android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());bottom=Math.max(bottom,ime.bottom);}}else{top=insets.getSystemWindowInsetTop();bottom=insets.getSystemWindowInsetBottom();}v.setPadding(0,top,0,bottom);return insets;});}

    private LinearLayout pageColumn(){LinearLayout p=new LinearLayout(this);p.setOrientation(LinearLayout.VERTICAL);p.setPadding(dp(14),dp(12),dp(14),dp(36));p.setBackgroundColor(Color.rgb(244,249,255));return p;}
    private View appHeader(String title,String sub){LinearLayout c=pixelCard(Color.rgb(219,234,254),BLUE);LinearLayout r=row();ImageView icon=new ImageView(this);icon.setImageResource(R.drawable.ic_app_icon);r.addView(icon,new LinearLayout.LayoutParams(dp(58),dp(58)));LinearLayout t=new LinearLayout(this);t.setOrientation(LinearLayout.VERTICAL);t.setPadding(dp(10),0,0,0);t.addView(text(title,24,NAVY,true));t.addView(text(sub,12,GRAY));r.addView(t,weight());c.addView(r);return c;}
    private View topBar(String title,Runnable back){LinearLayout r=row();Button b=miniButton("←",Color.rgb(226,232,240));b.setTextColor(NAVY);b.setOnClickListener(v->back.run());r.addView(b);TextView t=text(title,22,NAVY,true);t.setPadding(dp(10),dp(6),0,dp(6));r.addView(t,weight());return r;}
    private TextView section(String s){TextView v=text(s,16,NAVY,true);v.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);v.setPadding(dp(2),dp(14),dp(2),dp(8));return v;}
    private View statusCard(String icon,String label,int count,int color){LinearLayout c=pixelCard(color,Color.rgb(15,23,42));c.addView(text(icon+"  "+label,14,Color.WHITE,true));c.addView(text(String.valueOf(count),30,Color.WHITE,true));c.setOnClickListener(v->showReminders());return c;}
    private View infoCard(String title,String body){LinearLayout c=pixelCard(Color.WHITE,Color.rgb(148,163,184));c.addView(text(title,16,NAVY,true));c.addView(text(body,14,GRAY));return c;}
    private LinearLayout pixelCard(int fill,int stroke){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(12),dp(10),dp(12),dp(10));c.setBackground(pixelBackground(fill,stroke,2));return c;}
    private GradientDrawable pixelBackground(int fill,int stroke,int sw){GradientDrawable g=new GradientDrawable();g.setColor(fill);g.setStroke(dp(sw),stroke);g.setCornerRadius(dp(7));return g;}
    private Button gameButton(String s,int color){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(Color.WHITE);b.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setBackground(pixelBackground(color,Color.rgb(15,23,42),2));return b;}
    private Button miniButton(String s,int color){Button b=gameButton(s,color);b.setTextSize(13);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(dp(9),dp(6),dp(9),dp(6));return b;}
    private TextView chip(String s,int color){TextView v=text(s,12,Color.WHITE,true);v.setGravity(Gravity.CENTER);v.setPadding(dp(8),dp(5),dp(8),dp(5));v.setBackground(pixelBackground(color,Color.rgb(15,23,42),1));return v;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(15);e.setTextColor(Color.rgb(15,23,42));e.setHintTextColor(Color.rgb(100,116,139));e.setPadding(dp(10),dp(9),dp(10),dp(9));e.setBackground(pixelBackground(Color.WHITE,Color.rgb(148,163,184),1));e.setOnFocusChangeListener((v,has)->{if(has&&editorScroll!=null){editorScroll.postDelayed(()->{try{Rect r=new Rect();v.getDrawingRect(r);editorScroll.offsetDescendantRectToMyCoords(v,r);int target=Math.max(0,r.bottom-editorScroll.getHeight()/2);editorScroll.smoothScrollTo(0,target);}catch(Exception ignored){}},220);}});return e;}
    private TextView text(String s,int size,int color){return text(s,size,color,false);} private TextView text(String s,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setLineSpacing(0,1.08f);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(dp(2),dp(3),dp(2),dp(3));return v;}
    private View bottomNav(int selected){LinearLayout nav=row();nav.setPadding(dp(5),dp(6),dp(5),dp(7));nav.setBackground(pixelBackground(Color.WHITE,Color.rgb(148,163,184),1));String[] labels={"⌂\n首頁","▤\n採購單","!\n提醒","⚙\n設定"};int[] screens={HOME,PURCHASES,REMINDERS,SETTINGS_SCREEN};for(int i=0;i<labels.length;i++){Button b=new Button(this);b.setAllCaps(false);b.setText(labels[i]);b.setTextSize(12);b.setGravity(Gravity.CENTER);b.setTextColor(screens[i]==selected?BLUE:GRAY);b.setTypeface(Typeface.MONOSPACE,screens[i]==selected?Typeface.BOLD:Typeface.NORMAL);b.setBackgroundColor(Color.TRANSPARENT);final int s=screens[i];b.setOnClickListener(v->{if(s==HOME)showHome();else if(s==PURCHASES)showPurchases("");else if(s==REMINDERS)showReminders();else showSettings();});nav.addView(b,new LinearLayout.LayoutParams(0,dp(58),1));}return nav;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);return r;} private LinearLayout.LayoutParams weight(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),dp(3),dp(3),dp(3));return p;} private LinearLayout.LayoutParams margins(int w,int h,int top,int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(2),dp(top),dp(2),dp(bottom));return p;}
    private String trim(EditText e){return e==null?"":String.valueOf(e.getText()).trim();} private boolean empty(String s){return s==null||s.trim().isEmpty();} private String safe(String s,String fallback){return empty(s)?fallback:s;} private int dp(int n){return(int)(n*getResources().getDisplayMetrics().density+0.5f);} private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private String normalizeDate(String s){if(empty(s))return"";String d=PurchaseOcrParser.extractDate(s);if(!empty(d))return d;Matcher m=Pattern.compile("^(20\\d{2})-(\\d{1,2})-(\\d{1,2})$").matcher(s);if(m.find())return String.format(Locale.TAIWAN,"%04d-%02d-%02d",Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));return s;}
    private static class Draft{String imagePath="",rawOcr="",vendor="",location="",orderNo="",purchaseDate="";boolean warning;final List<PurchaseDbHelper.PurchaseItem> items=new ArrayList<>();}
    private static class ItemRow{LinearLayout root;EditText lineNo,description,qty,unit,unitPrice,subtotal,delivery,note;CheckBox completed;}
}
