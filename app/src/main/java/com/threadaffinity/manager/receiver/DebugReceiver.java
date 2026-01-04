package com.threadaffinity.manager.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.threadaffinity.manager.NativeHelper;
import com.threadaffinity.manager.model.AppConfig;
import com.threadaffinity.manager.model.ThreadInfo;
import com.threadaffinity.manager.util.ConfigManager;
import com.threadaffinity.manager.util.ProcessHelper;
import com.threadaffinity.manager.util.RootHelper;
import java.util.List;
import java.util.Map;

/**
 * 调试后门广播接收器
 * 
 * 使用方法 (通过 adb):
 * 
 * 1. 设置线程亲和性:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd set_affinity --ei tid 12345 --el mask 240
 * 
 * 2. 获取线程亲和性:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd get_affinity --ei tid 12345
 * 
 * 3. 获取进程所有线程:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd list_threads --es package com.example.app
 * 
 * 4. 批量设置进程所有线程亲和性:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd set_process_affinity --es package com.example.app --el mask 240
 * 
 * 5. 获取CPU信息:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd cpu_info
 * 
 * 6. 应用已保存的配置 (按线程名称匹配):
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd apply_config --es package com.tencent.tmgp.dfm
 * 
 * 7. 保存当前配置:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd save_config --es package com.tencent.tmgp.dfm --es threads "RenderThread:120,GameThread:120"
 * 
 * 8. 查看已保存的配置:
 *    adb shell am broadcast -n com.threadaffinity.manager/.receiver.DebugReceiver -a com.threadaffinity.DEBUG --es cmd show_config --es package com.tencent.tmgp.dfm
 */
