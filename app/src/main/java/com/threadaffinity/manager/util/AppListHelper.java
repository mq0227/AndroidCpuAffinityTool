package com.threadaffinity.manager.util;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import com.threadaffinity.manager.model.AppInfo;
import java.util.ArrayList;
import java.util.List;

/**
 * 应用列表获取辅助类
 */
public class AppListHelper {
    private static final String TAG = "AppListHelper";

    /**
     * 获取所有已安装的用户应用
     */
    public static List<AppInfo> getInstalledApps(Context context, boolean includeSystem) {
        List<AppInfo> apps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        
        try {
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            
            for (ApplicationInfo appInfo : packages) {
                // 过滤系统应用
                if (!includeSystem && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
                
                String appName = pm.getApplicationLabel(appInfo).toString();
                AppInfo app = new AppInfo(
                    appName,
                    appInfo.packageName,
                    pm.getApplicationIcon(appInfo)
                );
                apps.add(app);
            }
            
            // 按名称排序
            apps.sort((a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
            
            Log.i(TAG, "Found " + apps.size() + " apps");
        } catch (Exception e) {
            Log.e(TAG, "Failed to get app list: " + e.getMessage());
        }
        
        return apps;
    }

    /**
     * 搜索应用（按名称或包名）
     */
    public static List<AppInfo> searchApps(List<AppInfo> allApps, String query) {
        if (query == null || query.trim().isEmpty()) {
            return allApps;
        }
        
        String lowerQuery = query.toLowerCase().trim();
        List<AppInfo> results = new ArrayList<>();
        
        for (AppInfo app : allApps) {
            if (app.getAppName().toLowerCase().contains(lowerQuery) ||
                app.getPackageName().toLowerCase().contains(lowerQuery)) {
                results.add(app);
            }
        }
        
        return results;
    }

    /**
     * 获取正在运行的应用
     */
    public static List<AppInfo> getRunningApps(Context context) {
        List<AppInfo> runningApps = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        
        try {
            // 通过 root 获取运行中的进程
            String result = RootHelper.executeRootCommand("ps -A -o PID,NAME | grep -v '\\['");
            if (result == null || result.isEmpty()) {
                return runningApps;
            }
            
            String[] lines = result.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String processName = parts[parts.length - 1];
                    
                    // 检查是否是应用包名
                    if (processName.contains(".")) {
                        try {
                            ApplicationInfo appInfo = pm.getApplicationInfo(processName, 0);
                            String appName = pm.getApplicationLabel(appInfo).toString();
                            AppInfo app = new AppInfo(
                                appName,
                                processName,
                                pm.getApplicationIcon(appInfo)
                            );
                            
                            // 设置 PID
                            try {
                                app.setPid(Integer.parseInt(parts[0]));
                            } catch (NumberFormatException e) {
                                // 忽略
                            }
                            
                            // 避免重复
                            boolean exists = false;
                            for (AppInfo existing : runningApps) {
                                if (existing.getPackageName().equals(processName)) {
                                    exists = true;
                                    break;
                                }
                            }
                            if (!exists) {
                                runningApps.add(app);
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            // 不是应用，跳过
                        }
                    }
                }
            }
            
            // 按名称排序
            runningApps.sort((a, b) -> a.getAppName().compareToIgnoreCase(b.getAppName()));
            
            Log.i(TAG, "Found " + runningApps.size() + " running apps");
        } catch (Exception e) {
            Log.e(TAG, "Failed to get running apps: " + e.getMessage());
        }
        
        return runningApps;
    }
}
