package com.tft.purchase;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SafeMainActivityV204 extends TftActivityV204 {
    @Override protected void onCreate(Bundle savedInstanceState) {
        try { super.onCreate(savedInstanceState); }
        catch (Throwable t) { showStartupFailure(t); }
    }

    private void showStartupFailure(Throwable error) {
        String details = buildDetails(error);
        try {
            LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp2(18),dp2(28),dp2(18),dp2(28)); page.setBackgroundColor(Color.rgb(248,250,252));
            page.addView(label("APP 啟動發生錯誤",24,Color.rgb(185,28,28),true));
            page.addView(label("請把下方錯誤資訊複製給我，我可以直接定位。",15,Color.rgb(51,65,85),false));
            TextView info=label(details,12,Color.rgb(15,23,42),false); info.setTextIsSelectable(true); info.setPadding(dp2(12),dp2(12),dp2(12),dp2(12)); info.setBackgroundColor(Color.WHITE); page.addView(info);
            Button copy=button("複製錯誤資訊"); copy.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("全益採購追蹤啟動錯誤",details));Toast.makeText(this,"已複製",Toast.LENGTH_SHORT).show();});page.addView(copy);
            Button restart=button("重新啟動 APP");restart.setOnClickListener(v->{Intent i=new Intent(this,SafeMainActivityV204.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);startActivity(i);finish();});page.addView(restart);
            ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        } catch(Throwable ignored){TextView v=new TextView(this);v.setText(details);v.setPadding(24,48,24,24);setContentView(v);}
    }

    private String buildDetails(Throwable t){StringBuilder s=new StringBuilder();s.append("版本：2.0.4\n");s.append("Android：").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\n");s.append("裝置：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");Throwable cur=t;int depth=0;while(cur!=null&&depth<5){s.append(depth==0?"錯誤：":"Caused by：").append(cur.getClass().getName()).append("\n").append(String.valueOf(cur.getMessage())).append("\n");StackTraceElement[] stack=cur.getStackTrace();int max=Math.min(stack==null?0:stack.length,15);for(int i=0;i<max;i++)s.append("  at ").append(stack[i]).append("\n");cur=cur.getCause();depth++;}return s.toString();}
    private TextView label(String text,int sp,int color,boolean bold){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.START);if(bold)v.setTypeface(v.getTypeface(),android.graphics.Typeface.BOLD);return v;}
    private Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(15);return b;}
    private int dp2(int v){return(int)(v*getResources().getDisplayMetrics().density+0.5f);}
}
