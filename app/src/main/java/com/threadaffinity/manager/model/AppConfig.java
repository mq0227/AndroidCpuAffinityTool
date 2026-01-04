package com.threadaffinity.manager.model;

import java.util.Map;
import java.util.TreeMap;

/**
 * 应用配置模型，用于保存和加载亲和性配置
 * 掩码以十六进制字符串格式保存（如 "0x80"）
 */
public class AppConfig {
    private String packageName;
    private String appName;
    private long timestamp;
    // 线程名 -> 亲和性掩码（十六进制字符串，如 "0x80"）
    private Map<String, String> threadAffinities;

    public AppConfig() {
        threadAffinities = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public AppConfig(String packageName, String appName) {
        this.packageName = packageName;
        this.appName = appName;
        this.timestamp = System.currentTimeMillis();
        this.threadAffinities = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    }

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }

    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public Map<String, String> getThreadAffinities() { return threadAffinities; }
    public void setThreadAffinities(Map<String, String> threadAffinities) { 
        this.threadAffinities = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (threadAffinities != null) {
            this.threadAffinities.putAll(threadAffinities);
        }
    }

    /**
     * 添加线程亲和性配置（自动转换为十六进制字符串）
     */
    public void addThreadAffinity(String threadName, long mask) {
        threadAffinities.put(threadName, "0x" + Long.toHexString(mask).toUpperCase());
    }

    /**
     * 获取线程亲和性掩码（从十六进制字符串解析）
     * @return 掩码值，未配置返回 null
     */
    public Long getThreadAffinity(String threadName) {
        String hex = threadAffinities.get(threadName);
        if (hex == null) return null;
        return parseHexMask(hex);
    }
    
    /**
     * 获取原始十六进制字符串
     */
    public String getThreadAffinityHex(String threadName) {
        return threadAffinities.get(threadName);
    }
    
    /**
     * 解析十六进制掩码字符串
     */
    public static long parseHexMask(String hex) {
        if (hex == null || hex.isEmpty()) return 0xFFL;
        hex = hex.trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        try {
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0xFFL;
        }
    }
}
