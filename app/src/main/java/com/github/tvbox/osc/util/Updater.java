package com.github.tvbox.osc.util;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.github.tvbox.osc.BuildConfig;
import com.github.tvbox.osc.R;
import com.lzy.okgo.OkGo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * TVBox 应用更新管理器 (minSdk 21+)
 * 支持多代理切换、统一使用 FileProvider 安装
 */
public class Updater implements Download.Callback {
    private static final String TAG = "Updater";
    private static final int MAX_RETRY_COUNT = 4;

    private Activity activity;
    private Handler mainHandler;
    private AlertDialog dialog;
    private ProgressDialog progressDialog;
    private int retryCount = 0;
    private boolean forceCheck = false;
    private boolean silentMode = false;
    private boolean isInstallTriggered = false;

    public static Updater create() {
        return new Updater();
    }

    private Updater() {
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public Updater force() {
        this.forceCheck = true;
        return this;
    }

    public Updater silent() {
        this.silentMode = true;
        return this;
    }

    public void start(Activity activity) {
        this.activity = activity;
        if (forceCheck && !silentMode) {
            showToast("正在检查更新...");
        }
        new Thread(this::checkUpdate).start();
    }

    private String getJsonUrl() {
        return Github.getJson("XHYS");
    }

    private String getApkUrl() {
        return Github.getApk(BuildConfig.APK_NAME);
    }

    private void checkUpdate() {
        try {
            Log.d(TAG, "检查更新: " + getJsonUrl());

            // 移除 setOkHttpClient，使用 OkGo 默认配置
            String response = OkGo.<String>get(getJsonUrl())
                    .execute()
                    .body()
                    .string();

            Log.d(TAG, "返回: " + response);

            JSONObject json = new JSONObject(response);
            int remoteCode = json.optInt("code", 0);
            int localCode = BuildConfig.VERSION_CODE;

            Log.d(TAG, "本地版本: " + localCode + ", 远程版本: " + remoteCode);

            if (remoteCode > localCode) {
                String name = json.optString("name", "未知版本");
                String desc = json.optString("desc", "暂无更新说明");
                mainHandler.post(() -> showUpdateDialog(name, desc));
            } else if (forceCheck && !silentMode) {
                mainHandler.post(() -> showToast("当前已是最新版本"));
            }
        } catch (Exception e) {
            Log.e(TAG, "检查失败: " + e.getMessage());
            if (forceCheck && !silentMode) {
                mainHandler.post(() -> showToast("检查更新失败: " + e.getMessage()));
            }
        }
    }

    private void showUpdateDialog(String version, String desc) {
        if (activity == null || activity.isFinishing()) return;

        View view = LayoutInflater.from(activity).inflate(R.layout.dialog_update, null);

        TextView tvVersion = view.findViewById(R.id.version);
        TextView tvDesc = view.findViewById(R.id.desc);
        TextView btnConfirm = view.findViewById(R.id.confirm);
        TextView btnCancel = view.findViewById(R.id.cancel);

        tvVersion.setText(activity.getString(R.string.update_version, version));
        tvDesc.setText(desc);

        btnConfirm.setFocusable(true);
        btnCancel.setFocusable(true);

        dialog = new AlertDialog.Builder(activity)
                .setView(view)
                .setCancelable(false)
                .create();

        dialog.show();

        btnConfirm.setOnClickListener(v -> {
            btnConfirm.setEnabled(false);
            btnConfirm.setText("准备下载...");
            startDownload();
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.requestFocus();
    }

    /**
     * 获取缓存目录（外部优先，自动降级内部）
     */
    private File getAvailableCacheDir() {
        File externalCache = activity.getExternalCacheDir();
        if (externalCache != null) {
            if (!externalCache.exists()) {
                externalCache.mkdirs();
            }
            if (externalCache.canWrite()) {
                Log.d(TAG, "使用外部缓存: " + externalCache.getPath());
                return externalCache;
            }
        }
        Log.d(TAG, "使用内部缓存: " + activity.getCacheDir().getPath());
        return activity.getCacheDir();
    }

    private void startDownload() {
        String url = getApkUrl();
        Log.i(TAG, "下载: " + url);

        mainHandler.post(() -> {
            if (dialog != null) dialog.dismiss();
            if (progressDialog != null && progressDialog.isShowing()) {
                progressDialog.dismiss();
            }
            progressDialog = new ProgressDialog(activity);
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progressDialog.setTitle("正在下载");
            progressDialog.setMax(100);
            progressDialog.setCancelable(false);
            progressDialog.show();
        });

        File cacheDir = getAvailableCacheDir();
        File file = new File(cacheDir, "update.apk");

        if (file.exists() && !file.delete()) {
            Log.w(TAG, "无法删除旧文件，使用临时文件名");
            file = new File(cacheDir, "update_" + System.currentTimeMillis() + ".apk");
        }

        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        Download.create(url, file).start(this);
    }

    @Override
    public void progress(int progress) {
        mainHandler.post(() -> {
            if (progressDialog != null) {
                progressDialog.setProgress(progress);
            }
        });
    }

    @Override
    public void error(String msg) {
        Log.e(TAG, "下载错误: " + msg + ", 重试: " + retryCount);

        retryCount++;
        if (retryCount < MAX_RETRY_COUNT) {
            Github.switchToNextProxy();
            mainHandler.post(() -> {
                if (progressDialog != null) {
                    progressDialog.setMessage("切换代理重试 " + retryCount + "/" + MAX_RETRY_COUNT);
                }
                mainHandler.postDelayed(this::startDownload, 1500);
            });
        } else {
            Log.e(TAG, "所有代理失败");
            mainHandler.post(() -> {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                showToast("下载失败，所有代理均不可用");
                retryCount = 0;
            });
        }
    }

    @Override
    public void success(File file) {
        if (isInstallTriggered) return;
        isInstallTriggered = true;

        Log.i(TAG, "下载成功: " + file.getAbsolutePath());
        mainHandler.post(() -> {
            if (progressDialog != null) progressDialog.dismiss();
            installApk(file);
        });
    }

    /**
     * 安装 APK（minSdk 21+ 统一使用 FileProvider）
     */
    private void installApk(File file) {
        try {
            file.setReadable(true, false);

            Uri uri = FileProvider.getUriForFile(activity,
                    BuildConfig.APPLICATION_ID + ".fileprovider", file);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            if (activity.getPackageManager().queryIntentActivities(intent, 0).isEmpty()) {
                Log.e(TAG, "无应用处理安装，尝试备用方案");
                fallbackInstall(file);
                return;
            }

            activity.startActivity(intent);

        } catch (Exception e) {
            Log.e(TAG, "安装失败: " + e.getMessage(), e);
            fallbackInstall(file);
        }
    }

    /**
     * 备用安装方案
     */
    private void fallbackInstall(File file) {
        try {
            file.setReadable(true, false);

            File publicFile = copyToDownloads(file);
            if (publicFile != null) {
                file = publicFile;
            }

            if (publicFile != null) {
                Uri uri = FileProvider.getUriForFile(activity,
                        BuildConfig.APPLICATION_ID + ".fileprovider", file);
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(uri, "application/vnd.android.package-archive");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(intent);
                return;
            }

            showToast("自动安装失败，请手动安装: " + file.getAbsolutePath());
            isInstallTriggered = false;

        } catch (Exception e) {
            Log.e(TAG, "备用安装失败: " + e.getMessage());
            showToast("安装失败，请手动安装");
            isInstallTriggered = false;
        }
    }

    /**
     * 复制到 Downloads 目录
     */
    private File copyToDownloads(File sourceFile) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return null;
        }

        try {
            File downloadDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS);
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
            File targetFile = new File(downloadDir, "XHYS_update.apk");

            try (FileInputStream inStream = new FileInputStream(sourceFile);
                 FileOutputStream outStream = new FileOutputStream(targetFile);
                 FileChannel inChannel = inStream.getChannel();
                 FileChannel outChannel = outStream.getChannel()) {
                inChannel.transferTo(0, inChannel.size(), outChannel);
            }

            targetFile.setReadable(true, false);
            Log.i(TAG, "已复制到: " + targetFile.getAbsolutePath());
            return targetFile;

        } catch (IOException e) {
            Log.e(TAG, "复制失败: " + e.getMessage());
            return null;
        }
    }

    private void showToast(String msg) {
        if (activity != null && !activity.isFinishing()) {
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show();
        }
    }
}