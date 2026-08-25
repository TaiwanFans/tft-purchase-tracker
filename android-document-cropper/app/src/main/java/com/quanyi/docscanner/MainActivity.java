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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
    private enum Screen { HOME, CROP, RESULT }
    private Screen screen=Screen.HOME;
    private Bitmap sourceBitmap;
    private Bitmap croppedBitmap;
    private Bitmap displayBitmap;
    private DocumentCropView cropView;
    private ImageView resultImage;
    private Switch brightSwitch, sharpSwitch;

    @Override public void onCreate(Bundle b){super.onCreate(b);showHome();}

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(30,30,30));v.setPadding(0,dp(8),0,dp(8));if(bold)v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(18);b.setAllCaps(false);b.setMinHeight(dp(58));return b;}
    private LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(20),dp(20),dp(20),dp(20));return l;}
    private void gap(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}

    private void showHome(){
        screen=Screen.HOME;
        ScrollView scroll=new ScrollView(this); LinearLayout root=vertical(); scroll.addView(root);
        TextView title=text("文件裁切器",28,true);title.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(title);
        TextView sub=text("採購單・A4文件・名片・收據",17,false);sub.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(sub);
        gap(root,20);
        TextView steps=text("1  從相簿選照片\n2  自動抓取文件四角，可手動微調\n3  拉正裁切，可增加亮度與清晰度\n4  分享到 LINE 或儲存到手機相簿",18,false);steps.setLineSpacing(dp(5),1f);root.addView(steps);
        gap(root,22);
        Button pick=button("從相簿選取照片");pick.setOnClickListener(v->pickImage());root.addView(pick,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        gap(root,20);
        TextView privacy=text("隱私：圖片只在這支手機內處理，不會上傳到伺服器。",15,true);privacy.setTextColor(Color.rgb(50,110,75));root.addView(privacy);
        TextView note=text("建議：拍攝文件時讓整張紙都出現在照片內，背景與紙張顏色差異越明顯，自動抓角越準。",14,false);root.addView(note);
        setContentView(scroll);
    }

    private void pickImage(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");startActivityForResult(i,REQ_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_IMAGE&&resultCode==RESULT_OK&&data!=null&&data.getData()!=null){
            Uri uri=data.getData();
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            ProgressDialog d=ProgressDialog.show(this,"讀取文件","正在分析文件四角…",true,false);
            new Thread(()->{
                try{
                    Bitmap b=ImageUtils.loadBitmap(getContentResolver(),uri,4096);
                    PointF[] corners=ImageUtils.detectDocumentCorners(b);
                    runOnUiThread(()->{d.dismiss();if(sourceBitmap!=null&&!sourceBitmap.isRecycled())sourceBitmap.recycle();sourceBitmap=b;showCrop(corners);});
                }catch(Exception e){runOnUiThread(()->{d.dismiss();Toast.makeText(this,"無法開啟這張照片，請換一張再試。",Toast.LENGTH_LONG).show();});}
            }).start();
        }
    }

    private void showCrop(PointF[] corners){
        screen=Screen.CROP;
        LinearLayout root=vertical();root.setPadding(dp(12),dp(10),dp(12),dp(12));
        TextView title=text("調整文件四個角",22,true);title.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(title);
        TextView hint=text("拖曳白色圓點，讓綠線貼齊文件邊緣。",15,false);hint.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(hint);
        cropView=new DocumentCropView(this);cropView.setDocument(sourceBitmap,corners);root.addView(cropView,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setPadding(0,dp(8),0,0);
        Button auto=button("重新自動抓角");Button crop=button("確認裁切");
        row.addView(auto,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));row.addView(crop,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));root.addView(row);
        auto.setOnClickListener(v->{ProgressDialog d=ProgressDialog.show(this,"分析中","重新尋找文件邊界…",true,false);new Thread(()->{PointF[] p=ImageUtils.detectDocumentCorners(sourceBitmap);runOnUiThread(()->{d.dismiss();cropView.setCorners(p);});}).start();});
        crop.setOnClickListener(v->doCrop());
        setContentView(root);
    }

    private void doCrop(){
        if(cropView==null)return; PointF[] p=cropView.getCorners(); ProgressDialog d=ProgressDialog.show(this,"裁切中","正在拉正文件…",true,false);
        new Thread(()->{try{Bitmap out=ImageUtils.perspectiveCrop(sourceBitmap,p);runOnUiThread(()->{d.dismiss();if(croppedBitmap!=null&&!croppedBitmap.isRecycled())croppedBitmap.recycle();croppedBitmap=out;showResult();});}catch(Exception e){runOnUiThread(()->{d.dismiss();Toast.makeText(this,"裁切失敗，請重新調整四個角。",Toast.LENGTH_LONG).show();});}}).start();
    }

    private void showResult(){
        screen=Screen.RESULT;
        LinearLayout root=vertical();root.setPadding(dp(12),dp(10),dp(12),dp(12));
        TextView title=text("完成",24,true);title.setGravity(Gravity.CENTER_HORIZONTAL);root.addView(title);
        resultImage=new ImageView(this);resultImage.setAdjustViewBounds(true);resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(resultImage,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1f));
        brightSwitch=new Switch(this);brightSwitch.setText("增加亮度");brightSwitch.setTextSize(17);brightSwitch.setPadding(dp(8),dp(6),dp(8),dp(6));
        sharpSwitch=new Switch(this);sharpSwitch.setText("增加清晰度");sharpSwitch.setTextSize(17);sharpSwitch.setPadding(dp(8),dp(6),dp(8),dp(6));sharpSwitch.setChecked(true);
        LinearLayout switches=new LinearLayout(this);switches.setOrientation(LinearLayout.HORIZONTAL);switches.addView(brightSwitch,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));switches.addView(sharpSwitch,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));root.addView(switches);
        brightSwitch.setOnCheckedChangeListener((b,c)->refreshEnhanced());sharpSwitch.setOnCheckedChangeListener((b,c)->refreshEnhanced());
        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button line=button("分享到 LINE");Button save=button("儲存到相簿");actions.addView(line,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));actions.addView(save,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));root.addView(actions);
        Button again=button("重新選照片");root.addView(again,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        line.setOnClickListener(v->shareLine());save.setOnClickListener(v->saveCurrent());again.setOnClickListener(v->pickImage());
        setContentView(root);refreshEnhanced();
    }

    private void refreshEnhanced(){
        if(croppedBitmap==null||resultImage==null)return;
        boolean bright=brightSwitch!=null&&brightSwitch.isChecked(), sharp=sharpSwitch!=null&&sharpSwitch.isChecked();
        ProgressDialog d=ProgressDialog.show(this,"處理中","正在套用文件增強…",true,false);
        new Thread(()->{Bitmap b=ImageUtils.enhance(croppedBitmap,bright,sharp);runOnUiThread(()->{d.dismiss();if(displayBitmap!=null&&displayBitmap!=croppedBitmap&&!displayBitmap.isRecycled())displayBitmap.recycle();displayBitmap=b;resultImage.setImageBitmap(displayBitmap);});}).start();
    }

    private Bitmap currentBitmap(){return displayBitmap!=null?displayBitmap:croppedBitmap;}

    private void shareLine(){
        Bitmap b=currentBitmap();if(b==null)return;
        try{
            File dir=new File(getCacheDir(),"shared");if(!dir.exists())dir.mkdirs();
            File f=new File(dir,"document_"+System.currentTimeMillis()+".jpg");try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}
            Uri u=new Uri.Builder().scheme("content").authority(getPackageName()+".share").appendPath("images").appendPath(f.getName()).build();
            Intent share=new Intent(Intent.ACTION_SEND);share.setType("image/jpeg");share.putExtra(Intent.EXTRA_STREAM,u);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newUri(getContentResolver(),"document",u));share.setPackage("jp.naver.line.android");
            try{startActivity(share);}catch(Exception e){share.setPackage(null);startActivity(Intent.createChooser(share,"分享文件"));}
        }catch(Exception e){Toast.makeText(this,"分享失敗，請先嘗試儲存到相簿。",Toast.LENGTH_LONG).show();}
    }

    private void saveCurrent(){
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQ_WRITE);return;}
        saveToGallery();
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==REQ_WRITE&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)saveToGallery();}

    private void saveToGallery(){
        Bitmap b=currentBitmap();if(b==null)return;ProgressDialog d=ProgressDialog.show(this,"儲存中","正在儲存到手機相簿…",true,false);
        new Thread(()->{
            try{
                String name="文件_"+new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.TAIWAN).format(new Date())+".jpg";
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues cv=new ContentValues();cv.put(MediaStore.Images.Media.DISPLAY_NAME,name);cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");cv.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/文件裁切器");cv.put(MediaStore.Images.Media.IS_PENDING,1);
                    Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);if(uri==null)throw new Exception();try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null||!b.compress(Bitmap.CompressFormat.JPEG,95,out))throw new Exception();}cv.clear();cv.put(MediaStore.Images.Media.IS_PENDING,0);getContentResolver().update(uri,cv,null,null);
                }else{
                    File base=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);File dir=new File(base,"文件裁切器");if(!dir.exists())dir.mkdirs();File f=new File(dir,name);try(FileOutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,95,out);}android.media.MediaScannerConnection.scanFile(this,new String[]{f.getAbsolutePath()},new String[]{"image/jpeg"},null);
                }
                runOnUiThread(()->{d.dismiss();Toast.makeText(this,"已儲存到相簿「文件裁切器」",Toast.LENGTH_LONG).show();});
            }catch(Exception e){runOnUiThread(()->{d.dismiss();Toast.makeText(this,"儲存失敗，請確認手機儲存空間。",Toast.LENGTH_LONG).show();});}
        }).start();
    }

    @Override public void onBackPressed(){
        if(screen==Screen.RESULT&&sourceBitmap!=null){showCrop(ImageUtils.defaultCorners(sourceBitmap));return;}
        if(screen==Screen.CROP){showHome();return;}
        super.onBackPressed();
    }

    @Override protected void onDestroy(){super.onDestroy();if(sourceBitmap!=null&&!sourceBitmap.isRecycled())sourceBitmap.recycle();if(croppedBitmap!=null&&croppedBitmap!=sourceBitmap&&!croppedBitmap.isRecycled())croppedBitmap.recycle();if(displayBitmap!=null&&displayBitmap!=croppedBitmap&&displayBitmap!=sourceBitmap&&!displayBitmap.isRecycled())displayBitmap.recycle();}
}
