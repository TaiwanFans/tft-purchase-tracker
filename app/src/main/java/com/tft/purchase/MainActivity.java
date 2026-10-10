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
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.window.OnBackInvokedDispatcher;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

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

public class MainActivity extends Activity {
    private static final int REQ_PICK_IMAGES = 101;
    private static final int REQ_NOTIFY = 103;
    private static final int REQ_BACKUP_FOLDER = 201;
    private static final int REQ_RESTORE_ZIP = 202;

    private static final int SCREEN_HOME = 1;
    private static final int SCREEN_PURCHASES = 2;
    private static final int SCREEN_REMINDERS = 3;
    private static final int SCREEN_SETTINGS = 4;
    private static final int SCREEN_QUEUE = 5;
    private static final int SCREEN_EDITOR = 6;

    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int NAVY = Color.rgb(16, 42, 92);
    private static final int RED = Color.rgb(220, 38, 38);
    private static final int ORANGE = Color.rgb(234, 88, 12);
    private static final int AMBER = Color.rgb(217, 119, 6);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int GRAY = Color.rgb(71, 85, 105);

    private PurchaseDbHelper db;
    private SharedPreferences prefs;
    private int currentScreen = SCREEN_HOME;
    private long lastBackAt = 0;

    private long editingId = -1;
    private Draft editingDraft = null;
    private String currentImagePath = "";
    private EditText vendorEt, locationEt, orderNoEt, purchaseEt, signatureEt, notesEt;
    private CheckBox completedCb;
    private ImageView preview;
    private TextView ocrTv;
    private LinearLayout itemsContainer;
    private final List<ItemRow> itemRows = new ArrayList<>();
    private final List<Draft> pendingDrafts = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new PurchaseDbHelper(this);
        prefs = getSharedPreferences("tft_settings", MODE_PRIVATE);
        if (!prefs.contains("reminder_days")) prefs.edit().putInt("reminder_days", 5).apply();
        configureWindow();
        requestNotificationPermission();
        ReminderScheduler.schedule(this);
        DriveBackupHelper.backupIfDueAsync(this);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
        String open = getIntent() == null ? "" : getIntent().getStringExtra("screen");
        if ("reminders".equals(open)) showReminders(); else showDashboard();
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.rgb(248, 250, 252));
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) c.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
    }

    @Override
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 33) handleBack();
    }

    private void handleBack() {
        if (currentScreen == SCREEN_EDITOR) {
            new AlertDialog.Builder(this)
                    .setTitle("離開編輯？")
                    .setMessage("尚未儲存的修改會消失，確定要離開嗎？")
                    .setNegativeButton("繼續編輯", null)
                    .setPositiveButton("離開", (d, w) -> {
                        if (!pendingDrafts.isEmpty()) showQueue(); else showPurchases("");
                    }).show();
            return;
        }
        if (currentScreen == SCREEN_QUEUE || currentScreen == SCREEN_PURCHASES ||
                currentScreen == SCREEN_REMINDERS || currentScreen == SCREEN_SETTINGS) {
            showDashboard();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackAt < 1800) finish();
        else {
            lastBackAt = now;
            toast("再按一次返回鍵離開 APP");
        }
    }

    private void showDashboard() {
        currentScreen = SCREEN_HOME;
        LinearLayout page = pageColumn();
        page.addView(appHeader("全益採購追蹤", "像素版交貨雷達"));
        page.addView(sectionTitle("交貨提醒總覽（以品項明細計算）"));

        List<PurchaseDbHelper.DueItem> due = openDueItems();
        int overdue = 0, today = 0, three = 0, seven = 0;
        for (PurchaseDbHelper.DueItem d : due) {
            long diff = diffDays(d.deliveryDate);
            if (diff < 0) overdue++;
            else if (diff == 0) today++;
            else if (diff <= 3) three++;
            else if (diff <= 7) seven++;
        }

        LinearLayout row1 = hRow();
        row1.addView(statusCard("☠", "已逾期", overdue, RED), weight());
        row1.addView(statusCard("!", "今天交貨", today, ORANGE), weight());
        page.addView(row1);
        LinearLayout row2 = hRow();
        row2.addView(statusCard("★", "3天內交貨", three, AMBER), weight());
        row2.addView(statusCard("◆", "4–7天交貨", seven, BLUE), weight());
        page.addView(row2);

        Button importBtn = gameButton("＋ 從相簿匯入採購單", BLUE);
        importBtn.setTextSize(18);
        importBtn.setOnClickListener(v -> openPicker());
        page.addView(importBtn, marginParams(-1, -2, 8, 12));

        if (!pendingDrafts.isEmpty()) {
            Button queue = gameButton("待確認清單：" + pendingDrafts.size() + " 張", Color.rgb(8, 145, 178));
            queue.setOnClickListener(v -> showQueue());
            page.addView(queue, marginParams(-1, -2, 8, 12));
        }

        page.addView(sectionTitle("最近要交貨"));
        int shown = 0;
        for (PurchaseDbHelper.DueItem d : due) {
            if (shown >= 8) break;
            long diff = diffDays(d.deliveryDate);
            if (diff > 14) continue;
            page.addView(dueCard(d));
            shown++;
        }
        if (shown == 0) page.addView(emptyCard("目前 14 天內沒有未完成交貨項目。"));

        setMainScreen(page, SCREEN_HOME);
    }

    private void showPurchases(String search) {
        currentScreen = SCREEN_PURCHASES;
        LinearLayout page = pageColumn();
        page.addView(topBar("採購單", this::showDashboard));

        LinearLayout searchRow = hRow();
        EditText searchEt = input("搜尋廠商／採購單號／品項", false);
        searchEt.setText(search == null ? "" : search);
        searchRow.addView(searchEt, weight());
        Button find = miniButton("搜尋", BLUE);
        searchRow.addView(find);
        find.setOnClickListener(v -> showPurchases(searchEt.getText().toString()));
        page.addView(searchRow, marginParams(-1, -2, 4, 8));

        Button add = gameButton("＋ 相簿匯入", BLUE);
        add.setOnClickListener(v -> openPicker());
        page.addView(add, marginParams(-1, -2, 8, 12));

        List<PurchaseDbHelper.Purchase> orders = db.list(search);
        if (orders.isEmpty()) page.addView(emptyCard("目前沒有採購資料。"));
        for (PurchaseDbHelper.Purchase p : orders) page.addView(purchaseCard(p));
        setMainScreen(page, SCREEN_PURCHASES);
    }

    private void showReminders() {
        currentScreen = SCREEN_REMINDERS;
        LinearLayout page = pageColumn();
        page.addView(topBar("提醒總覽", this::showDashboard));
        page.addView(text("依交貨日由近到遠排列，先處理紅色與橘色。", 14, GRAY));

        List<PurchaseDbHelper.DueItem> due = openDueItems();
        int overdue = 0, today = 0, three = 0, seven = 0;
        for (PurchaseDbHelper.DueItem d : due) {
            long diff = diffDays(d.deliveryDate);
            if (diff < 0) overdue++;
            else if (diff == 0) today++;
            else if (diff <= 3) three++;
            else if (diff <= 7) seven++;
        }
        LinearLayout row1 = hRow();
        row1.addView(statusCard("☠", "已逾期", overdue, RED), weight());
        row1.addView(statusCard("!", "今天", today, ORANGE), weight());
        page.addView(row1);
        LinearLayout row2 = hRow();
        row2.addView(statusCard("★", "1–3天", three, AMBER), weight());
        row2.addView(statusCard("◆", "4–7天", seven, BLUE), weight());
        page.addView(row2);

        page.addView(sectionTitle("交貨明細"));
        if (due.isEmpty()) page.addView(emptyCard("目前沒有未完成交貨項目。"));
        for (PurchaseDbHelper.DueItem d : due) page.addView(dueCard(d));
        setMainScreen(page, SCREEN_REMINDERS);
    }

    private void showSettings() {
        currentScreen = SCREEN_SETTINGS;
        LinearLayout page = pageColumn();
        page.addView(topBar("設定", this::showDashboard));

        page.addView(sectionTitle("提醒設定"));
        int currentDays = prefs.getInt("reminder_days", 5);
        page.addView(text("目前提前提醒：" + currentDays + " 天。每日 08:30 會整理逾期、今天與即將交貨通知。", 15, GRAY));
        LinearLayout dayRow = hRow();
        for (int d : new int[]{3,5,7,14}) {
            Button b = miniButton(d + " 天", d == currentDays ? BLUE : Color.rgb(226,232,240));
            if (d != currentDays) b.setTextColor(NAVY);
            b.setOnClickListener(v -> {
                prefs.edit().putInt("reminder_days", d).apply();
                ReminderScheduler.schedule(this);
                showSettings();
            });
            dayRow.addView(b, weight());
        }
        page.addView(dayRow);

        page.addView(sectionTitle("Google Drive 雲端備份"));
        String tree = prefs.getString("backup_tree_uri", "");
        String last = prefs.getString("last_backup_label", "尚未備份");
        page.addView(gameInfoCard(
                tree.isEmpty() ? "尚未選擇備份資料夾" : "已設定雲端備份資料夾",
                "上次備份：" + last + "\n選擇 Google Drive 資料夾後，APP 每天會自動備份，也可以手動立即備份。"));

        Button choose = gameButton(tree.isEmpty() ? "選擇 Google Drive 備份資料夾" : "更換備份資料夾", BLUE);
        choose.setOnClickListener(v -> chooseBackupFolder());
        page.addView(choose, marginParams(-1,-2,6,8));
        Button backup = gameButton("立即備份", GREEN);
        backup.setEnabled(!tree.isEmpty());
        backup.setOnClickListener(v -> doBackupNow());
        page.addView(backup, marginParams(-1,-2,6,8));
        Button restore = gameButton("從備份 ZIP 還原", Color.rgb(124,58,237));
        restore.setOnClickListener(v -> chooseRestoreFile());
        page.addView(restore, marginParams(-1,-2,6,12));

        page.addView(sectionTitle("OCR 辨識方式"));
        page.addView(gameInfoCard("針對你提供的採購單版型優化",
                "固定抓：供應廠商、廠商地址、採購單號、採購日期，以及每一列的品名、數量、單位、單價、小計、交貨日期、備註。\n「以下空白」會自動忽略；不同品項可有不同交貨日。"));

        page.addView(sectionTitle("資料安全"));
        page.addView(text("本機資料保存在 APP 私有資料庫；雲端備份 ZIP 會包含採購資料與原始照片。換手機後可從備份 ZIP 還原。", 14, GRAY));
        setMainScreen(page, SCREEN_SETTINGS);
    }

    private void showQueue() {
        currentScreen = SCREEN_QUEUE;
        LinearLayout page = pageColumn();
        page.addView(topBar("待確認清單", this::showDashboard));
        page.addView(text("OCR 已先整理資料。請逐張確認後再存檔。", 14, GRAY));
        if (pendingDrafts.isEmpty()) {
            page.addView(emptyCard("目前沒有待確認採購單。"));
        } else {
            int i = 1;
            for (Draft d : new ArrayList<>(pendingDrafts)) {
                LinearLayout card = pixelCard(Color.WHITE, BLUE);
                card.addView(text("待確認 " + i + " / " + pendingDrafts.size(), 13, BLUE));
                card.addView(text(safe(d.vendor, "未辨識廠商"), 18, NAVY, true));
                card.addView(text("採購單號：" + safe(d.orderNo, "待確認") + "\n採購日：" + safe(d.purchaseDate, "待確認") +
                        "\n辨識到明細：" + d.items.size() + " 項", 14, GRAY));
                Button review = miniButton("檢查並儲存", GREEN);
                review.setOnClickListener(v -> showEditor(null, d));
                card.addView(review, marginParams(-1,-2,6,2));
                page.addView(card, marginParams(-1,-2,6,8));
                i++;
            }
        }
        Button more = gameButton("＋ 再從相簿匯入", BLUE);
        more.setOnClickListener(v -> openPicker());
        page.addView(more, marginParams(-1,-2,8,12));
        setMainScreen(page, SCREEN_QUEUE);
    }

    private View purchaseCard(PurchaseDbHelper.Purchase p) {
        LinearLayout card = pixelCard(Color.WHITE, p.completed ? GREEN : BLUE);
        LinearLayout titleRow = hRow();
        TextView vendor = text(safe(p.vendorName, "未填廠商"), 17, NAVY, true);
        titleRow.addView(vendor, weight());
        TextView state = chip(p.completed ? "已完成" : purchaseStatusLabel(p), p.completed ? GREEN : statusColorForPurchase(p));
        titleRow.addView(state);
        card.addView(titleRow);
        card.addView(text("採購單號：" + safe(p.orderNo, "未填") + "　採購日：" + safe(p.purchaseDate, "未填"), 13, GRAY));
        List<PurchaseDbHelper.PurchaseItem> items = db.listItems(p.id);
        String next = earliestDelivery(items, p.legacyDeliveryDate);
        card.addView(text("品項：" + (items.isEmpty() ? safe(p.legacyItems, "未填") : items.size() + " 項") +
                "\n最近交貨：" + safe(next, "未設定"), 14, Color.rgb(30,41,59)));
        card.setOnClickListener(v -> showEditor(p, null));
        return card;
    }

    private View dueCard(PurchaseDbHelper.DueItem d) {
        long diff = diffDays(d.deliveryDate);
        int color = diff < 0 ? RED : diff == 0 ? ORANGE : diff <= 3 ? AMBER : BLUE;
        LinearLayout card = pixelCard(Color.WHITE, color);
        LinearLayout top = hRow();
        top.addView(text(safe(d.vendorName, "未填廠商"), 16, NAVY, true), weight());
        top.addView(chip(statusLabel(diff), color));
        card.addView(top);
        card.addView(text("交貨日：" + d.deliveryDate + "　採購單：" + safe(d.orderNo, "未填"), 14, color, true));
        card.addView(text(safe(d.description, "未填品項") +
                (empty(d.quantity) ? "" : "\n數量：" + d.quantity + safe(d.unit, "")), 14, Color.rgb(30,41,59)));
        card.setOnClickListener(v -> {
            PurchaseDbHelper.Purchase p = db.get(d.purchaseId);
            if (p != null) showEditor(p, null);
        });
        return card;
    }

    private void showEditor(PurchaseDbHelper.Purchase p, Draft draft) {
        currentScreen = SCREEN_EDITOR;
        editingId = p == null ? -1 : p.id;
        editingDraft = draft;
        currentImagePath = p != null ? safe(p.imagePath, "") : draft != null ? safe(draft.imagePath, "") : "";
        itemRows.clear();

        LinearLayout page = pageColumn();
        page.addView(topBar(p == null ? "確認採購單" : "採購單明細", this::handleBack));
        page.addView(text("請特別確認每一列的交貨日期；提醒功能會依「每個品項」的交貨日計算。", 14, RED, true));

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setBackground(pixelBackground(Color.rgb(241,245,249), Color.rgb(148,163,184), 2));
        page.addView(preview, marginParams(-1, dp(260), 4, 8));
        refreshPreview();

        vendorEt = input("供應廠商", false);
        locationEt = input("廠商地址", false);
        orderNoEt = input("採購單號", false);
        purchaseEt = input("採購日期 YYYY-MM-DD", false);
        signatureEt = input("廠務簽名回傳日期 YYYY-MM-DD（人工填寫）", false);
        notesEt = input("整張採購單備註", false); notesEt.setMinLines(2);
        completedCb = new CheckBox(this); completedCb.setText("整張採購單已完成"); completedCb.setTextColor(NAVY);
        completedCb.setPadding(dp(6),dp(8),dp(6),dp(8));

        for (View v : new View[]{vendorEt, locationEt, orderNoEt, purchaseEt, signatureEt, notesEt, completedCb}) {
            page.addView(v, marginParams(-1,-2,4,6));
        }

        if (p != null) {
            vendorEt.setText(p.vendorName); locationEt.setText(p.location); orderNoEt.setText(p.orderNo);
            purchaseEt.setText(p.purchaseDate); signatureEt.setText(p.signatureReturnDate); notesEt.setText(p.notes);
            completedCb.setChecked(p.completed);
        } else if (draft != null) {
            vendorEt.setText(draft.vendor); locationEt.setText(draft.location); orderNoEt.setText(draft.orderNo);
            purchaseEt.setText(draft.purchaseDate);
        }

        page.addView(sectionTitle("品項明細／交貨日"));
        itemsContainer = new LinearLayout(this);
        itemsContainer.setOrientation(LinearLayout.VERTICAL);
        page.addView(itemsContainer);

        List<PurchaseDbHelper.PurchaseItem> initial = new ArrayList<>();
        if (p != null) initial.addAll(db.listItems(p.id));
        if (draft != null) initial.addAll(draft.items);
        if (p != null && initial.isEmpty() && (!empty(p.legacyItems) || !empty(p.legacyDeliveryDate))) {
            PurchaseDbHelper.PurchaseItem legacy = new PurchaseDbHelper.PurchaseItem();
            legacy.lineNo = "001";
            legacy.description = p.legacyItems;
            legacy.deliveryDate = p.legacyDeliveryDate;
            initial.add(legacy);
        }
        if (initial.isEmpty()) initial.add(new PurchaseDbHelper.PurchaseItem());
        for (PurchaseDbHelper.PurchaseItem item : initial) addItemRow(item);

        Button addItem = miniButton("＋ 新增品項", BLUE);
        addItem.setOnClickListener(v -> addItemRow(new PurchaseDbHelper.PurchaseItem()));
        page.addView(addItem, marginParams(-1,-2,6,10));

        ocrTv = text("", 12, GRAY);
        ocrTv.setVisibility(View.GONE);
        if (p != null) ocrTv.setText(p.rawOcr);
        else if (draft != null) ocrTv.setText(draft.rawOcr);
        page.addView(ocrTv);

        Button save = gameButton("✓ 確認儲存", GREEN);
        save.setTextSize(18);
        save.setOnClickListener(v -> savePurchase());
        page.addView(save, marginParams(-1,-2,10,6));

        if (draft != null) {
            Button later = gameButton("稍後再確認", Color.rgb(100,116,139));
            later.setOnClickListener(v -> showQueue());
            page.addView(later, marginParams(-1,-2,4,8));
        }
        if (p != null) {
            Button del = gameButton("刪除這筆採購單", RED);
            del.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setTitle("確認刪除？")
                    .setMessage("採購資料與本機照片會從 APP 中移除。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("刪除", (d,w) -> { db.delete(p.id); showPurchases(""); }).show());
            page.addView(del, marginParams(-1,-2,4,12));
        }
        setEditorScreen(page);
    }

    private void addItemRow(PurchaseDbHelper.PurchaseItem item) {
        final ItemRow row = new ItemRow();
        LinearLayout card = pixelCard(Color.rgb(248,250,252), Color.rgb(148,163,184));
        row.root = card;

        LinearLayout header = hRow();
        row.lineNo = input("項次", false); row.lineNo.setText(item.lineNo); row.lineNo.setTextSize(14);
        header.addView(row.lineNo, new LinearLayout.LayoutParams(dp(78), -2));
        TextView label = text("品項明細", 14, NAVY, true); header.addView(label, weight());
        Button remove = miniButton("刪除", Color.rgb(226,232,240)); remove.setTextColor(RED);
        header.addView(remove);
        card.addView(header);

        row.description = input("品名規格明細", false); row.description.setMinLines(2); row.description.setText(item.description);
        card.addView(row.description, marginParams(-1,-2,2,4));

        LinearLayout r1 = hRow();
        row.qty = compactInput("數量", item.quantity); r1.addView(row.qty, weight());
        row.unit = compactInput("單位", item.unit); r1.addView(row.unit, weight());
        row.unitPrice = compactInput("單價", item.unitPrice); r1.addView(row.unitPrice, weight());
        card.addView(r1);

        LinearLayout r2 = hRow();
        row.subtotal = compactInput("小計", item.subtotal); r2.addView(row.subtotal, weight());
        row.delivery = compactInput("交貨日 YYYY-MM-DD", item.deliveryDate);
        r2.addView(row.delivery, new LinearLayout.LayoutParams(0,-2,2));
        card.addView(r2);
        row.note = input("備註", false); row.note.setText(item.note); card.addView(row.note);
        row.completed = new CheckBox(this); row.completed.setText("此品項已完成"); row.completed.setChecked(item.completed); row.completed.setTextColor(GRAY); card.addView(row.completed);

        remove.setOnClickListener(v -> {
            if (itemRows.size() <= 1) { toast("至少保留一個品項"); return; }
            itemRows.remove(row); itemsContainer.removeView(card);
        });
        itemRows.add(row);
        itemsContainer.addView(card, marginParams(-1,-2,4,8));
    }

    private EditText compactInput(String hint, String value) {
        EditText e = input(hint, false);
        e.setText(value);
        e.setTextSize(13);
        return e;
    }

    private void savePurchase() {
        PurchaseDbHelper.Purchase p = editingId > 0 ? db.get(editingId) : new PurchaseDbHelper.Purchase();
        if (p == null) p = new PurchaseDbHelper.Purchase();
        p.vendorName = vendorEt.getText().toString().trim();
        p.location = locationEt.getText().toString().trim();
        p.orderNo = orderNoEt.getText().toString().trim();
        p.purchaseDate = normalizeDateInput(purchaseEt.getText().toString().trim());
        p.signatureReturnDate = normalizeDateInput(signatureEt.getText().toString().trim());
        p.notes = notesEt.getText().toString().trim();
        p.completed = completedCb.isChecked();
        p.imagePath = currentImagePath;
        p.rawOcr = ocrTv == null ? "" : ocrTv.getText().toString();

        List<PurchaseDbHelper.PurchaseItem> items = new ArrayList<>();
        StringBuilder legacy = new StringBuilder();
        String earliest = "";
        for (ItemRow r : itemRows) {
            PurchaseDbHelper.PurchaseItem i = new PurchaseDbHelper.PurchaseItem();
            i.lineNo = r.lineNo.getText().toString().trim();
            i.description = r.description.getText().toString().trim();
            i.quantity = r.qty.getText().toString().trim();
            i.unit = r.unit.getText().toString().trim();
            i.unitPrice = r.unitPrice.getText().toString().trim();
            i.subtotal = r.subtotal.getText().toString().trim();
            i.deliveryDate = normalizeDateInput(r.delivery.getText().toString().trim());
            i.note = r.note.getText().toString().trim();
            i.completed = r.completed.isChecked();
            if (empty(i.description) && empty(i.deliveryDate) && empty(i.quantity)) continue;
            items.add(i);
            if (!empty(i.description)) {
                if (legacy.length() > 0) legacy.append("\n");
                legacy.append(i.description);
                if (!empty(i.quantity)) legacy.append(" × ").append(i.quantity).append(i.unit);
            }
            if (!empty(i.deliveryDate) && (empty(earliest) || i.deliveryDate.compareTo(earliest) < 0)) earliest = i.deliveryDate;
        }
        p.legacyItems = legacy.toString();
        p.legacyDeliveryDate = earliest;

        if (empty(p.vendorName) && empty(p.orderNo)) {
            toast("請至少確認供應廠商或採購單號");
            return;
        }
        long id;
        if (editingId > 0) { p.id = editingId; db.update(p); id = editingId; }
        else { id = db.insert(p); editingId = id; }
        db.replaceItems(id, items);

        if (editingDraft != null) pendingDrafts.remove(editingDraft);
        editingDraft = null;
        toast("已儲存採購單");
        DriveBackupHelper.backupIfDueAsync(this);
        if (!pendingDrafts.isEmpty()) showQueue(); else showDashboard();
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK_IMAGES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == REQ_PICK_IMAGES) {
                List<Uri> uris = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int x = 0; x < clip.getItemCount(); x++) uris.add(clip.getItemAt(x).getUri());
                } else if (data.getData() != null) uris.add(data.getData());
                if (uris.isEmpty()) return;
                List<String> paths = new ArrayList<>();
                for (Uri uri : uris) paths.add(copyUriToInternal(uri));
                toast("已選擇 " + paths.size() + " 張，正在辨識…");
                processBatchOcr(paths, 0);
            } else if (requestCode == REQ_BACKUP_FOLDER && data.getData() != null) {
                Uri tree = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(tree, flags); } catch (Exception ignored) {}
                prefs.edit().putString("backup_tree_uri", tree.toString()).apply();
                toast("已設定備份資料夾");
                doBackupNow();
            } else if (requestCode == REQ_RESTORE_ZIP && data.getData() != null) {
                Uri uri = data.getData();
                new AlertDialog.Builder(this)
                        .setTitle("從備份還原？")
                        .setMessage("目前 APP 內的資料會被備份檔內容取代。確定繼續嗎？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("還原", (d,w) -> restoreFrom(uri)).show();
            }
        } catch (Exception e) { toast("操作失敗：" + e.getMessage()); }
    }

    private void processBatchOcr(List<String> paths, int index) {
        if (index >= paths.size()) {
            toast("辨識完成，共 " + paths.size() + " 張，請逐張確認");
            showQueue();
            return;
        }
        String path = paths.get(index);
        try {
            InputImage image = InputImage.fromFilePath(this, Uri.fromFile(new File(path)));
            TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        int[] size = imageSize(path);
                        Draft d = parseOcr(result, size[0], size[1]);
                        d.imagePath = path;
                        d.rawOcr = result.getText();
                        pendingDrafts.add(d);
                        recognizer.close();
                        processBatchOcr(paths, index + 1);
                    })
                    .addOnFailureListener(e -> {
                        Draft d = new Draft(); d.imagePath = path; d.rawOcr = "OCR 失敗：" + e.getMessage();
                        pendingDrafts.add(d);
                        recognizer.close();
                        processBatchOcr(paths, index + 1);
                    });
        } catch (Exception e) {
            Draft d = new Draft(); d.imagePath = path; d.rawOcr = "OCR 啟動失敗：" + e.getMessage();
            pendingDrafts.add(d);
            processBatchOcr(paths, index + 1);
        }
    }

    private Draft parseOcr(Text result, int width, int height) {
        Draft d = new Draft();
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock b : result.getTextBlocks()) lines.addAll(b.getLines());

        int headerY = (int)(height * 0.18f);
        int bottomY = (int)(height * 0.72f);
        for (Text.Line line : lines) {
            String s = tidy(line.getText());
            Rect box = line.getBoundingBox();
            if (box == null) continue;
            if (s.contains("品名") || s.contains("規格明細")) headerY = Math.max(headerY, box.bottom);
            if (s.contains("本頁小計")) bottomY = Math.min(bottomY, box.top);

            if (empty(d.vendor) && (s.contains("供應廠商") || s.contains("供應商"))) d.vendor = afterLabel(s, "供應廠商", "供應商");
            if (empty(d.location) && (s.contains("廠商地址") || s.contains("地址"))) d.location = afterLabel(s, "廠商地址", "地址");
            if (empty(d.orderNo) && s.contains("採購單號")) {
                Matcher m = Pattern.compile("(20\\d{8,14})").matcher(s.replace(" ", ""));
                if (m.find()) d.orderNo = m.group(1);
                else d.orderNo = afterLabel(s, "採購單號").replaceAll("[^0-9]", "");
            }
            if (empty(d.purchaseDate) && (s.contains("採購日期") || s.contains("採購日"))) d.purchaseDate = extractDate(s);
        }

        List<RowStart> starts = new ArrayList<>();
        Pattern rowNo = Pattern.compile("^\\s*(\\d{1,3})(?:\\s|$|[^0-9]).*");
        for (Text.Line line : lines) {
            Rect b = line.getBoundingBox();
            if (b == null || b.top <= headerY || b.top >= bottomY || b.left > width * 0.12f) continue;
            String s = tidy(line.getText());
            Matcher m = rowNo.matcher(s);
            if (m.matches()) {
                try {
                    int n = Integer.parseInt(m.group(1));
                    if (n >= 0 && n <= 99) starts.add(new RowStart(b.top, String.format(Locale.TAIWAN, "%03d", n)));
                } catch (Exception ignored) {}
            }
        }
        Collections.sort(starts, Comparator.comparingInt(a -> a.y));
        List<RowStart> unique = new ArrayList<>();
        for (RowStart s : starts) {
            if (unique.isEmpty() || Math.abs(unique.get(unique.size()-1).y - s.y) > dp(8)) unique.add(s);
        }

        for (int r = 0; r < unique.size(); r++) {
            int top = unique.get(r).y - dp(4);
            int bottom = r + 1 < unique.size() ? unique.get(r+1).y - dp(4) : bottomY;
            PurchaseDbHelper.PurchaseItem item = parseTableRow(result, width, top, bottom, unique.get(r).lineNo);
            if (item == null) continue;
            if (item.description.contains("以下空白") || item.description.equals("空白")) continue;
            if (empty(item.description) && empty(item.deliveryDate)) continue;
            d.items.add(item);
        }

        if (d.items.isEmpty()) {
            for (Text.Line line : lines) {
                Rect b = line.getBoundingBox();
                if (b == null || b.top <= headerY || b.top >= bottomY) continue;
                String s = tidy(line.getText());
                if (!s.matches("^0?0?\\d{1,2}.*") || s.contains("以下空白")) continue;
                PurchaseDbHelper.PurchaseItem item = new PurchaseDbHelper.PurchaseItem();
                Matcher m = Pattern.compile("^(\\d{1,3})\\s*(.*)$").matcher(s);
                if (m.find()) {
                    item.lineNo = String.format(Locale.TAIWAN, "%03d", Integer.parseInt(m.group(1)));
                    item.description = m.group(2).trim();
                }
                item.deliveryDate = extractDate(s);
                d.items.add(item);
            }
        }
        return d;
    }

    private PurchaseDbHelper.PurchaseItem parseTableRow(Text result, int width, int top, int bottom, String lineNo) {
        List<Token> tokens = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                for (Text.Element e : line.getElements()) {
                    Rect b = e.getBoundingBox();
                    if (b == null) continue;
                    int cy = (b.top + b.bottom) / 2;
                    if (cy >= top && cy < bottom) tokens.add(new Token(e.getText(), b));
                }
            }
        }
        if (tokens.isEmpty()) return null;
        Collections.sort(tokens, (a,b) -> {
            int dy = a.box.top - b.box.top;
            return Math.abs(dy) > dp(6) ? dy : a.box.left - b.box.left;
        });

        PurchaseDbHelper.PurchaseItem item = new PurchaseDbHelper.PurchaseItem();
        item.lineNo = lineNo;
        List<Token> desc = new ArrayList<>();
        List<Token> note = new ArrayList<>();
        String qty="", unit="", price="", subtotal="", delivery="";
        StringBuilder all = new StringBuilder();
        for (Token t : tokens) {
            String s = tidy(t.text);
            if (empty(s)) continue;
            if (all.length() > 0) all.append(' '); all.append(s);
            float cx = ((t.box.left + t.box.right) / 2f) / width;
            if (cx >= 0.065f && cx < 0.49f) desc.add(t);
            else if (cx >= 0.49f && cx < 0.565f && empty(qty) && isNumberish(s)) qty = cleanNumber(s);
            else if (cx >= 0.55f && cx < 0.615f && empty(unit) && isUnit(s)) unit = s;
            else if (cx >= 0.60f && cx < 0.685f && empty(price) && isNumberish(s)) price = cleanNumber(s);
            else if (cx >= 0.67f && cx < 0.785f && empty(subtotal) && isNumberish(s)) subtotal = cleanNumber(s);
            if (cx >= 0.74f && cx < 0.90f && empty(delivery)) {
                String dt = extractDate(s);
                if (!empty(dt)) delivery = dt;
            }
            if (cx >= 0.875f) note.add(t);
        }
        if (empty(delivery)) delivery = extractDate(all.toString());
        item.description = joinTokens(desc, lineNo);
        item.quantity = qty;
        item.unit = unit;
        item.unitPrice = price;
        item.subtotal = subtotal;
        item.deliveryDate = delivery;
        item.note = joinTokens(note, "");
        return item;
    }

    private String joinTokens(List<Token> xs, String stripPrefix) {
        StringBuilder out = new StringBuilder();
        int lastTop = Integer.MIN_VALUE;
        for (Token t : xs) {
            String s = tidy(t.text);
            if (empty(s)) continue;
            if (!empty(stripPrefix) && (s.equals(stripPrefix) || s.equals(stripPrefix.replaceFirst("^0+", "")))) continue;
            if (out.length() > 0) {
                if (lastTop != Integer.MIN_VALUE && Math.abs(t.box.top - lastTop) > dp(9)) out.append("\n");
                else out.append(' ');
            }
            out.append(s);
            lastTop = t.box.top;
        }
        return out.toString().replaceFirst("^0{0,2}\\d{1,3}\\s*", "").trim();
    }

    private String copyUriToInternal(Uri uri) throws Exception {
        File dir = new File(getFilesDir(), "purchase_images");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, UUID.randomUUID() + ".jpg");
        try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("無法讀取圖片");
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out.getAbsolutePath();
    }

    private int[] imageSize(String path) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, o);
        return new int[]{Math.max(1,o.outWidth), Math.max(1,o.outHeight)};
    }

    private void refreshPreview() {
        if (preview == null) return;
        if (empty(currentImagePath) || !new File(currentImagePath).exists()) {
            preview.setImageDrawable(null); return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true; BitmapFactory.decodeFile(currentImagePath, o);
        int sample = 1; while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2;
        o.inJustDecodeBounds = false; o.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(currentImagePath, o);
        preview.setImageBitmap(bm);
    }

    private void chooseBackupFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_BACKUP_FOLDER);
    }

    private void doBackupNow() {
        toast("正在建立雲端備份…");
        new Thread(() -> {
            try {
                String name = DriveBackupHelper.backupNow(this);
                runOnUiThread(() -> { toast("備份完成：" + name); if (currentScreen == SCREEN_SETTINGS) showSettings(); });
            } catch (Exception e) {
                runOnUiThread(() -> toast("備份失敗：" + e.getMessage()));
            }
        }).start();
    }

    private void chooseRestoreFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("application/zip");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_RESTORE_ZIP);
    }

    private void restoreFrom(Uri uri) {
        toast("正在還原資料…");
        new Thread(() -> {
            try {
                int count = DriveBackupHelper.restore(this, uri);
                runOnUiThread(() -> { toast("還原完成，共 " + count + " 張採購單"); showDashboard(); });
            } catch (Exception e) {
                runOnUiThread(() -> toast("還原失敗：" + e.getMessage()));
            }
        }).start();
    }

    private List<PurchaseDbHelper.DueItem> openDueItems() {
        List<PurchaseDbHelper.DueItem> out = new ArrayList<>();
        for (PurchaseDbHelper.DueItem d : db.listDueItems()) {
            if (!d.purchaseCompleted && !d.itemCompleted && !empty(d.deliveryDate)) out.add(d);
        }
        Collections.sort(out, Comparator.comparing(a -> a.deliveryDate));
        return out;
    }

    private long diffDays(String date) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
            Date d = sdf.parse(date); Date today = sdf.parse(sdf.format(new Date()));
            if (d == null || today == null) return 99999;
            return TimeUnit.MILLISECONDS.toDays(d.getTime() - today.getTime());
        } catch (Exception e) { return 99999; }
    }

    private String statusLabel(long diff) {
        if (diff < 0) return "已逾期 " + (-diff) + "天";
        if (diff == 0) return "今天交貨";
        if (diff <= 7) return diff + "天後交貨";
        return "尚有 " + diff + "天";
    }

    private String purchaseStatusLabel(PurchaseDbHelper.Purchase p) {
        List<PurchaseDbHelper.PurchaseItem> items = db.listItems(p.id);
        long min = 99999;
        if (!items.isEmpty()) {
            for (PurchaseDbHelper.PurchaseItem i : items) if (!i.completed && !empty(i.deliveryDate)) min = Math.min(min, diffDays(i.deliveryDate));
        } else if (!empty(p.legacyDeliveryDate)) min = diffDays(p.legacyDeliveryDate);
        if (min == 99999) return "未設定交貨日";
        return statusLabel(min);
    }

    private int statusColorForPurchase(PurchaseDbHelper.Purchase p) {
        String s = purchaseStatusLabel(p);
        if (s.startsWith("已逾期")) return RED;
        if (s.startsWith("今天")) return ORANGE;
        if (s.contains("天後")) return AMBER;
        return BLUE;
    }

    private String earliestDelivery(List<PurchaseDbHelper.PurchaseItem> items, String fallback) {
        String out = "";
        for (PurchaseDbHelper.PurchaseItem i : items) {
            if (empty(i.deliveryDate)) continue;
            if (empty(out) || i.deliveryDate.compareTo(out) < 0) out = i.deliveryDate;
        }
        return empty(out) ? fallback : out;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private LinearLayout pageColumn() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(24));
        root.setBackgroundColor(Color.rgb(244, 249, 255));
        return root;
    }

    private View appHeader(String title, String subtitle) {
        LinearLayout card = pixelCard(Color.rgb(219, 234, 254), BLUE);
        LinearLayout row = hRow();
        ImageView icon = new ImageView(this); icon.setImageResource(R.drawable.ic_app_icon);
        row.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout txt = new LinearLayout(this); txt.setOrientation(LinearLayout.VERTICAL); txt.setPadding(dp(10),0,0,0);
        txt.addView(text(title, 24, NAVY, true));
        txt.addView(text(subtitle, 13, GRAY));
        row.addView(txt, weight());
        card.addView(row);
        return card;
    }

    private View topBar(String title, Runnable backAction) {
        LinearLayout bar = hRow();
        Button back = miniButton("←", Color.rgb(226,232,240)); back.setTextColor(NAVY);
        back.setOnClickListener(v -> backAction.run());
        bar.addView(back);
        TextView t = text(title, 22, NAVY, true); t.setPadding(dp(10), dp(6), 0, dp(6));
        bar.addView(t, weight());
        return bar;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 16, NAVY, true);
        v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        v.setPadding(dp(2), dp(14), dp(2), dp(8));
        return v;
    }

    private View statusCard(String icon, String label, int count, int color) {
        LinearLayout card = pixelCard(color, Color.rgb(15,23,42));
        card.setPadding(dp(12),dp(12),dp(12),dp(12));
        TextView i = text(icon + "  " + label, 14, Color.WHITE, true); i.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); card.addView(i);
        TextView c = text(String.valueOf(count), 30, Color.WHITE, true); c.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); card.addView(c);
        card.setOnClickListener(v -> showReminders());
        return card;
    }

    private LinearLayout pixelCard(int fill, int stroke) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12),dp(10),dp(12),dp(10));
        card.setBackground(pixelBackground(fill, stroke, 2));
        return card;
    }

    private GradientDrawable pixelBackground(int fill, int stroke, int strokeWidthDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setStroke(dp(strokeWidthDp), stroke); g.setCornerRadius(dp(7));
        return g;
    }

    private Button gameButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s); b.setAllCaps(false); b.setTextSize(16); b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setPadding(dp(12),dp(10),dp(12),dp(10));
        b.setBackground(pixelBackground(color, Color.rgb(15,23,42), 2));
        return b;
    }

    private Button miniButton(String s, int color) {
        Button b = gameButton(s, color); b.setTextSize(13); b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(10),dp(7),dp(10),dp(7)); return b;
    }

    private View gameInfoCard(String title, String body) {
        LinearLayout c = pixelCard(Color.WHITE, Color.rgb(148,163,184));
        c.addView(text(title, 16, NAVY, true));
        c.addView(text(body, 14, GRAY));
        return c;
    }

    private TextView chip(String s, int color) {
        TextView v = text(s, 12, Color.WHITE, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8),dp(5),dp(8),dp(5));
        v.setBackground(pixelBackground(color, Color.rgb(15,23,42), 1));
        return v;
    }

    private View emptyCard(String s) {
        LinearLayout c = pixelCard(Color.WHITE, Color.rgb(203,213,225));
        c.addView(text(s, 14, GRAY)); return c;
    }

    private EditText input(String hint, boolean password) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextSize(15); e.setTextColor(Color.rgb(15,23,42)); e.setHintTextColor(Color.rgb(100,116,139));
        e.setPadding(dp(10),dp(9),dp(10),dp(9)); e.setBackground(pixelBackground(Color.WHITE, Color.rgb(148,163,184), 1));
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private TextView text(String s, int size, int color) { return text(s,size,color,false); }
    private TextView text(String s, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); v.setLineSpacing(0,1.08f);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setPadding(dp(2),dp(3),dp(2),dp(3)); return v;
    }

    private LinearLayout hRow() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-2,1); p.setMargins(dp(3),dp(3),dp(3),dp(3)); return p; }
    private LinearLayout.LayoutParams marginParams(int w, int h, int top, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h); p.setMargins(dp(2),dp(top),dp(2),dp(bottom)); return p; }

    private void setMainScreen(LinearLayout page, int selected) {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
        LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(Color.rgb(244,249,255));
        shell.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        shell.addView(bottomNav(selected), new LinearLayout.LayoutParams(-1,-2));
        applyInsets(shell);
        setContentView(shell);
    }

    private void setEditorScreen(LinearLayout page) {
        ScrollView scroll = new ScrollView(this); scroll.addView(page); scroll.setBackgroundColor(Color.rgb(244,249,255));
        applyInsets(scroll); setContentView(scroll);
    }

    private View bottomNav(int selected) {
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL); nav.setPadding(dp(5),dp(6),dp(5),dp(7));
        nav.setBackground(pixelBackground(Color.WHITE, Color.rgb(148,163,184), 1));
        String[] labels = {"⌂\n首頁","▤\n採購單","♟\n提醒","⚙\n設定"};
        int[] screens = {SCREEN_HOME,SCREEN_PURCHASES,SCREEN_REMINDERS,SCREEN_SETTINGS};
        for (int i=0;i<labels.length;i++) {
            Button b = new Button(this); b.setAllCaps(false); b.setText(labels[i]); b.setTextSize(12); b.setGravity(Gravity.CENTER);
            b.setTextColor(screens[i]==selected ? BLUE : GRAY); b.setTypeface(Typeface.MONOSPACE, screens[i]==selected?Typeface.BOLD:Typeface.NORMAL);
            b.setBackgroundColor(Color.TRANSPARENT); final int s = screens[i];
            b.setOnClickListener(v -> { if(s==SCREEN_HOME)showDashboard(); else if(s==SCREEN_PURCHASES)showPurchases(""); else if(s==SCREEN_REMINDERS)showReminders(); else showSettings(); });
            nav.addView(b, new LinearLayout.LayoutParams(0,dp(58),1));
        }
        return nav;
    }

    private void applyInsets(View root) {
        if (Build.VERSION.SDK_INT >= 21) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top, bottom;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(0, top, 0, bottom);
                return insets;
            });
        }
    }

    private String afterLabel(String s, String... labels) {
        for (String label : labels) {
            int idx = s.indexOf(label);
            if (idx >= 0) {
                String x = s.substring(idx + label.length()).replaceFirst("^[：:\\s]+", "").trim();
                if (!empty(x)) return x;
            }
        }
        return "";
    }

    private String extractDate(String s) {
        if (s == null) return "";
        String x = s.replace('／','/').replace('。','.');
        Matcher m = Pattern.compile("(?<!\\d)(20\\d{2})[./\\-年](\\d{1,2})[./\\-月](\\d{1,2})(?:日)?(?!\\d)").matcher(x);
        if (!m.find()) return "";
        try {
            int y=Integer.parseInt(m.group(1)), mo=Integer.parseInt(m.group(2)), d=Integer.parseInt(m.group(3));
            if (mo<1||mo>12||d<1||d>31) return "";
            return String.format(Locale.TAIWAN,"%04d-%02d-%02d",y,mo,d);
        } catch(Exception e){ return ""; }
    }

    private String normalizeDateInput(String s) {
        if (empty(s)) return "";
        String d = extractDate(s);
        if (!empty(d)) return d;
        Matcher m = Pattern.compile("^(20\\d{2})-(\\d{1,2})-(\\d{1,2})$").matcher(s);
        if (m.find()) return String.format(Locale.TAIWAN,"%04d-%02d-%02d",Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
        return s;
    }

    private boolean isNumberish(String s) { return s != null && s.matches("[-+]?\\d[\\d,]*(?:\\.\\d+)?"); }
    private String cleanNumber(String s) { return s == null ? "" : s.replace(" ", "").trim(); }
    private boolean isUnit(String s) { return s != null && s.matches("(個|顆|台|支|組|件|箱|包|只|片|條|式|粒|盒|套|張|卷|桶|公斤|KG|PCS|PC|SET)"); }
    private String tidy(String s) { return s == null ? "" : s.replace('\u3000',' ').replaceAll("\\s+"," ").trim(); }
    private boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private String safe(String s, String fallback) { return empty(s) ? fallback : s; }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private static class Draft {
        String imagePath="", rawOcr="", vendor="", location="", orderNo="", purchaseDate="";
        List<PurchaseDbHelper.PurchaseItem> items = new ArrayList<>();
    }
    private static class Token { String text; Rect box; Token(String t, Rect b){text=t;box=b;} }
    private static class RowStart { int y; String lineNo; RowStart(int y,String n){this.y=y;this.lineNo=n;} }
    private static class ItemRow {
        LinearLayout root; EditText lineNo, description, qty, unit, unitPrice, subtotal, delivery, note; CheckBox completed;
    }
}
