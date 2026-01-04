package com.threadaffinity.manager.util;

import android.util.Log;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 持久化Root Shell - 单实例，大缓冲区
 * 支持自动重连和错误恢复
 */
public class RootShell {
    private static final String TAG = "RootShell";
    private static final String END_TOKEN = "___END___";
    private static final int BUFFER_SIZE = 12 * 1024 * 1024; // 12MB 缓冲区
    private static final int MAX_CONSECUTIVE_FAILURES = 3; // 连续失败次数阈值
    
    private static Process process;
    private static DataOutputStream stdin;
    private static BufferedReader stdout;
    private static final Object lock = new Object();
    private static volatile boolean initialized = false;
    private static AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private static long lastSuccessTime = 0;
    
    /**
     * 初始化Shell
     */
    private static boolean init() {
        synchronized (lock) {
            if (initialized && isAlive()) {
                return true;
            }
            
            close();
            
            try {
                Log.i(TAG, "Initializing root shell...");
                process = Runtime.getRuntime().exec("su");
                stdin = new DataOutputStream(process.getOutputStream());
                // 使用大缓冲区
                stdout = new BufferedReader(new InputStreamReader(process.getInputStream()), BUFFER_SIZE);
                initialized = true;
                consecutiveFailures.set(0);
                lastSuccessTime = System.currentTimeMillis();
                Log.i(TAG, "Root shell initialized with " + BUFFER_SIZE + " bytes buffer");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Failed to init shell: " + e.getMessage());
                return false;
            }
        }
    }
    
    private static boolean isAlive() {
        if (process == null) return false;
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }
    
    /**
     * 执行命令
     */
    public static String execute(String command, long timeoutMs) {
        synchronized (lock) {
            // 检查是否需要重新初始化
            if (!init()) {
                Log.e(TAG, "Failed to init shell for command: " + command);
                return null;
            }
            
            // 如果连续失败次数过多，尝试重新初始化
            if (consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
                Log.w(TAG, "Too many consecutive failures, reinitializing shell...");
                close();
                if (!init()) {
                    return null;
                }
            }
            
            try {
                StringBuilder output = new StringBuilder(4096);
                long token = System.nanoTime();
                String endMarker = END_TOKEN + token;
                
                // 发送命令
                stdin.writeBytes(command + "\n");
                stdin.writeBytes("echo '" + endMarker + "'\n");
                stdin.flush();
                
                // 读取输出
                char[] buffer = new char[8192];
                StringBuilder lineBuffer = new StringBuilder();
                long deadline = System.currentTimeMillis() + timeoutMs;
                boolean foundMarker = false;
                
                while (System.currentTimeMillis() < deadline) {
                    if (stdout.ready()) {
                        int read = stdout.read(buffer);
                        if (read > 0) {
                            lineBuffer.append(buffer, 0, read);
                            
                            // 检查是否包含结束标记
                            int endIdx = lineBuffer.indexOf(endMarker);
                            if (endIdx >= 0) {
                                output.append(lineBuffer.substring(0, endIdx));
                                foundMarker = true;
                                break;
                            }
                        }
                    } else {
                        Thread.sleep(2);
                    }
                }
                
                // 如果没找到结束标记
                if (!foundMarker) {
                    if (lineBuffer.length() > 0) {
                        int endIdx = lineBuffer.indexOf(endMarker);
                        if (endIdx >= 0) {
                            output.append(lineBuffer.substring(0, endIdx));
                            foundMarker = true;
                        } else {
                            // 超时，返回已读取的内容，但标记为失败
                            Log.w(TAG, "Command timeout, partial output: " + lineBuffer.length() + " chars");
                            output.append(lineBuffer);
                            consecutiveFailures.incrementAndGet();
                        }
                    } else {
                        Log.w(TAG, "Command timeout with no output");
                        consecutiveFailures.incrementAndGet();
                    }
                }
                
                if (foundMarker) {
                    // 成功，重置失败计数
                    consecutiveFailures.set(0);
                    lastSuccessTime = System.currentTimeMillis();
                }
                
                return output.toString();
            } catch (Exception e) {
                Log.e(TAG, "Execute failed: " + e.getMessage());
                consecutiveFailures.incrementAndGet();
                
                // 如果是 IO 异常，关闭 shell 以便下次重新初始化
                if (e instanceof java.io.IOException) {
                    Log.w(TAG, "IO exception, closing shell for reinit");
                    close();
                }
                
                return null;
            }
        }
    }
    
    /**
     * 检查 Shell 是否健康
     */
    public static boolean isHealthy() {
        synchronized (lock) {
            return initialized && isAlive() && consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES;
        }
    }
    
    /**
     * 获取上次成功执行的时间
     */
    public static long getLastSuccessTime() {
        return lastSuccessTime;
    }
    
    /**
     * 关闭Shell
     */
    public static void close() {
        synchronized (lock) {
            initialized = false;
            try { if (stdin != null) { stdin.writeBytes("exit\n"); stdin.flush(); stdin.close(); } } catch (Exception e) {}
            try { if (stdout != null) stdout.close(); } catch (Exception e) {}
            try { if (process != null) process.destroy(); } catch (Exception e) {}
            stdin = null;
            stdout = null;
            process = null;
            Log.i(TAG, "Root shell closed");
        }
    }
}
