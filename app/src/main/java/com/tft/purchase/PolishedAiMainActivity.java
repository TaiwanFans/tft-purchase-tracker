package com.tft.purchase;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.1.3 visual polish layer.
 * Keeps the existing EnhancedAiMainActivity behaviour intact and only improves hierarchy,
 * wording and touch presentation. Procurement/OCR/database/queue logic remains unchanged.
 */
public class PolishedAiMainActivity extends EnhancedAiMainActivity {
    private static final int NAVY = Color.rgb(15, 42, 92);
    private static final int BLUE = Color.rgb(37, 99, 235);
    private static final int GRAY = Color.rgb(71, 85, 105);
    private static final int RED = Color.rgb(220, 38, 38);
    private static final int ORANGE = Color.rgb(234, 88, 12);
    private static final int AMBER = Color.rgb(202, 138, 4);
    private static final int GREEN = Color.rgb(22, 163, 74);
    private static final int SOFT_BLUE = Color.rgb(239, 246, 255);
    private static final String DUE_TAG = "v213_due_polished";
    private static final String PURCHASE_TAG = "v213_purchase_polished";
    private static final String CONNECTION_TAG = "v213_connection_settings";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        schedulePolish();
    }

    @Override protected void onResume() {
        super.onResume();
        schedulePolish();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) schedulePolish();
    }

    private void schedulePolish() {
        uiHandler.removeCallbacks(polishRunnable);
        uiHandler.post(polishRunnable);
        uiHandler.postDelayed(polishRunnable, 180);
        uiHandler.postDelayed(polishRunnable, 650);
    }

    private final Runnable polishRunnable = () -> {
        if (getWindow() == null || getWindow().getDecorView() == null) return;
        View root = getWindow().getDecorView();
        rewriteLegacyCopy(root);
        injectConnectionSettings(root);
        polishBottomNav(root);
        polishDueCards(root);
        polishPurchaseCards(root);
    };

    /** Removes stale MiniCPM wording and makes the current cloud pipeline understandable. */
    private void rewriteLegacyCopy(View view) {
        if (view == null) return;
        if (view instanceof TextView) {
            TextView t = (TextView) view;
            String old = t.getText() == null ? "" : t.getText().toString();
            String next = old;
            if ("全益採購追蹤".equals(next)) next = "採購單追蹤";
            if (next.contains("ML KIT OCR + MINICPM-V 4.6")) next = next.replace("ML KIT OCR + MINICPM-V 4.6", "PP-OCRv6 Small + Gemini");
            if (next.contains("ML Kit OCR + MiniCPM-V 4.6")) next = next.replace("ML Kit OCR + MiniCPM-V 4.6", "PP-OCRv6 Small / ML Kit + Gemini");
            if (next.contains("ML Kit OCR 與 MiniCPM-V 4.6")) next = next.replace("ML Kit OCR 與 MiniCPM-V 4.6", "PP-OCRv6 Small、ML Kit 與 Gemini");
            if (next.contains("OCR + MiniCPM 正在背景分析")) next = next.replace("OCR + MiniCPM 正在背景分析", "OCR + Gemini 正在背景分析");
            if (next.contains("OCR + 本機 AI 自動處理")) next = "OCR + Gemini 自動辨識";
            if (next.contains("Google ML Kit 先讀取採購單文字，再由 MiniCPM-V 4.6")) {
                next = "手機先完成文件校正、PP-OCRv6 Small / ML Kit 與固定 8 欄定位，再交由 Gemini 核對完整頁面；最後仍以格線位置、OCR 證據與數學規則驗證。";
            }
            if (next.contains("AI 品項／規格／數量／交貨日期")) next = "品項／數量／交貨日期";
            if (next.contains("版本 2.0.12")) next = next.replace("版本 2.0.12", "版本 2.1.3");
            if (next.contains("AI 已改為 Google ML Kit OCR + MiniCPM-V 4.6")) {
                next = "Firebase AI Logic / Gemini 網路視覺已啟用；PP-OCRv6 Small、ML Kit、OpenCV 與固定 8 欄解析照常保留。";
            }
            if (next.contains("前往安裝本機 AI 模型")) next = "檢查 Firebase / Gemini 連線";
            if (next.contains("選擇採購單照片 → AI 自動填寫")) next = "掃描／選擇採購單 → AI 自動辨識";
            if (next.equals("＋ AI 分析新採購單")) next = "＋ 掃描／匯入採購單";
            if (!next.equals(old)) t.setText(next);

            if (t instanceof Button && "檢查 Firebase / Gemini 連線".contentEquals(t.getText())) {
                t.setOnClickListener(v -> openConnectionSettings());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) rewriteLegacyCopy(g.getChildAt(i));
        }
    }

    /** Firebase/App Check remains reachable from Settings even after first-time setup is skipped. */
    private void injectConnectionSettings(View root) {
        TextView section = findText(root, "AI 辨識");
        if (section == null || !(section.getParent() instanceof LinearLayout)) return;
        LinearLayout page = (LinearLayout) section.getParent();
        if (page.findViewWithTag(CONNECTION_TAG) != null) return;
        Button b = new Button(this);
        b.setTag(CONNECTION_TAG);
        b.setText("Firebase / Gemini 連線設定");
        b.setAllCaps(false);
        b.setTextSize(14.5f);
        b.setTextColor(Color.WHITE);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setMinHeight(dp(48));
        b.setBackground(round(BLUE, BLUE, 0, 14));
        b.setOnClickListener(v -> openConnectionSettings());
        int sectionIndex = page.indexOfChild(section);
        int insertAt = Math.min(page.getChildCount(), Math.max(0, sectionIndex + 2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(2), dp(6), dp(2), dp(10));
        page.addView(b, insertAt, lp);
    }

    private void openConnectionSettings() {
        Intent i = new Intent(this, FirebaseGeminiConnectionActivity.class);
        i.putExtra("force_connection_setup", true);
        startActivity(i);
    }

    private TextView findText(View view, String exact) {
        if (view instanceof TextView && exact.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                TextView found = findText(g.getChildAt(i), exact);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Bottom navigation becomes a clearer four-tab bar with a selected pill. */
    private void polishBottomNav(View root) {
        List<Button> tabs = new ArrayList<>();
        collectNavButtons(root, tabs);
        for (Button b : tabs) {
            String raw = b.getText() == null ? "" : b.getText().toString();
            String base = raw.replace("⌂ ", "").replace("▣ ", "").replace("⏰ ", "").replace("⚙ ", "");
            boolean selected = b.getCurrentTextColor() == BLUE || (b.getTypeface() != null && b.getTypeface().isBold());
            if ("首頁".equals(base)) b.setText("⌂ 首頁");
            else if ("採購單".equals(base)) b.setText("▣ 採購單");
            else if ("提醒".equals(base)) b.setText("⏰ 提醒");
            else if ("設定".equals(base)) b.setText("⚙ 設定");
            else continue;
            b.setTextSize(13.5f);
            b.setGravity(Gravity.CENTER);
            b.setPadding(dp(5), dp(7), dp(5), dp(7));
            b.setTextColor(selected ? BLUE : GRAY);
            b.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            b.setBackground(round(selected ? SOFT_BLUE : Color.TRANSPARENT,
                    selected ? Color.rgb(191, 219, 254) : Color.TRANSPARENT, selected ? 1 : 0, 14));
            ViewGroup.LayoutParams lp = b.getLayoutParams();
            if (lp != null) { lp.height = dp(56); b.setLayoutParams(lp); }
        }
    }

    private void collectNavButtons(View view, List<Button> out) {
        if (view instanceof Button) {
            String s = ((Button) view).getText() == null ? "" : ((Button) view).getText().toString();
            s = s.replace("⌂ ", "").replace("▣ ", "").replace("⏰ ", "").replace("⚙ ", "");
            if ("首頁".equals(s) || "採購單".equals(s) || "提醒".equals(s) || "設定".equals(s)) out.add((Button) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) collectNavButtons(g.getChildAt(i), out);
        }
    }

    /** Make the tracked item the hero instead of the vendor. */
    private void polishDueCards(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof LinearLayout) polishDueCardIfMatched((LinearLayout) child);
            polishDueCards(child);
        }
    }

    private void polishDueCardIfMatched(LinearLayout card) {
        if (DUE_TAG.equals(card.getTag())) return;
        TextView delivery = directTextStarting(card, "交貨日");
        if (delivery == null) return;
        TextView item = directItemText(card, delivery);
        LinearLayout top = directFirstLinear(card);
        if (item == null || top == null) return;

        int statusColor = statusColorFromTop(top);
        card.setTag(DUE_TAG);
        card.setPadding(dp(17), dp(14), dp(17), dp(14));
        card.setBackground(round(Color.WHITE, statusColor, 2, 16));

        int itemIndex = card.indexOfChild(item);
        if (itemIndex > 1) {
            card.removeView(item);
            card.addView(item, 1);
        }
        item.setTextSize(20);
        item.setTextColor(NAVY);
        item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        item.setLineSpacing(0, 1.10f);
        item.setPadding(dp(1), dp(8), dp(1), dp(6));

        String meta = delivery.getText() == null ? "" : delivery.getText().toString();
        meta = meta.replace("交貨日　", "交貨　");
        delivery.setText(meta);
        delivery.setTextSize(13.5f);
        delivery.setTextColor(GRAY);
        delivery.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        delivery.setPadding(dp(1), dp(2), dp(1), dp(4));

        TextView vendor = firstDirectText(top);
        if (vendor != null) {
            vendor.setTextSize(13.5f);
            vendor.setTextColor(GRAY);
            vendor.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            vendor.setPadding(dp(1), dp(1), dp(5), dp(3));
        }
        TextView status = lastDirectText(top);
        if (status != null && status != vendor) {
            status.setTextSize(13);
            status.setPadding(dp(10), dp(6), dp(10), dp(6));
            status.setBackground(round(statusColor, statusColor, 0, 11));
        }
    }

    /** Purchases list gets softer cards and clearer metadata. */
    private void polishPurchaseCards(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof LinearLayout) polishPurchaseCardIfMatched((LinearLayout) child);
            polishPurchaseCards(child);
        }
    }

    private void polishPurchaseCardIfMatched(LinearLayout card) {
        if (PURCHASE_TAG.equals(card.getTag()) || DUE_TAG.equals(card.getTag())) return;
        TextView meta = directTextStarting(card, "採購單號");
        if (meta == null) return;
        LinearLayout top = directFirstLinear(card);
        if (top == null) return;
        card.setTag(PURCHASE_TAG);
        card.setPadding(dp(17), dp(14), dp(17), dp(14));
        card.setBackground(round(Color.WHITE, Color.rgb(203, 213, 225), 1, 16));
        TextView vendor = firstDirectText(top);
        if (vendor != null) {
            vendor.setTextSize(17);
            vendor.setTextColor(NAVY);
            vendor.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        meta.setTextSize(13.5f);
        meta.setTextColor(GRAY);
        meta.setPadding(dp(1), dp(7), dp(1), dp(4));
        TextView summary = directTextContaining(card, "AI 品項");
        if (summary != null) {
            summary.setText(summary.getText().toString().replace("AI 品項", "品項"));
            summary.setTextSize(14.5f);
            summary.setTextColor(Color.rgb(30, 41, 59));
            summary.setPadding(dp(1), dp(4), dp(1), dp(3));
        }
    }

    private TextView directTextStarting(LinearLayout parent, String prefix) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (v instanceof TextView && !(v instanceof Button)) {
                String s = ((TextView) v).getText() == null ? "" : ((TextView) v).getText().toString();
                if (s.startsWith(prefix)) return (TextView) v;
            }
        }
        return null;
    }

    private TextView directTextContaining(LinearLayout parent, String term) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View v = parent.getChildAt(i);
            if (v instanceof TextView && !(v instanceof Button)) {
                String s = ((TextView) v).getText() == null ? "" : ((TextView) v).getText().toString();
                if (s.contains(term)) return (TextView) v;
            }
        }
        return null;
    }

    private TextView directItemText(LinearLayout card, TextView delivery) {
        for (int i = 0; i < card.getChildCount(); i++) {
            View v = card.getChildAt(i);
            if (v == delivery || !(v instanceof TextView) || v instanceof Button) continue;
            String s = ((TextView) v).getText() == null ? "" : ((TextView) v).getText().toString();
            if (s.startsWith("交貨日") || s.startsWith("採購單號") || s.contains("AI 品項")) continue;
            if (!s.trim().isEmpty()) return (TextView) v;
        }
        return null;
    }

    private LinearLayout directFirstLinear(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) if (parent.getChildAt(i) instanceof LinearLayout) return (LinearLayout) parent.getChildAt(i);
        return null;
    }

    private TextView firstDirectText(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) if (parent.getChildAt(i) instanceof TextView) return (TextView) parent.getChildAt(i);
        return null;
    }

    private TextView lastDirectText(LinearLayout parent) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) if (parent.getChildAt(i) instanceof TextView) return (TextView) parent.getChildAt(i);
        return null;
    }

    private int statusColorFromTop(LinearLayout top) {
        TextView status = lastDirectText(top);
        String s = status == null || status.getText() == null ? "" : status.getText().toString();
        if (s.contains("逾期")) return RED;
        if (s.contains("今天")) return ORANGE;
        if (s.matches(".*[123] 天後.*")) return AMBER;
        if (s.contains("已完成")) return GREEN;
        return BLUE;
    }

    private GradientDrawable round(int fill, int stroke, int widthDp, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (widthDp > 0) g.setStroke(dp(widthDp), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
