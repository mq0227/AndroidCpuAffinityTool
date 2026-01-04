package com.threadaffinity.manager.util;

import android.util.Log;
import com.threadaffinity.manager.model.ThreadInfo;
import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 进程和线程信息获取辅助类
 */
public class ProcessHelper {
    private static final String TAG = "ProcessHelper";

    /**
     * 检查进程是否还在运行
     */
    public static boolean isProcessRunning(int pid) {
        if (pid <= 0) return false;
        try {
            // 使用root命令检查进程是否存在
            String result = RootHelper.executeRootCommand("ls /proc/" + pid + "/stat");
            return result != null && !result.isEmpty() && !result.contains("No such file");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 获取指定包名的进程ID
     */
    public static int getPidByPackage(String packageName) {
        Log.d(TAG, "Getting PID for package: " + packageName);
        
        try {
            // 使用pidof命令
            String result = RootHelper.executeRootCommand("pidof " + packageName);
            if (result != null && !result.trim().isEmpty()) {
                String[] pids = result.trim().split("\\s+");
                int pid = Integer.parseInt(pids[0]);
                Log.i(TAG, "Found PID: " + pid + " for " + packageName);
                return pid;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get PID: " + e.getMessage());
        }
        
        return -1;
    }

    /**
     * 获取进程的所有线程（优化版，使用awk批量获取）
     */
    public static List<ThreadInfo> getThreads(int pid) {
        List<ThreadInfo> threads = new ArrayList<>();
        Log.d(TAG, "Getting threads for PID: " + pid);
        
        try {
            // 使用awk一次性获取所有线程ID和名称，避免for循环+cat导致大量fork
            String result = RootHelper.executeRootCommand(
                "ls /proc/" + pid + "/task 2>/dev/null | awk -v pid=" + pid + " '{" +
                "tid=$1; comm_file=\"/proc/\"pid\"/task/\"tid\"/comm\"; name=\"\"; " +
                "if ((getline name < comm_file) > 0) { gsub(/[ \\t\\r\\n]/, \"\", name); } close(comm_file); " +
                "print tid\":\"name; " +
                "}'");
            
            if (result != null && !result.trim().isEmpty()) {
                String[] lines = result.trim().split("\n");
                
                for (String line : lines) {
                    try {
                        String[] parts = line.split(":", 2);
                        if (parts.length >= 1) {
                            int tid = Integer.parseInt(parts[0].trim());
                            String name = parts.length > 1 ? parts[1].trim() : "Thread-" + tid;
                            if (name.isEmpty()) name = "Thread-" + tid;
                            threads.add(new ThreadInfo(tid, name));
                        }
                    } catch (NumberFormatException e) {
                        // 忽略非数字
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get threads: " + e.getMessage());
        }
        
        Log.i(TAG, "Found " + threads.size() + " threads for PID " + pid);
        return threads;
    }

    /**
     * 获取单个线程的详细信息（使用awk一次性读取）
     */
    public static ThreadInfo getThreadInfo(int pid, int tid) {
        try {
            // 使用awk一次性读取comm和stat
            String result = RootHelper.executeRootCommand(
                "awk 'BEGIN { " +
                "comm_file=\"/proc/" + pid + "/task/" + tid + "/comm\"; " +
                "stat_file=\"/proc/" + pid + "/task/" + tid + "/stat\"; " +
                "name=\"\"; state=\"\"; " +
                "if ((getline name < comm_file) > 0) { gsub(/[ \\t\\r\\n]/, \"\", name); } close(comm_file); " +
                "if ((getline stat_line < stat_file) > 0) { n=split(stat_line, f, \" \"); if (n>=3) state=f[3]; } close(stat_file); " +
                "print name\"|\"state; " +
                "}'");
            
            if (result == null || result.trim().isEmpty()) {
                return null;
            }
            
            String[] parts = result.trim().split("\\|");
            String name = parts.length > 0 ? parts[0].trim() : "";
            String stateChar = parts.length > 1 ? parts[1].trim() : "";
            
            if (name.isEmpty()) {
                name = "Thread-" + tid;
            }
            
            ThreadInfo info = new ThreadInfo(tid, name);
            if (!stateChar.isEmpty()) {
                info.setState(parseState(stateChar));
            }
            
            Log.d(TAG, "Thread info: tid=" + tid + ", name=" + name + ", state=" + info.getState());
            return info;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to get thread info for tid " + tid + ": " + e.getMessage());
            return null;
        }
    }

    private static String parseState(String stateChar) {
        switch (stateChar) {
            case "R": return "Running";
            case "S": return "Sleeping";
            case "D": return "Disk Sleep";
            case "Z": return "Zombie";
            case "T": return "Stopped";
            case "t": return "Tracing";
            case "X": return "Dead";
            default: return stateChar;
        }
    }

    /**
     * 刷新线程的CPU使用率和运行核心信息（使用awk批量处理）
     */
    public static void refreshThreadStats(int pid, List<ThreadInfo> threads) {
        if (threads == null || threads.isEmpty()) return;
        
        try {
            // 使用awk一次性读取所有线程的stat文件，获取运行核心
            String result = RootHelper.executeRootCommand(
                "ls /proc/" + pid + "/task 2>/dev/null | awk -v pid=" + pid + " '{" +
                "tid=$1; stat_file=\"/proc/\"pid\"/task/\"tid\"/stat\"; " +
                "if ((getline stat_line < stat_file) > 0) { " +
                "  n=split(stat_line, f, \" \"); " +
                "  if (n >= 39) print tid\"|\"f[39]; " +
                "} close(stat_file); " +
                "}'");
            
            if (result != null && !result.trim().isEmpty()) {
                // 构建tid到cpu的映射
                java.util.Map<Integer, Integer> tidToCpu = new java.util.HashMap<>();
                for (String line : result.trim().split("\n")) {
                    String[] parts = line.split("\\|");
                    if (parts.length >= 2) {
                        try {
                            int tid = Integer.parseInt(parts[0].trim());
                            int cpu = Integer.parseInt(parts[1].trim());
                            tidToCpu.put(tid, cpu);
                        } catch (NumberFormatException e) {
                            // 忽略
                        }
                    }
                }
                
                // 更新线程信息
                for (ThreadInfo thread : threads) {
                    Integer cpu = tidToCpu.get(thread.getTid());
                    if (cpu != null) {
                        thread.setRunningCpu(cpu);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh thread stats: " + e.getMessage());
        }
    }
}
