package com.threadaffinity.manager.util;

import android.content.Context;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.threadaffinity.manager.model.AppConfig;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 配置文件管理器
 * 亲和性掩码以十六进制字符串格式保存（如 "0x80"）
 */
public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static final String CONFIG_DIR = "configs";
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private final Context context;

    public ConfigManager(Context context) {
        this.context = context;
        ensureConfigDir();
    }

    private void ensureConfigDir() {
        File dir = new File(context.getFilesDir(), CONFIG_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
            Log.i(TAG, "Created config directory: " + dir.getAbsolutePath());
        }
    }

    /**
     * 保存配置（掩码以十六进制字符串格式保存）
     */
    public boolean saveConfig(AppConfig config) {
        String filename = config.getPackageName().replace(".", "_") + ".json";
        File file = new File(context.getFilesDir(), CONFIG_DIR + "/" + filename);
        
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(config, writer);
            Log.i(TAG, "Config saved: " + file.getAbsolutePath());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Failed to save config: " + e.getMessage());
            return false;
        }
    }

    /**
     * 加载配置（自动兼容旧格式的十进制数字）
     */
    public AppConfig loadConfig(String packageName) {
        String filename = packageName.replace(".", "_") + ".json";
        File file = new File(context.getFilesDir(), CONFIG_DIR + "/" + filename);
        
        if (!file.exists()) {
            Log.w(TAG, "Config file not found: " + filename);
            return null;
        }
        
        try (FileReader reader = new FileReader(file)) {
            AppConfig config = gson.fromJson(reader, AppConfig.class);
            
            // 兼容旧格式：将十进制数字转换为十六进制字符串
            if (config != null && config.getThreadAffinities() != null) {
                Map<String, String> fixed = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                for (Map.Entry<String, ?> entry : ((Map<String, ?>) config.getThreadAffinities()).entrySet()) {
                    Object value = entry.getValue();
                    String hexStr;
                    if (value instanceof String) {
                        // 新格式：已经是十六进制字符串
                        hexStr = (String) value;
                        if (!hexStr.startsWith("0x") && !hexStr.startsWith("0X")) {
                            // 可能是纯数字字符串，尝试解析
                            try {
                                long mask = Long.parseLong(hexStr);
                                hexStr = "0x" + Long.toHexString(mask).toUpperCase();
                            } catch (NumberFormatException e) {
                                hexStr = "0xFF";
                            }
                        }
                    } else if (value instanceof Double) {
                        // 旧格式：Gson 将数字解析为 Double
                        long mask = ((Double) value).longValue();
                        hexStr = "0x" + Long.toHexString(mask).toUpperCase();
                    } else if (value instanceof Number) {
                        long mask = ((Number) value).longValue();
                        hexStr = "0x" + Long.toHexString(mask).toUpperCase();
                    } else {
                        hexStr = "0xFF";
                    }
                    fixed.put(entry.getKey(), hexStr);
                }
                config.setThreadAffinities(fixed);
            }
            
            Log.i(TAG, "Config loaded: " + filename + ", affinities=" + 
                (config != null && config.getThreadAffinities() != null ? config.getThreadAffinities().size() : 0));
            return config;
        } catch (IOException e) {
            Log.e(TAG, "Failed to load config: " + e.getMessage());
            return null;
        } catch (Exception e) {
            // JSON 解析错误，删除损坏的配置文件
            Log.e(TAG, "Corrupted config file, deleting: " + filename + " - " + e.getMessage());
            file.delete();
            return null;
        }
    }

    /**
     * 获取所有已保存的配置
     */
    public List<AppConfig> getAllConfigs() {
        List<AppConfig> configs = new ArrayList<>();
        File dir = new File(context.getFilesDir(), CONFIG_DIR);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        
        if (files != null) {
            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    AppConfig config = gson.fromJson(reader, AppConfig.class);
                    if (config != null) {
                        configs.add(config);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to read config: " + file.getName() + " - " + e.getMessage());
                } catch (Exception e) {
                    // JSON 解析错误，删除损坏的配置文件
                    Log.e(TAG, "Corrupted config file, deleting: " + file.getName() + " - " + e.getMessage());
                    file.delete();
                }
            }
        }
        
        Log.i(TAG, "Loaded " + configs.size() + " configs");
        return configs;
    }

    /**
     * 删除配置
     */
    public boolean deleteConfig(String packageName) {
        String filename = packageName.replace(".", "_") + ".json";
        File file = new File(context.getFilesDir(), CONFIG_DIR + "/" + filename);
        boolean deleted = file.delete();
        Log.i(TAG, "Config deleted: " + filename + " - " + deleted);
        return deleted;
    }

    /**
     * 导出配置到外部存储
     */
    public String exportConfig(AppConfig config) {
        File exportDir = context.getExternalFilesDir("exports");
        if (exportDir != null && !exportDir.exists()) {
            exportDir.mkdirs();
        }
        
        String filename = config.getPackageName().replace(".", "_") + "_export.json";
        File file = new File(exportDir, filename);
        
        try (FileWriter writer = new FileWriter(file)) {
            gson.toJson(config, writer);
            Log.i(TAG, "Config exported: " + file.getAbsolutePath());
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Failed to export config: " + e.getMessage());
            return null;
        }
    }
}
