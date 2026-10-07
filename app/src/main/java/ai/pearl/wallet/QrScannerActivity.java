package ai.pearl.wallet;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.io.InputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** QR decoding is entirely local. Returns untrusted text; the wallet validates it before prefilling. */
@SuppressWarnings("deprecation")
public final class QrScannerActivity extends Activity implements SurfaceHolder.Callback {
    static final int REQUEST=4210;
    static final String PAYLOAD="qr_payload";
    private static final int CAMERA_PERMISSION=42, IMAGE=43;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private SurfaceView preview;
    private TextView status;
    private Camera camera;
    private boolean resumed, surfaceReady, finished;
    private volatile boolean decoding;
    private int generation;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(24,80,24,60);
        status=new TextView(this);status.setText("扫描 Pearl 收款二维码\n识别在手机完成，仅填入地址和金额。下一步请核对完整地址。");status.setTextSize(16);body.addView(status);
        preview=new SurfaceView(this);preview.getHolder().addCallback(this);body.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        Button image=new Button(this);image.setText("从图片识别二维码");image.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),IMAGE));body.addView(image);
        Button cancel=new Button(this);cancel.setText("取消扫码");cancel.setOnClickListener(v->finish());body.addView(cancel);setContentView(body);
        if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);
    }
    @Override public void surfaceCreated(SurfaceHolder holder){surfaceReady=true;openCamera();}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){/* Actual preview size is selected from camera-supported sizes. */}
    @Override public void surfaceDestroyed(SurfaceHolder holder){surfaceReady=false;closeCamera();}
    @Override public void onResume(){super.onResume();resumed=true;openCamera();}
    @Override public void onPause(){resumed=false;closeCamera();super.onPause();}
    private void openCamera(){
        if(!resumed||!surfaceReady||camera!=null||finished||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        try{
            int id=0;Camera.CameraInfo info=new Camera.CameraInfo();
            for(int i=0;i<Camera.getNumberOfCameras();i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){id=i;break;}}
            Camera.CameraInfo selected=new Camera.CameraInfo();Camera.getCameraInfo(id,selected);camera=Camera.open(id);
            Camera.Parameters p=camera.getParameters();Camera.Size size=p.getPreviewSize();
            for(Camera.Size s:p.getSupportedPreviewSizes())if(s.width<=1280&&s.height<=960&&(size.width>1280||s.width>size.width))size=s;
            p.setPreviewSize(size.width,size.height);p.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if(p.getSupportedFocusModes()!=null&&p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(p);int rotation=getWindowManager().getDefaultDisplay().getRotation();int degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            int orientation=selected.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-(selected.orientation+degrees)%360)%360:(selected.orientation-degrees+360)%360;
            camera.setDisplayOrientation(orientation);camera.setPreviewDisplay(preview.getHolder());camera.startPreview();requestFrame();
        }catch(Exception e){closeCamera();status.setText("相机暂不可用，可选择本机二维码图片。");}
    }
    private void closeCamera(){
        generation++;Camera current=camera;camera=null;
        if(current!=null){try{current.setPreviewCallback(null);current.stopPreview();}catch(RuntimeException ignored){/* Release even when a camera stopped unexpectedly. */}finally{current.release();}}
    }
    private void requestFrame(){
        if(camera==null||!resumed||finished)return;
        if(decoding){preview.postDelayed(this::requestFrame,200);return;}
        final int token=generation;
        camera.setOneShotPreviewCallback((bytes,c)->{
            if(token!=generation||camera!=c||decoding)return;
            Camera.Size s=c.getParameters().getPreviewSize();decoding=true;
            worker.execute(()->{String result=null;try{result=decode(new BinaryBitmap(new HybridBinarizer(new PlanarYUVLuminanceSource(bytes,s.width,s.height,0,0,s.width,s.height,false))));}catch(Exception ignored){/* Keep scanning frames. */}
                String payload=result;runOnUiThread(()->{decoding=false;if(token!=generation||!resumed||finished)return;if(payload!=null)complete(payload);else preview.postDelayed(this::requestFrame,200);});});
        });
    }
    private static String decode(BinaryBitmap bitmap)throws Exception{return new MultiFormatReader().decode(bitmap,Collections.singletonMap(DecodeHintType.POSSIBLE_FORMATS,Collections.singletonList(BarcodeFormat.QR_CODE))).getText();}
    private void complete(String value){
        if(finished||isDestroyed())return;
        try{PaymentRequest.parse(value);}catch(Exception e){status.setText(getString(R.string.invalid_payment_qr,e.getMessage()));preview.postDelayed(this::requestFrame,1000);return;}
        finished=true;setResult(RESULT_OK,new Intent().putExtra(PAYLOAD,value));finish();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==CAMERA_PERMISSION){if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED)openCamera();else status.setText("未启用相机权限，可选择本机二维码图片。");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=IMAGE||result!=RESULT_OK||data==null||data.getData()==null)return;
        android.net.Uri uri=data.getData();status.setText("正在本机识别图片…");
        worker.execute(()->{Bitmap bitmap=null;String payload=null;String error=null;
            try{
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                try(InputStream input=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(input,null,options);}
                if(options.outWidth<=0||options.outHeight<=0||options.outWidth>30000||options.outHeight>30000)throw new IllegalArgumentException("图片尺寸无效");
                options.inJustDecodeBounds=false;options.inSampleSize=1;while(options.outWidth/options.inSampleSize>1600||options.outHeight/options.inSampleSize>1600)options.inSampleSize*=2;
                try(InputStream input=getContentResolver().openInputStream(uri)){bitmap=BitmapFactory.decodeStream(input,null,options);}
                if(bitmap==null)throw new IllegalArgumentException("图片无法读取");int width=bitmap.getWidth(),height=bitmap.getHeight();int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
                payload=decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width,height,pixels))));
            }catch(Exception e){error="未识别到 Pearl 收款二维码，请换一张清晰图片。";}finally{if(bitmap!=null)bitmap.recycle();}
            String value=payload,message=error;runOnUiThread(()->{if(isFinishing()||isDestroyed())return;if(value!=null)complete(value);else status.setText(message);});
        });
    }
    @Override public void onDestroy(){closeCamera();worker.shutdownNow();super.onDestroy();}
}
