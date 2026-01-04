package com.threadaffinity.manager;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import com.threadaffinity.manager.service.FloatingWindowService;
import com.threadaffinity.manager.util.ProcessHelper;

/**
 * 快速启动Activity - 无UI，直接启动悬浮窗监控
 * 
 * 使用方法:
 * am start -n com.threadaffinity.manager/.QuickStartActivity -e package com.tencent.tmgp.sgame
 * 
 * 或者简写:
 * am start -a com.threadaffinity.QUICK_START -e package com.tencent.tmgp.sgame
 */
public class QuickStartActivity extends Activity {
    private static final String TAG = "QuickStartActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        String packageName = null;
        Intent intent = getIntent();
        
        if (intent != null) {
            packageName = intent.getStringExtra("package");
            // 也支持 pkg 简写
            if (packageName == null) {
                packageName = intent.getStringExtra("pkg");
            }
        }
        
        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "No package name provided");
            Toast.makeText(this, "请提供包名: -e package <包名>", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        Log.i(TAG, "Quick start for: " + packageName);
        
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission, requesting...");
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            Intent permIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(permIntent);
            finish();
            return;
        }
        
        // 获取目标进程PID
        int pid = ProcessHelper.getPidByPackage(packageName);
        if (pid <= 0) {
            Log.w(TAG, "Process not running: " + packageName);
            Toast.makeText(this, "应用未运行: " + packageName, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        // 启动悬浮窗服务
        Intent serviceIntent = new Intent(this, FloatingWindowService.class);
        serviceIntent.putExtra("pid", pid);
        serviceIntent.putExtra("package", packageName);
        serviceIntent.putExtra("minimized", true);  // 快速启动默认最小化
        startForegroundService(serviceIntent);
        
        Log.i(TAG, "Started monitoring: " + packageName + " (pid=" + pid + ")");
        
        // 立即结束，不显示任何UI
        finish();
    }
}
