package com.example.salesinvoice;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private static final int PERMISSION_REQUEST_CODE = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // 会自动生成，但我们用代码创建，下面有替代方案

        // 如果没有布局文件，动态创建 WebView
        webView = new WebView(this);
        setContentView(webView);

        setupWebView();
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        // 注入 Android 接口，供 HTML 调用
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        // 加载 assets 中的 index.html
        webView.loadUrl("file:///android_asset/index.html");
    }

    // 提供给 HTML 的接口类
    public class WebAppInterface {
        private MainActivity mActivity;

        WebAppInterface(MainActivity activity) {
            mActivity = activity;
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(mActivity, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void saveFile(String base64Data, String fileName, String mimeType) {
            runOnUiThread(() -> mActivity.saveFileInternal(base64Data, fileName, mimeType));
        }

        @JavascriptInterface
        public void printBase64(String base64Data) {
            runOnUiThread(() -> mActivity.printImage(base64Data));
        }

        @JavascriptInterface
        public void onPageLoaded() {
            // HTML 加载完成回调，可忽略
        }
    }

    private void saveFileInternal(String base64Data, String fileName, String mimeType) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            return;
        }
        try {
            // 去除 base64 头部 (data:image/png;base64,)
            String pureBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
            byte[] decoded = Base64.decode(pureBase64, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(decoded);
                    os.close();
                }
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(decoded);
                fos.close();
                // 通知图库刷新
                Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaScanIntent.setData(Uri.fromFile(file));
                sendBroadcast(mediaScanIntent);
            }
            Toast.makeText(this, "文件已保存到下载目录", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void printImage(String base64Data) {
        try {
            String pureBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
            byte[] decoded = Base64.decode(pureBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(decoded));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                File cacheDir = getExternalCacheDir();
                File printFile = new File(cacheDir, "sales_invoice.png");
                FileOutputStream fos = new FileOutputStream(printFile);
                fos.write(decoded);
                fos.close();

                // 使用系统打印服务
                Intent printIntent = new Intent(this, PrintServiceActivity.class);
                printIntent.setDataAndType(Uri.fromFile(printFile), "image/png");
                printIntent.putExtra("title", "销售单打印");
                startActivity(printIntent);
            } else {
                Toast.makeText(this, "打印功能需要 Android 4.4 以上", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "打印失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "权限已授予，请再次点击保存按钮", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "存储权限被拒绝，无法保存文件", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
