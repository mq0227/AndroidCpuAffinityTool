package com.threadaffinity.manager.util;

import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;

/**
 * Root权限辅助类
 * 尽量用Java直接读取，只有必须root的操作才用shell
 */
public class RootHelper {
    private static final String TAG = "RootHelper";

    /**
     * 检查是否有Root权限
     */
    public static boolean hasRootAccess() {
        String result = executeRootCommand("id");
        boolean hasRoot = result != null && result.contains("uid=0");
        Log.i(TAG, "Root access check: " + hasRoot);
        return hasRoot;
    }

    /**
     * 执行Root命令
     */
    public static String executeRootCommand(String command) {
        return RootShell.execute(command, 5000);
    }

    /**
     * 直接读取CPU频率（不需要root）
     */
    public static int[] getCpuFrequenciesDirect() {
        int[] frequencies = new int[8];
        for (int i = 0; i < 8; i++) {
            try {
                RandomAccessFile file = new RandomAccessFile(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq", "r");
                String line = file.readLine();
                file.close();
                if (line != null) {
                    frequencies[i] = Integer.parseInt(line.trim()) / 1000;
                }
            } catch (Exception e) {
                frequencies[i] = 0;
            }
        }
        return frequencies;
    }
    
    /**
     * 直接读取/proc/stat（不需要root）
     */
    public static String readProcStatDirect() {
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"));
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < 9) {
                sb.append(line).append("\n");
                count++;
            }
            reader.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to read /proc/stat: " + e.getMessage());
        }
        return sb.toString();
    }
    
    /**
     * 直接读取/proc/stat（兼容旧接口）
     */
    public static String readProcStat() {
        return readProcStatDirect();
    }
    
    /**
     * 关闭Shell（兼容旧接口）
     */
    public static void closeShell() {
        // 不需要做什么，每次都是新进程
    }
}
