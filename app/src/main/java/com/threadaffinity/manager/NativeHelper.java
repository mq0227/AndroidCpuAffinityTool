package com.threadaffinity.manager;

import android.util.Log;

/**
 * Native方法封装类
 * 提供线程亲和性设置和CPU监控的JNI接口
 */
public class NativeHelper {
    private static final String TAG = "NativeHelper";

    static {
        try {
            System.loadLibrary("threadaffinity");
            Log.i(TAG, "Native library loaded successfully");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library: " + e.getMessage());
        }
    }

    /**
     * 设置线程亲和性
     * @param tid 线程ID
     * @param mask CPU亲和性掩码
     * @return 是否成功
     */
    public static native boolean setThreadAffinity(int tid, long mask);

    /**
     * 获取线程亲和性
     * @param tid 线程ID
     * @return CPU亲和性掩码
     */
    public static native long getThreadAffinity(int tid);

    /**
     * 获取CPU核心数
     * @return CPU核心数
     */
    public static native int getCpuCount();

    /**
     * 获取各CPU使用率
     * @return 使用率数组，index 0为总体，1-N为各核心
     */
    public static native float[] getCpuUsage();

    /**
     * 获取线程CPU使用率
     * @param pid 进程ID
     * @param tid 线程ID
     * @return CPU使用率百分比
     */
    public static native float getThreadCpuUsage(int pid, int tid);

    /**
     * 获取线程当前运行的CPU核心
     * @param tid 线程ID
     * @return CPU核心编号
     */
    public static native int getThreadRunningCpu(int tid);
    
    /**
     * 获取进程的所有线程信息
     * @param pid 进程ID
     * @return 线程信息数组，每个元素格式: "tid:name:cpu"
     */
    public static native String[] getProcessThreads(int pid);
    
    /**
     * 读取线程名
     * @param pid 进程ID
     * @param tid 线程ID
     * @return 线程名
     */
    public static native String getThreadName(int pid, int tid);
    
    /**
     * 通过包名获取进程ID
     * @param packageName 包名
     * @return 进程ID，未找到返回-1
     */
    public static native int getPidByPackage(String packageName);
    
    /**
     * 检查进程是否存在
     * @param pid 进程ID
     * @return 是否存在
     */
    public static native boolean isProcessRunning(int pid);
    
    /**
     * 关闭持久化 Root Shell
     * 在服务销毁时调用
     */
    public static native void closeRootShell();
    
    /**
     * 检查 Root Shell 是否存活
     * @return 是否存活
     */
    public static native boolean isRootShellAlive();
}