public class DebugReceiver extends BroadcastReceiver {
    private static final String TAG = "DebugReceiver";
    public static final String ACTION = "com.threadaffinity.DEBUG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }

        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) {
            Log.e(TAG, "No command specified");
            return;
        }

        Log.i(TAG, "Received command: " + cmd);

        switch (cmd) {
            case "set_affinity":
                handleSetAffinity(intent);
                break;
            case "get_affinity":
                handleGetAffinity(intent);
                break;
            case "list_threads":
                handleListThreads(intent);
                break;
            case "set_process_affinity":
                handleSetProcessAffinity(intent);
                break;
            case "cpu_info":
                handleCpuInfo();
                break;
            case "taskset":
                handleTaskset(intent);
                break;
            case "apply_config":
                handleApplyConfig(context, intent);
                break;
            case "save_config":
                handleSaveConfig(context, intent);
                break;
            case "show_config":
                handleShowConfig(context, intent);
                break;
            default:
                Log.e(TAG, "Unknown command: " + cmd);
        }
    }

    private void handleSetAffinity(Intent intent) {
        int tid = intent.getIntExtra("tid", -1);
        long mask = intent.getLongExtra("mask", -1);

        if (tid <= 0) {
            Log.e(TAG, "Invalid tid: " + tid);
            return;
        }
        if (mask < 0) {
            Log.e(TAG, "Invalid mask");
            return;
        }

        Log.i(TAG, "Setting affinity: tid=" + tid + ", mask=0x" + Long.toHexString(mask));
        boolean result = NativeHelper.setThreadAffinity(tid, mask);
        Log.i(TAG, "Result: " + (result ? "SUCCESS" : "FAILED"));
    }

    private void handleGetAffinity(Intent intent) {
        int tid = intent.getIntExtra("tid", -1);

        if (tid <= 0) {
            Log.e(TAG, "Invalid tid: " + tid);
            return;
        }

        long mask = NativeHelper.getThreadAffinity(tid);
        Log.i(TAG, "Thread " + tid + " affinity: 0x" + Long.toHexString(mask) + " (" + mask + ")");
    }

    private void handleListThreads(Intent intent) {
        int pid = intent.getIntExtra("pid", -1);
        String packageName = intent.getStringExtra("package");

        if (pid <= 0 && packageName != null) {
            pid = ProcessHelper.getPidByPackage(packageName);
        }

        if (pid <= 0) {
            Log.e(TAG, "Invalid pid or package not found");
            return;
        }

        Log.i(TAG, "Listing threads for PID: " + pid);
        List<ThreadInfo> threads = ProcessHelper.getThreads(pid);
        
        Log.i(TAG, "Found " + threads.size() + " threads:");
        for (ThreadInfo thread : threads) {
            long mask = NativeHelper.getThreadAffinity(thread.getTid());
            Log.i(TAG, String.format("  TID=%d, Name=%s, Affinity=0x%x", 
                thread.getTid(), thread.getName(), mask));
        }
    }

    private void handleSetProcessAffinity(Intent intent) {
        int pid = intent.getIntExtra("pid", -1);
        String packageName = intent.getStringExtra("package");
        long mask = intent.getLongExtra("mask", -1);

        if (pid <= 0 && packageName != null) {
            pid = ProcessHelper.getPidByPackage(packageName);
        }

        if (pid <= 0) {
            Log.e(TAG, "Invalid pid or package not found");
            return;
        }
        if (mask < 0) {
            Log.e(TAG, "Invalid mask");
            return;
        }

        Log.i(TAG, "Setting all threads affinity for PID " + pid + " to 0x" + Long.toHexString(mask));
        List<ThreadInfo> threads = ProcessHelper.getThreads(pid);
        
        int success = 0, failed = 0;
        for (ThreadInfo thread : threads) {
            boolean result = NativeHelper.setThreadAffinity(thread.getTid(), mask);
            if (result) {
                success++;
            } else {
                failed++;
                Log.w(TAG, "Failed to set affinity for tid " + thread.getTid());
            }
        }
        
        Log.i(TAG, "Result: " + success + " success, " + failed + " failed");
    }

    private void handleCpuInfo() {
        int cpuCount = NativeHelper.getCpuCount();
        float[] usage = NativeHelper.getCpuUsage();
        
        Log.i(TAG, "CPU Count: " + cpuCount);
        if (usage != null && usage.length > 0) {
            Log.i(TAG, "CPU Total Usage: " + String.format("%.1f%%", usage[0]));
            for (int i = 1; i < usage.length && i <= cpuCount; i++) {
                Log.i(TAG, "  CPU" + (i-1) + ": " + String.format("%.1f%%", usage[i]));
            }
        }
    }

    private void handleTaskset(Intent intent) {
        String args = intent.getStringExtra("args");
        if (args == null || args.isEmpty()) {
            Log.e(TAG, "No taskset args specified");
            return;
        }

        Log.i(TAG, "Executing: taskset " + args);
        String result = RootHelper.executeRootCommand("taskset " + args);
        Log.i(TAG, "Output: " + result);
    }

    /**
     * 应用已保存的配置（按线程名称匹配）
     */
    private void handleApplyConfig(Context context, Intent intent) {
        String packageName = intent.getStringExtra("package");
        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "No package specified");
            return;
        }

        ConfigManager configManager = new ConfigManager(context);
        AppConfig config = configManager.loadConfig(packageName);
        
        if (config == null) {
            Log.e(TAG, "No config found for: " + packageName);
            return;
        }

        int pid = ProcessHelper.getPidByPackage(packageName);
        if (pid <= 0) {
            Log.e(TAG, "Process not running: " + packageName);
            return;
        }

        Log.i(TAG, "Applying config for " + packageName + " (PID: " + pid + ")");
        Log.i(TAG, "Config has " + config.getThreadAffinities().size() + " thread rules");

        List<ThreadInfo> threads = ProcessHelper.getThreads(pid);
        
        int matched = 0, applied = 0, failed = 0;
        
        for (ThreadInfo thread : threads) {
            String threadName = thread.getName();
            // 从配置获取掩码（十六进制字符串 -> long）
            Long mask = config.getThreadAffinity(threadName);
            
            if (mask != null) {
                matched++;
                boolean result = NativeHelper.setThreadAffinity(thread.getTid(), mask);
                if (result) {
                    applied++;
                    Log.i(TAG, "  Applied: " + threadName + " (TID:" + thread.getTid() + ") -> 0x" + Long.toHexString(mask));
                } else {
                    failed++;
                    Log.w(TAG, "  Failed: " + threadName + " (TID:" + thread.getTid() + ")");
                }
            }
        }
        
        Log.i(TAG, "Result: " + matched + " matched, " + applied + " applied, " + failed + " failed");
        Log.i(TAG, "Total threads: " + threads.size());
    }

    /**
     * 保存配置
     */
    private void handleSaveConfig(Context context, Intent intent) {
        String packageName = intent.getStringExtra("package");
        String threadsStr = intent.getStringExtra("threads");
        
        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "No package specified");
            return;
        }

        AppConfig config = new AppConfig(packageName, packageName);
        
        // 解析线程配置: "RenderThread:120,GameThread:120"
        if (threadsStr != null && !threadsStr.isEmpty()) {
            String[] pairs = threadsStr.split(",");
            for (String pair : pairs) {
                String[] parts = pair.split(":");
                if (parts.length == 2) {
                    try {
                        String name = parts[0].trim();
                        long mask = Long.parseLong(parts[1].trim());
                        config.addThreadAffinity(name, mask);
                        Log.d(TAG, "Added rule: " + name + " -> " + mask);
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "Invalid mask in: " + pair);
                    }
                }
            }
        }

        ConfigManager configManager = new ConfigManager(context);
        boolean saved = configManager.saveConfig(config);
        
        if (saved) {
            Log.i(TAG, "Config saved for " + packageName + " with " + config.getThreadAffinities().size() + " rules");
        } else {
            Log.e(TAG, "Failed to save config");
        }
    }

    /**
     * 显示已保存的配置
     */
    private void handleShowConfig(Context context, Intent intent) {
        String packageName = intent.getStringExtra("package");
        if (packageName == null || packageName.isEmpty()) {
            Log.e(TAG, "No package specified");
            return;
        }

        ConfigManager configManager = new ConfigManager(context);
        AppConfig config = configManager.loadConfig(packageName);
        
        if (config == null) {
            Log.i(TAG, "No config found for: " + packageName);
            return;
        }

        Log.i(TAG, "=== Config for " + packageName + " ===");
        Log.i(TAG, "App Name: " + config.getAppName());
        Log.i(TAG, "Thread Affinities:");
        
        // 遍历十六进制字符串格式的配置
        for (Map.Entry<String, String> entry : config.getThreadAffinities().entrySet()) {
            String hexMask = entry.getValue();
            long mask = AppConfig.parseHexMask(hexMask);
            Log.i(TAG, "  " + entry.getKey() + " -> " + hexMask + " (" + mask + ")");
        }
    }
}
