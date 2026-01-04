package com.threadaffinity.manager.model;

import android.graphics.drawable.Drawable;

/**
 * 应用信息模型
 */
public class AppInfo {
    private String appName;
    private String packageName;
    private Drawable icon;
    private int pid;

    public AppInfo(String appName, String packageName, Drawable icon) {
        this.appName = appName;
        this.packageName = packageName;
        this.icon = icon;
        this.pid = -1;
    }

    public String getAppName() { return appName; }
    public String getPackageName() { return packageName; }
    public Drawable getIcon() { return icon; }
    public int getPid() { return pid; }
    public void setPid(int pid) { this.pid = pid; }

    @Override
    public String toString() {
        return appName + " (" + packageName + ")";
    }
}
