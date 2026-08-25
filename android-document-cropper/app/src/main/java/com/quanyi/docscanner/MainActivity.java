package com.quanyi.docscanner;

import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_IMAGE=1001;
    private static final int REQ_WRITE=1002;
    private static final int BG=Color.rgb(16,24,32);
    private static final int PANEL=Color.rgb(28,39,49);
    private static final int TEXT=Color.rgb(244,248,248);
    private static final int MUTED=Color.rgb(176,196,201);
    private static final int GREEN=Color.rgb(76,255,169);
    private static final int CYAN=Color.rgb(108,224,240);
    private enum Screen { HOME, CROP, RESULT }
    private Screen screen=Screen.HOME;
    private Bitmap sourceBitmap;
    private Bitmap croppedBitmap;
    private Bitmap displayBitmap;
    private PointF[] lastCorners;
    private DocumentCropView cropView;
    private ImageView resultImage;
    private Switch brightSwitch, sharpSwitch;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if(Build.VERSION.SDK_INT>=23)getWindow().getDecorView().setSystemUiVisibility(0);
        showHome();
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}

    private TextView text(String s,int sp,boolean bold){
        TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(TEXT);v.setPadding(0,dp(6),0,dp(6));
        v.setTypeface(Typeface.MONOSPACE,bold?Typeface.BOLD:Typeface.NORMAL);return v;
    }

    private Button button(String s,boolean primary){
        Button b=new Button(this);b.setText(s);b.setTextSize(15);b.setAllCaps(false);b.setMinHeight(dp(50));b.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        GradientDrawable g=new GradientDrawable();g.setShape(GradientDrawable.RECTANGLE);g.setCornerRadius(dp(2));
        if(primary){g.setColor(GREEN);g.setStroke(dp(2),GREEN);b.setTextColor(BG);}else{g.setColor(PANEL);g.setStroke(dp(2),CYAN);b.setTextColor(TEXT);}b.setBackground(g);
        b.setPadding(dp(8),dp(4),dp(8),dp(4));return b;
    }

    private LinearLayout vertical(int pad){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(pad),dp(pad),dp(pad),dp(pad));l.setBackgroundColor(BG);return l;}
    private void gap(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}

    private void setSafeContentView(View content){
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(BG);frame.addView(content,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            int l=0,t=0,r=0,b=0;
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets s=insets.getInsets(WindowInsets.Type.systemBars());l=s.left;t=s.top;r=s.right;b=s.bottom;}
            else{l=insets.getSystemWindowInsetLeft();t=insets.getSystemWindowInsetTop();r=insets.getSystemWindowInsetRight();b=insets.getSystemWindowInsetBottom();}
            frame.setPadding(l,t,r,b);return insets;
        });
        setContentView(frame);frame.requestApplyInsets();
    }

    private TextView badge(String s){TextView v=text(s,13,true);v.setTextColor(GREEN);v.setGravity(Gravity.CENTER);v.setPadding(dp(8),dp(7),dp(8),dp(7));GradientDrawable g=new GradientDrawable();g.setColor(PANEL);g.setStroke(dp(2),GREEN);v.setBackground(g);return v;}

    private void showHome(){
        screen=Screen.HOME;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);LinearLayout root=vertical(18);scroll.addView(root);
        ImageView icon=new ImageView(this);icon.setImageResource(com.quanyi.docscanner.R.drawable.ic_pixel_document);icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);root.addView(icon,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(92)));
        TextView title=text("文件裁切器",27,true);title.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(title);
        TextView version=text("PIXEL DOC TOOL  v1.1",13,true);version.setTextColor(GREEN);version.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(version);
        TextView sub=text("採購單・A4・名片・收據",15,false);sub.setTextColor(MUTED);sub.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(sub);
        gap(root,18);
        LinearLayout badges=new LinearLayout(this);badges.setOrientation(LinearLayout.HORIZONTAL);badges.addView(badge("本機處理"),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));View spacer=new View(this);badges.addView(spacer,new LinearLayout.LayoutParams(dp(8),1));badges.addView(badge("不需登入"),new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));root.addView(badges);
        gap(root,18);
        TextView steps=text("[1] 相簿選圖\n[2] 自動抓角 + 手動微調\n[3] 透視拉正 + 影像增強\n[4] LINE 分享 / 儲存相簿",16,false);steps.setLineSpacing(dp(5),1f);steps.setTextColor(TEXT);root.addView(steps);
        gap(root,18);
        Button pick=button("▶  從相簿選取照片",true);pick.setOnClickListener(v->pickImage());root.addView(pick,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(56)));
        gap(root,16);
        TextView note=text("TIP  文件四周最好留一點背景，自動抓角會更穩。",13,false);note.setTextColor(MUTED);root.addView(note);
        setSafeContentView(scroll);
    }

    private void pickImage(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,REQ_IMAGE);}

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_IMAGE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            ProgressDialog d=ProgressDialog.show(this,"分析文件","正在搜尋紙張邊界…",true,false);
            new Thread(()->{try{
                Bitmap b=ImageUtils.loadBitmap(getContentResolver(),uri,4096);PointF[] corners=ImageUtils.detectDocumentCorners(b);
                runOnUiThread(()->{d.dismiss();if(sourceBitmap!=null&&!sourceBitmap.isRecycled())sourceBitmap.recycle();sourceBitmap=b;lastCorners=corners;showCrop(corners);});
            }catch(Exception e){runOnUiThread(()->{d.dismiss();Toast.makeText(this,"無法開啟這張照片，請換一張再試。",Toast.LENGTH_LONG).show();});}}).start();
        }
    }

    private void showCrop(PointF[] corners){
        screen=Screen.CROP;LinearLayout root=vertical(10);
        TextView title=text("四角校正",20,true);title.setGravity(Gravity.CENTER);root.addView(title);
        TextView hint=text("拖曳方形控制點，讓綠框貼齊紙張。",13,false);hint.setTextColor(MUTED);hint.setGravity(Gravity.CENTER);root.addView(hint);
        cropView=new DocumentCropView(this);cropView.setDocument(sourceBitmap,corners);root.addView(cropView,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,dp(8),0,0);Button auto=button("↻ 自動抓角",false);Button crop=button("✓ 確認裁切",true);
        row.addView(auto,new LinearLayout.LayoutParams(0,dp(54),1f));View spacer=new View(this);row.addView(spacer,new LinearLayout.LayoutParams(dp(8),1));row.addView(crop,new LinearLayout.LayoutParams(0,dp(54),1f));root.addView(row);
        auto.setOnClickListener(v->{ProgressDialog d=ProgressDialog.show(this,"重新分析","正在尋找最佳文件邊界…",true,false);new Thread(()->{PointF[] p=ImageUtils.detectDocumentCorners(sourceBitmap);runOnUiThread(()->{d.dismiss();lastCorners=p;cropView.setCorners(p);});}).start();});
        crop.setOnClickListener(v->doCrop());setSafeContentView(root);
    }

    private void doCrop(){
        if(cropView==null)return;PointF[] p=cropView.getCorners();lastCorners=p;ProgressDialog d=ProgressDialog.show(this,"裁切中","正在拉正文件…",true,false);
        new Thread(()->{try{Bitmap out=ImageUtils.perspectiveCrop(sourceBitmap,p);runOnUiThread(()->{d.dismiss();if(croppedBitmap!=null&&!croppedBitmap.isRecycled())croppedBitmap.recycle();croppedBitmap=out;showResult();});}catch(Exception e){runOnUiThread(()->{d.dismiss();Toast.makeText(this,"裁切失敗，請重新調整四個角。",Toast.LENGTH_LONG).show();});}}).start();
    }

    private void showResult(){
        screen=Screen.RESULT;LinearLayout page=vertical(10);TextView title=text("處理完成",20,true);title.setGravity(Gravity.CENTER);page.addView(title);
        int imageHeight=Math.max(dp(220),Math.min(dp(390),getResources().getDisplayMetrics().heightPixels/2));
        resultImage=new ImageView(this);resultImage.setAdjustViewBounds(false);resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);resultImage.setBackgroundColor(PANEL);page.addView(resultImage,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,imageHeight));
        gap(page,8);TextView mode=text("影像增強",14,true);mode.setTextColor(GREEN);page.addView(mode);
        LinearLayout switches=new LinearLayout(this);switches.setOrientation(LinearLayout.HORIZONTAL);brightSwitch=new Switch(this);brightSwitch.setText("亮度＋");brightSwitch.setTextColor(TEXT);brightSwitch.setTextSize(14);brightSwitch.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);brightSwitch.setChecked(false);sharpSwitch=new Switch(this);sharpSwitch.setText("清晰＋");sharpSwitch.setTextColor(TEXT);sharpSwitch.setTextSize(14);sharpSwitch.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);sharpSwitch.setChecked(true);switches.addView(brightSwitch,new LinearLayout.LayoutParams(0,dp(54),1f));switches.addView(sharpSwitch,new LinearLayout.LayoutParams(0,dp(54),1f));page.addView(switches);
        TextView note=text("亮度＋：提升暗部與紙張對比｜清晰＋：強化文字邊緣",12,false);note.setTextColor(MUTED);page.addView(note);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(BG);shell.addView(scroll,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        LinearLayout actions=vertical(8);LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);Button line=button("LINE 分享",false);Button save=button("儲存相簿",true);row.addView(line,new LinearLayout.LayoutParams(0,dp(52),1f));View spacer=new View(this);row.addView(spacer,new LinearLayout.LayoutParams(dp(8),1));row.addView(save,new LinearLayout.LayoutParams(0,dp(52),1f));actions.addView(row);Button again=button("重新選照片",false);actions.addView(again,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(48)));shell.addView(actions,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        brightSwitch.setOnCheckedChangeListener((b,c)->refreshEnhanced());sharpSwitch.setOnCheckedChangeListener((b,c)->refreshEnhanced());line.setOnClickListener(v->shareLine());save.setOnClickListener(v->saveCurrent());again.setOnClickListener(v->pickImage());setSafeContentView(shell);refreshEnhanced();
    }

    private void refreshEnhanced(){
        if(croppedBitmap==null||resultImage==null)return;boolean bright=brightSwitch!=null&&brightSwitch.isChecked(),sharp=sharpSwitch!=null&&sharpSwitch.isChecked();
        new Thread(()->{Bitmap b=ImageUtils.enhance(croppedBitmap,bright,sharp);runOnUiThread(()->{if(displayBitmap!=null&&displayBitmap!=croppedBitmap&&!displayBitmap.isRecycled())displayBitmap.recycle();displayBitmap=b;resultImage.setImageBitmap(displayBitmap);});}).start();
    }

    private Bitmap currentBitmap(){return displayBitmap!=null?displayBitmap:croppedBitmap;}

    private void shareLine(){
        Bitmap b=currentBitmap();if(b==null)return;try{File dir=new File(getCacheDir(),"shared");if(!dir.exists())dir.mkdirs();File f=new File(dir,"document_"+System.currentTimeMillis()+".jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,96,out);}Uri u=new Uri.Builder().scheme("content").authority(getPackageName()+".share").appendPath("images").appendPath(f.getName()).build();Intent share=new Intent(Intent.ACTION_SEND);share.setType("image/jpeg");share.putExtra(Intent.EXTRA_STREAM,u);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newUri(getContentResolver(),"document",u));share.setPackage("jp.naver.line.android");try{startActivity(share);}catch(Exception e){share.setPackage(null);startActivity(Intent.createChooser(share,"分享文件"));}}catch(Exception e){Toast.makeText(this,"分享失敗，請先嘗試儲存到相簿。",Toast.LENGTH_LONG).show();}
    }

    private void saveCurrent(){if(Build.VERSION.SDK_INT<29&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_WRITE);return;}saveToGallery();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_WRITE&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)saveToGallery();}

    private void saveToGallery(){
        Bitmap b=currentBitmap();if(b==null)return;ProgressDialog d=ProgressDialog.show(this,"儲存中","正在存入手機相簿…",true,false);
        new Thread(()->{try{String name="文件_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.TAIWAN).format(new Date())+".jpg";if(Build.VERSION.SDK_INT>=29){ContentValues cv=new ContentValues();cv.put(MediaStore.Images.Media.DISPLAY_NAME,name);cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");cv.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/文件裁切器");cv.put(MediaStore.Images.Media.IS_PENDING,1);Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);if(uri==null)throw new Exception();try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null||!b.compress(Bitmap.CompressFormat.JPEG,96,out))throw new Exception();}cv.clear();cv.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(uri,cv,null,null);}else{File base=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);File dir=new File(base,"文件裁切器");if(!dir.exists())dir.mkdirs();File f=new File(dir,name);try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,96,out);}android.media.MediaScannerConnection.scanFile(this,new String[]{f.getAbsolutePath()},new String[]{"image/jpeg"},null);}runOnUiThread(()->{d.dismiss();Toast.makeText(this,"已儲存到相簿「文件裁切器」",Toast.LENGTH_LONG).show();});}catch(Exception e){runOnUiThread(()->{d.dismiss();Toast.makeText(this,"儲存失敗，請確認手機儲存空間。",Toast.LENGTH_LONG).show();});}}).start();
    }

    @Override public void onBackPressed(){if(screen==Screen.RESULT&&sourceBitmap!=null){showCrop(lastCorners!=null?lastCorners:ImageUtils.defaultCorners(sourceBitmap));return;}if(screen==Screen.CROP){showHome();return;}super.onBackPressed();}
    @Override protected void onDestroy(){super.onDestroy();if(sourceBitmap!=null&&!sourceBitmap.isRecycled())sourceBitmap.recycle();if(croppedBitmap!=null&&croppedBitmap!=sourceBitmap&&!croppedBitmap.isRecycled())croppedBitmap.recycle();if(displayBitmap!=null&&displayBitmap!=croppedBitmap&&displayBitmap!=sourceBitmap&&!displayBitmap.isRecycled())displayBitmap.recycle();}
}
