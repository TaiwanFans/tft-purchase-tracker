package com.tft.purchase;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 101;
    private static final int REQ_CAMERA = 102;
    private static final int REQ_NOTIFY = 103;
    private final String USERNAME = "TFT0000";

    private PurchaseDbHelper db;
    private SharedPreferences prefs;
    private boolean loggedIn = false;
    private long editingId = -1;
    private String currentImagePath = "";

    private EditText vendorEt, locationEt, itemsEt, deliveryEt, purchaseEt, signatureEt, notesEt;
    private CheckBox completedCb;
    private ImageView preview;
    private TextView ocrTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        db = new PurchaseDbHelper(this);
        prefs = getSharedPreferences("tft_settings", MODE_PRIVATE);
        ensureCredentials();
        requestNotificationPermission();
        scheduleDailyReminder();
        showLogin();
    }

    private void showLogin() {
        LinearLayout root = baseColumn();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title("全益採購追蹤"));
        TextView sub = text("公司內部採購管理\n初始帳號：TFT0000", 16);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub);
        EditText user = input("帳號", false);
        EditText pass = input("密碼", true);
        root.addView(user);
        root.addView(pass);
        Button login = button("登入");
        login.setOnClickListener(v -> {
            if (USERNAME.equals(user.getText().toString().trim()) && verifyPassword(pass.getText().toString())) {
                loggedIn = true;
                showDashboard("");
            } else {
                toast("帳號或密碼錯誤");
            }
        });
        root.addView(login);
        setScrollable(root);
    }

    private void showDashboard(String search) {
        if (!loggedIn) { showLogin(); return; }
        LinearLayout root = baseColumn();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = title("全益採購追蹤");
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button settings = button("設定");
        top.addView(settings);
        root.addView(top);
        settings.setOnClickListener(v -> showSettings());

        List<PurchaseDbHelper.Purchase> all = db.list(search);
        int overdue = 0, today = 0, soon = 0, open = 0;
        for (PurchaseDbHelper.Purchase p : all) {
            if (p.completed) continue;
            open++;
            String s = statusOf(p);
            if (s.startsWith("已逾期")) overdue++;
            else if (s.equals("今天交貨")) today++;
            else if (s.startsWith("即將交貨")) soon++;
        }
        TextView stats = text("已逾期 " + overdue + "　今天 " + today + "　即將交貨 " + soon + "　未完成 " + open, 16);
        stats.setPadding(16, 20, 16, 20);
        stats.setBackgroundColor(Color.rgb(242, 246, 250));
        root.addView(stats);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText searchEt = input("搜尋廠商／地址／品項", false);
        searchEt.setText(search == null ? "" : search);
        searchRow.addView(searchEt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button searchBtn = button("搜尋");
        searchRow.addView(searchBtn);
        root.addView(searchRow);
        searchBtn.setOnClickListener(v -> showDashboard(searchEt.getText().toString()));

        Button add = button("＋ 拍照／新增採購單");
        add.setTextSize(18);
        add.setOnClickListener(v -> showEditor(null));
        root.addView(add);

        if (all.isEmpty()) root.addView(text("目前沒有採購資料。", 16));
        for (PurchaseDbHelper.Purchase p : all) root.addView(purchaseCard(p));
        setScrollable(root);
    }

    private View purchaseCard(PurchaseDbHelper.Purchase p) {
        Button b = new Button(this);
        String status = p.completed ? "已完成" : statusOf(p);
        String sign = empty(p.signatureReturnDate) ? "未填" : p.signatureReturnDate;
        b.setAllCaps(false);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setText(status + "\n" + safe(p.vendorName, "未辨識廠商") + "｜" + safe(p.location, "未填地點") +
                "\n交貨：" + safe(p.deliveryDate, "未填") + "｜採購：" + safe(p.purchaseDate, "未填") +
                "\n" + safe(p.items, "未填品項") + "\n簽名回傳：" + sign);
        b.setPadding(20, 18, 20, 18);
        b.setOnClickListener(v -> showEditor(p));
        return b;
    }

    private void showEditor(PurchaseDbHelper.Purchase p) {
        editingId = p == null ? -1 : p.id;
        currentImagePath = p == null ? "" : safe(p.imagePath, "");
        LinearLayout root = baseColumn();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("← 返回");
        top.addView(back);
        TextView tt = title(p == null ? "新增採購單" : "採購單詳情／編輯");
        top.addView(tt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(top);
        back.setOnClickListener(v -> showDashboard(""));
        root.addView(text("拍照或選圖後會自動 OCR。辨識結果請人工確認，再按儲存。", 15));

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight(dp(260));
        root.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)));
        refreshPreview();

        LinearLayout imgBtns = new LinearLayout(this);
        imgBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button camera = button("拍照");
        Button pick = button("相簿選圖");
        Button ocr = button("重新辨識");
        imgBtns.addView(camera, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        imgBtns.addView(pick, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        imgBtns.addView(ocr, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(imgBtns);
        camera.setOnClickListener(v -> openCamera());
        pick.setOnClickListener(v -> openPicker());
        ocr.setOnClickListener(v -> runOcr());

        vendorEt = input("廠商名稱", false);
        locationEt = input("廠商所在地／地址", false);
        itemsEt = input("產品品項與數量（可多行）", false); itemsEt.setMinLines(3);
        deliveryEt = input("交貨日期 YYYY-MM-DD", false);
        purchaseEt = input("採購日期 YYYY-MM-DD", false);
        signatureEt = input("廠務簽名回傳日期 YYYY-MM-DD（人工填寫）", false);
        notesEt = input("備註", false); notesEt.setMinLines(2);
        completedCb = new CheckBox(this); completedCb.setText("已完成");
        ocrTv = text("OCR 原始文字會顯示在這裡", 13);

        for (View v : Arrays.asList(vendorEt, locationEt, itemsEt, deliveryEt, purchaseEt, signatureEt, notesEt, completedCb, ocrTv)) root.addView(v);

        if (p != null) {
            vendorEt.setText(p.vendorName); locationEt.setText(p.location); itemsEt.setText(p.items);
            deliveryEt.setText(p.deliveryDate); purchaseEt.setText(p.purchaseDate); signatureEt.setText(p.signatureReturnDate);
            notesEt.setText(p.notes); completedCb.setChecked(p.completed); ocrTv.setText(safe(p.rawOcr, ""));
        }

        Button save = button("確認儲存");
        save.setTextSize(18);
        save.setOnClickListener(v -> savePurchase());
        root.addView(save);
        if (p != null) {
            Button del = button("刪除這筆採購單");
            del.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("確認刪除？")
                    .setMessage("刪除後無法復原。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("刪除", (d, w) -> { db.delete(p.id); showDashboard(""); }).show());
            root.addView(del);
        }
        setScrollable(root);
    }

    private void savePurchase() {
        PurchaseDbHelper.Purchase p = editingId > 0 ? db.get(editingId) : new PurchaseDbHelper.Purchase();
        if (p == null) p = new PurchaseDbHelper.Purchase();
        p.vendorName = vendorEt.getText().toString().trim();
        p.location = locationEt.getText().toString().trim();
        p.items = itemsEt.getText().toString().trim();
        p.deliveryDate = deliveryEt.getText().toString().trim();
        p.purchaseDate = purchaseEt.getText().toString().trim();
        p.signatureReturnDate = signatureEt.getText().toString().trim();
        p.notes = notesEt.getText().toString().trim();
        p.completed = completedCb.isChecked();
        p.imagePath = currentImagePath;
        p.rawOcr = ocrTv.getText().toString();
        if (editingId > 0) { p.id = editingId; db.update(p); } else db.insert(p);
        toast("已儲存");
        showDashboard("");
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, REQ_PICK);
    }

    private void openCamera() {
        Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (i.resolveActivity(getPackageManager()) != null) startActivityForResult(i, REQ_CAMERA);
        else toast("找不到相機 APP");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        try {
            if (requestCode == REQ_PICK && data.getData() != null) {
                currentImagePath = copyUriToInternal(data.getData());
                refreshPreview();
                runOcr();
            } else if (requestCode == REQ_CAMERA && data.getExtras() != null) {
                Bitmap bm = (Bitmap) data.getExtras().get("data");
                if (bm != null) {
                    currentImagePath = saveBitmap(bm);
                    refreshPreview();
                    runOcr();
                }
            }
        } catch (Exception e) {
            toast("圖片處理失敗：" + e.getMessage());
        }
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

    private String saveBitmap(Bitmap bm) throws Exception {
        File dir = new File(getFilesDir(), "purchase_images");
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, UUID.randomUUID() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bm.compress(Bitmap.CompressFormat.JPEG, 95, fos);
        }
        return out.getAbsolutePath();
    }

    private void refreshPreview() {
        if (preview == null) return;
        if (empty(currentImagePath) || !new File(currentImagePath).exists()) {
            preview.setImageDrawable(null);
            preview.setBackgroundColor(Color.rgb(238,238,238));
            return;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true; BitmapFactory.decodeFile(currentImagePath, o);
        int sample = 1; while (o.outWidth / sample > 1200 || o.outHeight / sample > 1200) sample *= 2;
        o.inJustDecodeBounds = false; o.inSampleSize = sample;
        preview.setImageBitmap(BitmapFactory.decodeFile(currentImagePath, o));
    }

    private void runOcr() {
        if (empty(currentImagePath) || !new File(currentImagePath).exists()) { toast("請先拍照或選擇圖片"); return; }
        toast("正在辨識採購單…");
        try {
            InputImage image = InputImage.fromFilePath(this, Uri.fromFile(new File(currentImagePath)));
            TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
            recognizer.process(image)
                    .addOnSuccessListener(result -> {
                        String raw = result.getText();
                        ocrTv.setText(raw);
                        Parsed parsed = parseOcr(result);
                        if (!empty(parsed.vendor)) vendorEt.setText(parsed.vendor);
                        if (!empty(parsed.location)) locationEt.setText(parsed.location);
                        if (!empty(parsed.items)) itemsEt.setText(parsed.items);
                        if (!empty(parsed.delivery)) deliveryEt.setText(parsed.delivery);
                        if (!empty(parsed.purchase)) purchaseEt.setText(parsed.purchase);
                        toast("辨識完成，請人工確認");
                        recognizer.close();
                    })
                    .addOnFailureListener(e -> { toast("OCR 失敗，可改用手動輸入：" + e.getMessage()); recognizer.close(); });
        } catch (Exception e) { toast("OCR 啟動失敗：" + e.getMessage()); }
    }

    private static class Parsed { String vendor="", location="", items="", delivery="", purchase=""; }

    private Parsed parseOcr(Text result) {
        Parsed p = new Parsed();
        List<String> lines = new ArrayList<>();
        for (Text.TextBlock b : result.getTextBlocks()) for (Text.Line l : b.getLines()) lines.add(l.getText().trim());
        Pattern qty = Pattern.compile("(.{1,30}?)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(個|顆|台|支|組|件|箱|包|只|片|條|PCS|PC|SET|粒)", Pattern.CASE_INSENSITIVE);
        List<String> itemLines = new ArrayList<>();
        for (String line : lines) {
            if (empty(line)) continue;
            String normalizedDate = extractDate(line);
            if (empty(p.delivery) && (line.contains("交貨") || line.contains("交期") || line.contains("交貨日")) && !empty(normalizedDate)) p.delivery = normalizedDate;
            if (empty(p.purchase) && (line.contains("採購日期") || line.contains("採購日") || line.startsWith("日期") || line.contains("下單日")) && !empty(normalizedDate)) p.purchase = normalizedDate;
            if (empty(p.location) && (line.contains("地址") || containsTaiwanPlace(line))) p.location = cleanAfterLabel(line, "地址");
            if (empty(p.vendor) && !line.contains("採購單") && !line.contains("地址") &&
                    (line.contains("有限公司") || line.contains("股份有限公司") || line.contains("工程行") || line.contains("企業社") || line.endsWith("公司") || line.endsWith("工廠"))) p.vendor = cleanVendor(line);
            Matcher m = qty.matcher(line);
            if (m.find() && !line.contains("電話") && !line.contains("統編")) itemLines.add(line);
        }
        if (empty(p.purchase)) {
            for (String line : lines) {
                String d = extractDate(line);
                if (!empty(d) && !d.equals(p.delivery)) { p.purchase = d; break; }
            }
        }
        if (!itemLines.isEmpty()) p.items = join(itemLines, "\n");
        return p;
    }

    private String cleanVendor(String s) {
        return s.replaceAll("^(廠商|供應商|公司名稱)[:：\\s]*", "").trim();
    }

    private String cleanAfterLabel(String s, String label) {
        int i = s.indexOf(label);
        if (i >= 0) {
            String x = s.substring(i + label.length()).replaceFirst("^[:：\\s]+", "").trim();
            if (!empty(x)) return x;
        }
        return s.trim();
    }

    private boolean containsTaiwanPlace(String s) {
        String[] places = {"台北","臺北","新北","桃園","新竹","苗栗","台中","臺中","彰化","南投","雲林","嘉義","台南","臺南","高雄","屏東","宜蘭","花蓮","台東","臺東","澎湖"};
        for (String x : places) if (s.contains(x)) return true;
        return false;
    }

    private String extractDate(String s) {
        Matcher m = Pattern.compile("(?<!\\d)(\\d{2,4})[./\\-年](\\d{1,2})[./\\-月](\\d{1,2})(?:日)?(?!\\d)").matcher(s);
        if (!m.find()) return "";
        try {
            int y = Integer.parseInt(m.group(1)); int mo = Integer.parseInt(m.group(2)); int d = Integer.parseInt(m.group(3));
            if (y < 1911) y += 1911;
            if (y < 2000 || y > 2100 || mo < 1 || mo > 12 || d < 1 || d > 31) return "";
            return String.format(Locale.TAIWAN, "%04d-%02d-%02d", y, mo, d);
        } catch (Exception e) { return ""; }
    }

    private void showSettings() {
        LinearLayout root = baseColumn();
        Button back = button("← 返回首頁"); root.addView(back); back.setOnClickListener(v -> showDashboard(""));
        root.addView(title("設定／帳號安全"));
        root.addView(text("登入帳號固定為 TFT0000。建議第一次登入後立即修改初始密碼。", 15));

        EditText days = input("提前幾天提醒", false);
        days.setInputType(InputType.TYPE_CLASS_NUMBER);
        days.setText(String.valueOf(prefs.getInt("reminder_days", 5)));
        root.addView(days);
        Button saveDays = button("儲存提醒天數");
        saveDays.setOnClickListener(v -> {
            try { prefs.edit().putInt("reminder_days", Math.max(0, Integer.parseInt(days.getText().toString()))).apply(); toast("已更新提醒設定"); }
            catch (Exception e) { toast("請輸入數字"); }
        });
        root.addView(saveDays);

        root.addView(text("修改密碼", 20));
        EditText current = input("目前密碼", true);
        EditText newer = input("新密碼（至少 8 碼）", true);
        EditText confirm = input("再次輸入新密碼", true);
        root.addView(current); root.addView(newer); root.addView(confirm);
        Button change = button("修改密碼");
        change.setOnClickListener(v -> {
            String n = newer.getText().toString();
            if (!verifyPassword(current.getText().toString())) { toast("目前密碼錯誤"); return; }
            if (n.length() < 8) { toast("新密碼至少 8 碼"); return; }
            if (!n.equals(confirm.getText().toString())) { toast("兩次新密碼不一致"); return; }
            setPassword(n); current.setText(""); newer.setText(""); confirm.setText(""); toast("密碼已修改");
        });
        root.addView(change);
        Button logout = button("登出"); logout.setOnClickListener(v -> { loggedIn=false; showLogin(); }); root.addView(logout);
        setScrollable(root);
    }

    private String statusOf(PurchaseDbHelper.Purchase p) {
        if (p.completed) return "已完成";
        if (empty(p.deliveryDate)) return "未設定交貨日";
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN);
            Date target = sdf.parse(p.deliveryDate); Date today = sdf.parse(sdf.format(new Date()));
            if (target == null || today == null) return "日期格式待確認";
            long diff = TimeUnit.MILLISECONDS.toDays(target.getTime() - today.getTime());
            if (diff < 0) return "已逾期 " + (-diff) + " 天";
            if (diff == 0) return "今天交貨";
            if (diff <= prefs.getInt("reminder_days", 5)) return "即將交貨（" + diff + " 天）";
            return "未完成";
        } catch (Exception e) { return "日期格式待確認"; }
    }

    private void ensureCredentials() {
        if (!prefs.contains("password_hash")) setPassword("0000");
        if (!prefs.contains("reminder_days")) prefs.edit().putInt("reminder_days", 5).apply();
    }

    private void setPassword(String password) {
        try {
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt);
            prefs.edit().putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private boolean verifyPassword(String password) {
        try {
            byte[] salt = Base64.decode(prefs.getString("password_salt", ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(prefs.getString("password_hash", ""), Base64.NO_WRAP);
            return MessageDigest.isEqual(expected, pbkdf2(password.toCharArray(), salt));
        } catch (Exception e) { return false; }
    }

    private byte[] pbkdf2(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, 120000, 256);
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
    }

    private void scheduleDailyReminder() {
        Calendar c = Calendar.getInstance(); c.set(Calendar.HOUR_OF_DAY, 8); c.set(Calendar.MINUTE, 30); c.set(Calendar.SECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
        Intent i = new Intent(this, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(this, 77, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, c.getTimeInMillis(), AlarmManager.INTERVAL_DAY, pi);
    }

    private LinearLayout baseColumn() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(16), dp(16), dp(16), dp(24)); return root;
    }
    private void setScrollable(View content) { ScrollView s = new ScrollView(this); s.addView(content); setContentView(s); }
    private TextView title(String s) { TextView v = text(s, 24); v.setTypeface(Typeface.DEFAULT, Typeface.BOLD); v.setPadding(0, dp(8), 0, dp(12)); return v; }
    private TextView text(String s, int size) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(30,30,30)); v.setPadding(dp(4), dp(6), dp(4), dp(6)); return v; }
    private EditText input(String hint, boolean password) { EditText e = new EditText(this); e.setHint(hint); e.setTextSize(17); e.setPadding(dp(10), dp(10), dp(10), dp(10)); if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return e; }
    private Button button(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(16); return b; }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    private boolean empty(String s) { return s == null || s.trim().isEmpty(); }
    private String safe(String s, String fallback) { return empty(s) ? fallback : s; }
    private String join(List<String> xs, String sep) { StringBuilder b = new StringBuilder(); for (String x : xs) { if (b.length()>0) b.append(sep); b.append(x); } return b.toString(); }
}
