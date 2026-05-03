package com.huiyu.receipt;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.print.PrintHelper;

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
        // 动态创建 WebView，无需布局文件
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

        // 注入 Android 接口
        webView.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        // 加载本地 HTML
        webView.loadUrl("file:///android_asset/index.html");
    }

    // JavaScript 接口类
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
        public void requestStoragePermission() {
            // 用于页面加载时主动请求存储权限（Android 6.0+）
            if (ContextCompat.checkSelfPermission(mActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(mActivity,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
            }
        }

        @JavascriptInterface
        public void onPageLoaded() {
            // 页面加载完成回调（可忽略）
        }
    }

    // 保存文件到系统下载目录
    private void saveFileInternal(String base64Data, String fileName, String mimeType) {
        try {
            // 去除 base64 头部 (data:image/png;base64,)
            String pureBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
            byte[] decoded = Base64.decode(pureBase64, Base64.DEFAULT);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(decoded);
                    os.close();
                    showToast("文件已保存到下载目录");
                } else {
                    showToast("保存失败：无法创建文件");
                }
            } else {
                // Android 9 及以下使用传统文件存储
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    showToast("请授予存储权限后再试");
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_REQUEST_CODE);
                    return;
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(decoded);
                fos.close();
                // 通知图库刷新
                Intent scanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                scanIntent.setData(Uri.fromFile(file));
                sendBroadcast(scanIntent);
                showToast("文件已保存到下载目录");
            }
        } catch (Exception e) {
            showToast("保存失败：" + e.getMessage());
        }
    }

    // 调用系统打印服务（支持 Android 4.4+）
    private void printImage(String base64Data) {
        try {
            String pureBase64 = base64Data.contains(",") ? base64Data.split(",")[1] : base64Data;
            byte[] decoded = Base64.decode(pureBase64, Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeStream(new ByteArrayInputStream(decoded));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                PrintHelper photoPrinter = new PrintHelper(this);
                photoPrinter.setScaleMode(PrintHelper.SCALE_MODE_FILL);
                photoPrinter.printBitmap("慧语电子票据 - 销售单", bitmap);
            } else {
                showToast("打印功能需要 Android 4.4 或更高版本");
            }
        } catch (Exception e) {
            showToast("打印失败：" + e.getMessage());
        }
    }

    // 权限回调
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showToast("权限已授予，可重新保存/打印");
            } else {
                showToast("存储权限被拒绝，无法保存文件");
            }
        }
    }

    // 辅助 Toast 方法
    private void showToast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
