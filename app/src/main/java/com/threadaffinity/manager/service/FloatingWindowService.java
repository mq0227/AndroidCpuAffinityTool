package com.threadaffinity.manager.service;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.app.NotificationCompat;
import com.threadaffinity.manager.MainActivity;
import com.threadaffinity.manager.NativeHelper;
import com.threadaffinity.manager.R;
import com.threadaffinity.manager.model.AppConfig;
import com.threadaffinity.manager.model.ThreadInfo;
import com.threadaffinity.manager.util.CpuInfo;
import com.threadaffinity.manager.util.ConfigManager;
import com.threadaffinity.manager.util.LocaleHelper;
import com.threadaffinity.manager.util.ProcessHelper;
import com.threadaffinity.manager.util.RootHelper;
import java.util.*;
import java.util.concurrent.*;

public class FloatingWindowService extends Service {
    private static final String TAG = "FloatingWindowService";
    private static final String CHANNEL_ID = "cpu_monitor_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int UPDATE_INTERVAL_MS = 1800; // 1.8秒统一刷新间隔
    private static final int FPS_UPDATE_INTERVAL_MS = 1200; // 1.2秒帧率刷新间隔
    private static final int AFFINITY_APPLY_INTERVAL_MS = 10000; // 10秒循环应用亲和性（分散写入）
    
    // 配置文件中存储的键名（固定字符串，不随语言变化）
    private static final String CONFIG_KEY_THIS_APP = "_THIS_APP_";
    
    // 应用了语言设置的 Context
    private Context localizedContext;

    private WindowManager windowManager;
    private View floatingView;
    private LinearLayout rootLayout, layoutMinimized, layoutMaximized, layoutHeader;
    private LinearLayout layoutCores, layoutThreadsSection, layoutThreads;
    private LinearLayout layoutSystemThreadsSection, layoutSystemThreads; // 系统线程区域
    private TextView tvTitle, tvCpuTotal, tvAlpha, tvMinimize, tvClose, tvExpand;
    private TextView tvSystemThreadsTitle, tvThreadsTitle;
    private TextView tvFps, tvMiniFps; // 帧率显示
    private TextView[] tvFreqs = new TextView[8];
    private TextView[] tvMiniCores = new TextView[8];
    private View[] barCores = new View[8];
    private View[] barMinis = new View[8];  // 最小化视图的8个小条
    private View viewStatus;

    private int pid;
    private String packageName;
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService affinityScheduler; // 循环应用亲和性
    private ScheduledExecutorService fpsScheduler; // 帧率更新
    private Handler mainHandler;
    private boolean isMinimized = false;
    private int cpuCount = 8;
    private int[] maxFreqs = new int[8];
    
    private ConfigManager configManager;
    
    // CPU负载历史数据
    private long[][] lastCpuTimes = new long[9][2]; // [cpu][idle, total]
    private boolean cpuTimesInitialized = false;
    
    // 帧率相关
    private volatile int currentFps = 0;
    private long lastValidTimestamp = 0;
    private int recentFrameCount = 0;
    private long recentFrameStartTime = 0;
    
    // 透明度
    private int alphaLevel = 2;
    private float[] alphaValues = {0.5f, 0.7f, 0.85f, 0.95f, 1.0f};

    // 拖动
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        // 应用语言设置
        localizedContext = LocaleHelper.applyLanguage(this);
        mainHandler = new Handler(Looper.getMainLooper());
        cpuCount = NativeHelper.getCpuCount();
        prefs = getSharedPreferences("floating_window", MODE_PRIVATE);
        alphaLevel = prefs.getInt("alpha_level", 2);
        configManager = new ConfigManager(this);
        readMaxFrequencies();
        createNotificationChannel();
        
        // 注释掉自绑定，避免导致系统重启
        // bindSelfToBigCores();
    }
    
    /**
     * 将本APK绑定到大核心6、7 (暂时禁用)
     */
    private void bindSelfToBigCores() {
        try {
            int myTid = android.os.Process.myTid();
            // 设置CPU亲和性到核心6和7 (掩码: 0b11000000 = 192)
            boolean success = NativeHelper.setThreadAffinity(myTid, 0xC0L); // 核心6、7
            Log.d(TAG, "Bound self (tid=" + myTid + ") to cores 6,7: " + success);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind self to big cores: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean startMinimized = false;
        if (intent != null) {
            pid = intent.getIntExtra("pid", -1);
            packageName = intent.getStringExtra("package");
            startMinimized = intent.getBooleanExtra("minimized", false);
            Log.d(TAG, "onStartCommand: pid=" + pid + ", package=" + packageName + ", minimized=" + startMinimized);
        }
        startForeground(NOTIFICATION_ID, createNotification());
        
        // 防止重复创建悬浮窗
        if (floatingView == null) {
            createFloatingWindow();
            // 快速启动时默认最小化
            if (startMinimized) {
                isMinimized = true;
                layoutMinimized.setVisibility(View.VISIBLE);
                layoutMaximized.setVisibility(View.GONE);
            }
            startMonitoring();
        } else {
            // 已存在悬浮窗，只更新标题
            if (tvTitle != null && packageName != null) {
                String shortName = packageName.substring(Math.max(0, packageName.lastIndexOf('.') + 1));
                tvTitle.setText(shortName);
                if (tvThreadsTitle != null) {
                    tvThreadsTitle.setText(shortName);
                }
            }
            Log.d(TAG, "FloatingWindow already exists, skipping creation");
        }
        return START_STICKY;
    }

    private void readMaxFrequencies() {
        try {
            for (int i = 0; i < 8; i++) {
                String result = RootHelper.executeRootCommand(
                    "cat /sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                if (result != null && !result.isEmpty()) {
                    maxFreqs[i] = Integer.parseInt(result.trim()) / 1000;
                } else {
                    maxFreqs[i] = i < 3 ? 2000 : (i < 7 ? 2800 : 3200);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading max frequencies: " + e.getMessage());
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, localizedContext.getString(R.string.cpu_monitor_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(localizedContext.getString(R.string.cpu_monitor_running))
                .setContentText(packageName != null ? packageName : localizedContext.getString(R.string.monitoring))
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createFloatingWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        // 使用应用了语言设置的 Context 来 inflate 布局
        floatingView = LayoutInflater.from(localizedContext).inflate(R.layout.layout_floating_window, null);
        
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = prefs.getInt("pos_x", 0);
        params.y = prefs.getInt("pos_y", 100);

        initViews();
        setupTouchListener();
        updateAlpha();
        windowManager.addView(floatingView, params);
    }

    private void initViews() {
        rootLayout = floatingView.findViewById(R.id.rootLayout);
        layoutMinimized = floatingView.findViewById(R.id.layoutMinimized);
        layoutMaximized = floatingView.findViewById(R.id.layoutMaximized);
        layoutHeader = floatingView.findViewById(R.id.layoutHeader);
        layoutCores = floatingView.findViewById(R.id.layoutCores);
        layoutThreadsSection = floatingView.findViewById(R.id.layoutThreadsSection);
        layoutThreads = floatingView.findViewById(R.id.layoutThreads);
        
        // 系统线程区域
        layoutSystemThreadsSection = floatingView.findViewById(R.id.layoutSystemThreadsSection);
        layoutSystemThreads = floatingView.findViewById(R.id.layoutSystemThreads);
        tvSystemThreadsTitle = floatingView.findViewById(R.id.tvSystemThreadsTitle);
        tvThreadsTitle = floatingView.findViewById(R.id.tvThreadsTitle);
        
        tvTitle = floatingView.findViewById(R.id.tvTitle);
        tvCpuTotal = floatingView.findViewById(R.id.tvCpuTotal);
        tvAlpha = floatingView.findViewById(R.id.tvAlpha);
        tvMinimize = floatingView.findViewById(R.id.tvMinimize);
        tvClose = floatingView.findViewById(R.id.tvClose);
        tvExpand = floatingView.findViewById(R.id.tvExpand);
        viewStatus = floatingView.findViewById(R.id.viewStatus);
        
        // 帧率显示
        tvFps = floatingView.findViewById(R.id.tvFps);
        tvMiniFps = floatingView.findViewById(R.id.tvMiniFps);

        // 频率显示
        tvFreqs[0] = floatingView.findViewById(R.id.tvFreq0);
        tvFreqs[1] = floatingView.findViewById(R.id.tvFreq1);
        tvFreqs[2] = floatingView.findViewById(R.id.tvFreq2);
        tvFreqs[3] = floatingView.findViewById(R.id.tvFreq3);
        tvFreqs[4] = floatingView.findViewById(R.id.tvFreq4);
        tvFreqs[5] = floatingView.findViewById(R.id.tvFreq5);
        tvFreqs[6] = floatingView.findViewById(R.id.tvFreq6);
        tvFreqs[7] = floatingView.findViewById(R.id.tvFreq7);

        // 最小化核心频率显示
        tvMiniCores[0] = floatingView.findViewById(R.id.tvMiniCore0);
        tvMiniCores[1] = floatingView.findViewById(R.id.tvMiniCore1);
        tvMiniCores[2] = floatingView.findViewById(R.id.tvMiniCore2);
        tvMiniCores[3] = floatingView.findViewById(R.id.tvMiniCore3);
        tvMiniCores[4] = floatingView.findViewById(R.id.tvMiniCore4);
        tvMiniCores[5] = floatingView.findViewById(R.id.tvMiniCore5);
        tvMiniCores[6] = floatingView.findViewById(R.id.tvMiniCore6);
        tvMiniCores[7] = floatingView.findViewById(R.id.tvMiniCore7);

        // 最小化视图的小条
        barMinis[0] = floatingView.findViewById(R.id.barMini0);
        barMinis[1] = floatingView.findViewById(R.id.barMini1);
        barMinis[2] = floatingView.findViewById(R.id.barMini2);
        barMinis[3] = floatingView.findViewById(R.id.barMini3);
        barMinis[4] = floatingView.findViewById(R.id.barMini4);
        barMinis[5] = floatingView.findViewById(R.id.barMini5);
        barMinis[6] = floatingView.findViewById(R.id.barMini6);
        barMinis[7] = floatingView.findViewById(R.id.barMini7);

        // 柱状图
        barCores[0] = floatingView.findViewById(R.id.barCore0);
        barCores[1] = floatingView.findViewById(R.id.barCore1);
        barCores[2] = floatingView.findViewById(R.id.barCore2);
        barCores[3] = floatingView.findViewById(R.id.barCore3);
        barCores[4] = floatingView.findViewById(R.id.barCore4);
        barCores[5] = floatingView.findViewById(R.id.barCore5);
        barCores[6] = floatingView.findViewById(R.id.barCore6);
        barCores[7] = floatingView.findViewById(R.id.barCore7);

        String shortName = packageName != null ? 
            packageName.substring(Math.max(0, packageName.lastIndexOf('.') + 1)) : "CPU";
        tvTitle.setText(shortName);
        // 设置线程列表标题为包名（与左上角一致）
        if (tvThreadsTitle != null) {
            tvThreadsTitle.setText(shortName);
        }
        // 手动设置系统线程标题（使用正确语言的 Context）
        if (tvSystemThreadsTitle != null) {
            tvSystemThreadsTitle.setText(localizedContext.getString(R.string.system_threads));
        }

        tvMinimize.setOnClickListener(v -> toggleMinimize());
        tvClose.setOnClickListener(v -> killApp());
        tvAlpha.setOnClickListener(v -> cycleAlpha());
        tvExpand.setOnClickListener(v -> toggleMinimize());
        layoutMinimized.setOnClickListener(v -> toggleMinimize());
    }

    private void setupTouchListener() {
        View.OnTouchListener dragListener = (v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(floatingView, params);
                    return true;
                case MotionEvent.ACTION_UP:
                    prefs.edit().putInt("pos_x", params.x).putInt("pos_y", params.y).apply();
                    // 检测是否是点击
                    float dx = Math.abs(event.getRawX() - initialTouchX);
                    float dy = Math.abs(event.getRawY() - initialTouchY);
                    if (dx < 10 && dy < 10) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        };
        // 整个根布局都可以拖动，支持小窗模式
        rootLayout.setOnTouchListener(dragListener);
        layoutHeader.setOnTouchListener(dragListener);
        layoutMinimized.setOnTouchListener(dragListener);
    }

    private void cycleAlpha() {
        alphaLevel = (alphaLevel + 1) % alphaValues.length;
        prefs.edit().putInt("alpha_level", alphaLevel).apply();
        updateAlpha();
    }

    private void updateAlpha() {
        if (rootLayout != null) {
            rootLayout.setAlpha(alphaValues[alphaLevel]);
        }
    }

    private void toggleMinimize() {
        isMinimized = !isMinimized;
        layoutMinimized.setVisibility(isMinimized ? View.VISIBLE : View.GONE);
        layoutMaximized.setVisibility(isMinimized ? View.GONE : View.VISIBLE);
        // 数据持续更新，切换只改变UI显示，不重置任何状态
    }

    private void startMonitoring() {
        // 各任务错开执行，避免同时占用 CPU
        // CPU 信息更新（1.8秒周期，0ms 开始）
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::updateStats, 0, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // 帧率更新（1.2秒周期，独立调度）
        fpsScheduler = Executors.newSingleThreadScheduledExecutor();
        fpsScheduler.scheduleAtFixedRate(this::updateFpsAsync, 300, FPS_UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // APP 线程列表更新（1.8秒周期，600ms 开始）
        threadScheduler = Executors.newSingleThreadScheduledExecutor();
        threadScheduler.scheduleAtFixedRate(this::updateThreadsAsync, 600, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // 系统线程列表更新（1.8秒周期，1200ms 开始）
        sysThreadScheduler = Executors.newSingleThreadScheduledExecutor();
        sysThreadScheduler.scheduleAtFixedRate(this::updateSystemThreadsAsync, 1200, UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        // 循环应用亲和性（10秒周期，2秒后开始，避免启动时集中执行）
        affinityScheduler = Executors.newSingleThreadScheduledExecutor();
        affinityScheduler.scheduleAtFixedRate(this::applyAffinityInBackground, 
            2000, AFFINITY_APPLY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        Log.i(TAG, "Started monitoring for: " + packageName);
    }
    
    /**
     * 异步更新帧率（独立周期 1.2秒）
     */
    private void updateFpsAsync() {
        try {
            int fps = getCurrentFps();
            final int finalFps = fps;
            mainHandler.post(() -> updateFpsUI(finalFps));
        } catch (Exception e) {
            Log.e(TAG, "Error updating FPS: " + e.getMessage());
        }
    }
    
    /**
     * 更新帧率 UI
     */
    private void updateFpsUI(int fps) {
        if (floatingView == null) return;
        
        if (fps > 0) {
            // 根据帧率设置颜色：55+青色，40-54橙色，<40红色
            int fpsColor = fps >= 55 ? 0xFF00BCD4 : (fps >= 40 ? 0xFFFFB74D : 0xFFEF5350);
            String fpsText = String.valueOf(fps);
            
            if (tvFps != null) {
                tvFps.setText(fpsText);
                tvFps.setTextColor(fpsColor);
            }
            if (tvMiniFps != null) {
                tvMiniFps.setText(fpsText);
                tvMiniFps.setTextColor(fpsColor);
            }
        }
    }
    
    /**
     * 后台循环应用亲和性（不更新UI，静默执行）
     */
    private void applyAffinityInBackground() {
        if (packageName == null || packageName.isEmpty()) return;
        
        try {
            // ========== 调度器参数设置（每10秒强制刷新，防止被系统重置） ==========
            // 1. 禁用能效感知调度 (EAS) - 防止系统基于能效重置亲和性
            // 2. 禁用强制负载均衡 - 防止系统强制迁移线程
            // 3. 禁用 WALT 大任务轮转 - 防止大任务被自动迁移
            // 4. 禁用线程迁移数量
            // 5. 禁用 cpuset 负载均衡
            // 6. 禁用 core_ctl
            // 7. 开启全局 boost，允许 CPU 达到最高频率
            // 8. 禁用努比亚的 cpufreq_ctrl 限制
            // 9. 设置激进升频参数
            RootHelper.executeRootCommand(
                // 禁用调度器的亲和性重置机制
                "sysctl -w kernel.sched_energy_aware=0 2>/dev/null; " +
                "sysctl -w kernel.sched_force_lb_enable=0 2>/dev/null; " +
                "sysctl -w kernel.sched_walt_rotate_big_tasks=0 2>/dev/null; " +
                "sysctl -w kernel.sched_nr_migrate=0 2>/dev/null; " +
                "echo 0 > /dev/cpuset/sched_load_balance 2>/dev/null; " +
                // 禁用 core_ctl
                "echo 0 > /sys/devices/system/cpu/cpu0/core_ctl/enable 2>/dev/null; " +
                // 开启全局 boost
                "chmod 644 /sys/devices/system/cpu/cpufreq/boost 2>/dev/null; " +
                "echo 1 > /sys/devices/system/cpu/cpufreq/boost 2>/dev/null; " +
                // 禁用努比亚 cpufreq_ctrl 限制
                "echo 0 > /sys/devices/system/cpu/cpu0/cpufreq/walt/cpufreq_ctrl 2>/dev/null; " +
                "echo 0 > /sys/devices/system/cpu/cpu3/cpufreq/walt/cpufreq_ctrl 2>/dev/null; " +
                "echo 0 > /sys/devices/system/cpu/cpu7/cpufreq/walt/cpufreq_ctrl 2>/dev/null; " +
                // 设置升频无延迟
                "echo 0 > /sys/devices/system/cpu/cpu0/cpufreq/walt/up_rate_limit_us 2>/dev/null; " +
                "echo 0 > /sys/devices/system/cpu/cpu3/cpufreq/walt/up_rate_limit_us 2>/dev/null; " +
                "echo 0 > /sys/devices/system/cpu/cpu7/cpufreq/walt/up_rate_limit_us 2>/dev/null; " +
                // 设置 40% 负载触发高频
                "echo 40 > /sys/devices/system/cpu/cpu0/cpufreq/walt/hispeed_load 2>/dev/null; " +
                "echo 40 > /sys/devices/system/cpu/cpu3/cpufreq/walt/hispeed_load 2>/dev/null; " +
                "echo 40 > /sys/devices/system/cpu/cpu7/cpufreq/walt/hispeed_load 2>/dev/null; " +
                // 确保所有核心活跃
                "echo 4 > /sys/devices/system/cpu/cpu3/core_ctl/min_cpus 2>/dev/null; " +
                "echo 1 > /sys/devices/system/cpu/cpu7/core_ctl/min_cpus 2>/dev/null");
            
            // 先应用全局系统配置（优先级低）
            applySystemGlobalAffinity();
            
            // 每次重新获取pid（进程可能重启）
            int currentPid = ProcessHelper.getPidByPackage(packageName);
            if (currentPid <= 0) {
                Log.d(TAG, "Process not running, skipping affinity apply");
                return;
            }
            
            // 更新pid
            pid = currentPid;
            
            // 再应用APP配置（优先级高，可覆盖系统全局配置）
            // 统一使用 JNI 写入，掩码从十六进制字符串解析
            AppConfig config = configManager.loadConfig(packageName);
            if (config != null && config.getThreadAffinities() != null && !config.getThreadAffinities().isEmpty()) {
                // 使用awk一次性获取所有线程tid和名称，避免while read + cat导致大量fork
                String result = RootHelper.executeRootCommand(
                    "ls /proc/" + currentPid + "/task 2>/dev/null | awk -v pid=" + currentPid + " '{" +
                    "tid=$1; comm_file=\"/proc/\"pid\"/task/\"tid\"/comm\"; name=\"\"; " +
                    "if ((getline name < comm_file) > 0) { gsub(/[ \\t\\r\\n]/, \"\", name); } close(comm_file); " +
                    "print tid\":\"name; " +
                    "}'");
                
                if (result != null && !result.isEmpty()) {
                    int applied = 0;
                    
                    for (String line : result.trim().split("\n")) {
                        String[] parts = line.split(":", 2);
                        if (parts.length >= 2) {
                            try {
                                int tid = Integer.parseInt(parts[0].trim());
                                String name = parts[1].trim();
                                // 从配置获取掩码（十六进制字符串 -> long）
                                Long mask = config.getThreadAffinity(name);
                                if (mask != null) {
                                    Log.d(TAG, "APP: Calling JNI setThreadAffinity: tid=" + tid + " name=" + name + " mask=0x" + Long.toHexString(mask));
                                    boolean success = NativeHelper.setThreadAffinity(tid, mask);
                                    if (success) {
                                        applied++;
                                    }
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "APP affinity error: " + e.getMessage());
                            }
                        }
                    }
                    
                    if (applied > 0) {
                        Log.i(TAG, "APP affinity apply: " + applied + " threads for " + packageName);
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in background affinity apply: " + e.getMessage());
        }
    }
    
    /**
     * 应用全局系统线程亲和性配置
     * 统一使用 JNI 写入，掩码以十六进制格式处理
     * 扫描所有同名线程并写入亲和性
     */
    private void applySystemGlobalAffinity() {
        Log.d(TAG, "applySystemGlobalAffinity called");
        try {
            AppConfig sysConfig = configManager.loadConfig("_system_global_");
            if (sysConfig == null || sysConfig.getThreadAffinities() == null || sysConfig.getThreadAffinities().isEmpty()) {
                Log.d(TAG, "No system global config found");
                return;
            }
            
            // 获取配置的线程名和掩码（十六进制字符串 -> long）
            Map<String, String> sysAffinitiesHex = sysConfig.getThreadAffinities();
            Map<String, Long> sysAffinities = new HashMap<>();
            for (Map.Entry<String, String> entry : sysAffinitiesHex.entrySet()) {
                String threadName = entry.getKey();
                if (CONFIG_KEY_THIS_APP.equals(threadName)) continue;
                long mask = AppConfig.parseHexMask(entry.getValue());
                sysAffinities.put(threadName, mask);
            }
            
            int applied = 0;
            
            // 构建 awk 脚本，一次性查找所有配置的线程名
            // 使用 /proc/*/task/*/stat 读取，正确处理包含空格的线程名
            StringBuilder awkScript = new StringBuilder();
            awkScript.append("BEGIN { ");
            for (Map.Entry<String, Long> entry : sysAffinities.entrySet()) {
                String threadName = entry.getKey();
                if (CONFIG_KEY_THIS_APP.equals(threadName)) continue;
                long mask = entry.getValue();
                // 转义线程名中的特殊字符
                String escapedName = threadName.replace("\\", "\\\\").replace("\"", "\\\"");
                awkScript.append("masks[\"").append(escapedName).append("\"]=").append(mask).append("; ");
            }
            awkScript.append("} ");
            // 从 /proc/*/task/*/stat 读取，正确解析线程名
            // stat 格式: pid (comm) state ... 
            // 找到第一个 ( 和最后一个 )，中间就是 comm
            awkScript.append("{ ");
            awkScript.append("start=index($0, \"(\"); end=0; ");
            awkScript.append("for(i=length($0); i>0; i--) { if(substr($0,i,1)==\")\") { end=i; break; } } ");
            awkScript.append("if(start>0 && end>start) { ");
            awkScript.append("  comm=substr($0, start+1, end-start-1); ");
            awkScript.append("  tid=substr($0, 1, start-2); gsub(/[^0-9]/, \"\", tid); ");
            awkScript.append("  if(comm in masks) print tid, masks[comm]; ");
            awkScript.append("} }");
            
            // 遍历关键系统进程的线程
            // surfaceflinger, system_server, 以及本APP
            int myPid = android.os.Process.myPid();
            String sfPid = RootHelper.executeRootCommand("pidof surfaceflinger 2>/dev/null");
            String ssPid = RootHelper.executeRootCommand("pidof system_server 2>/dev/null");
            if (sfPid != null) sfPid = sfPid.trim();
            if (ssPid != null) ssPid = ssPid.trim();
            
            StringBuilder catCmd = new StringBuilder();
            if (sfPid != null && !sfPid.isEmpty()) {
                catCmd.append("cat /proc/").append(sfPid).append("/task/*/stat 2>/dev/null; ");
            }
            if (ssPid != null && !ssPid.isEmpty()) {
                catCmd.append("cat /proc/").append(ssPid).append("/task/*/stat 2>/dev/null | head -50; ");
            }
            catCmd.append("cat /proc/").append(myPid).append("/task/*/stat 2>/dev/null");
            
            String cmd = "(" + catCmd.toString() + ") | awk '" + awkScript.toString() + "'";
            String result = RootHelper.executeRootCommand(cmd);
            
            Log.d(TAG, "System affinity awk result: " + (result != null ? result.length() + " chars" : "null"));
            Log.d(TAG, "System affinity awk output: " + (result != null ? result.substring(0, Math.min(200, result.length())) : "null"));
            
            if (result != null && !result.trim().isEmpty()) {
                for (String line : result.trim().split("\n")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            int tid = Integer.parseInt(parts[0]);
                            long mask = Long.parseLong(parts[1]);
                            Log.i(TAG, "Calling JNI setThreadAffinity: tid=" + tid + " mask=0x" + Long.toHexString(mask));
                            // 使用 JNI 调用持久化 shell 设置亲和性
                            boolean success = NativeHelper.setThreadAffinity(tid, mask);
                            if (success) {
                                applied++;
                            }
                            Log.i(TAG, "JNI setThreadAffinity result: tid=" + tid + " -> " + (success ? "OK" : "FAIL"));
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to parse/apply: " + line + ", error: " + e.getMessage());
                        }
                    }
                }
            }
            
            if (applied > 0) {
                Log.i(TAG, "System global affinity applied: " + applied + " threads");
            } else {
                Log.d(TAG, "System global affinity: no threads matched");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying system global affinity: " + e.getMessage());
        }
    }

    private void updateStats() {
        try {
            // 获取CPU负载和频率（直接读取文件，不用root，很快）
            float[] cpuUsage = getCpuLoads();
            int[] cpuFreqs = getCpuFrequencies();
            
            if (cpuUsage == null) return;
            
            final float[] finalCpuUsage = cpuUsage;
            final int[] finalCpuFreqs = cpuFreqs;
            
            if (isMinimized) {
                // 最小化时只更新频率和负载柱状图
                mainHandler.post(() -> updateMinimizedUI(finalCpuUsage, finalCpuFreqs));
            } else {
                // 最大化时完整更新（包括线程列表）
                final List<ThreadInfo> finalThreads = new ArrayList<>(cachedThreads);
                mainHandler.post(() -> updateUI(finalCpuUsage, finalCpuFreqs, finalThreads));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
        }
    }
    
    /**
     * 最小化时的UI更新（频率和负载柱状图）
     */
    private void updateMinimizedUI(float[] cpuUsage, int[] cpuFreqs) {
        if (floatingView == null) return;
        
        float density = getResources().getDisplayMetrics().density;
        int maxBarHeightMini = (int) (14 * density);
        
        for (int i = 0; i < 8; i++) {
            int freq = cpuFreqs[i];
            float load = (cpuUsage != null && i + 1 < cpuUsage.length) ? cpuUsage[i + 1] : 0;
            
            float freqRatio = maxFreqs[i] > 0 ? (float) freq / maxFreqs[i] : 0;
            freqRatio = Math.min(freqRatio, 1.0f);
            int gradientColor = getGradientColor(freqRatio);
            
            float loadRatio = Math.max(load / 100f, 0.05f);
            loadRatio = Math.min(loadRatio, 1.0f);
            
            if (tvMiniCores[i] != null) {
                tvMiniCores[i].setText(String.valueOf(freq));
                tvMiniCores[i].setTextColor(gradientColor);
            }
            if (barMinis[i] != null) {
                int heightPx = (int) (maxBarHeightMini * loadRatio);
                ViewGroup.LayoutParams lp = barMinis[i].getLayoutParams();
                if (lp != null) {
                    lp.height = heightPx;
                    barMinis[i].setLayoutParams(lp);
                }
                barMinis[i].setBackgroundColor(gradientColor);
            }
        }
    }
    
    // 缓存的线程列表
    private volatile List<ThreadInfo> cachedThreads = new ArrayList<>();
    private volatile List<ThreadInfo> cachedSystemThreads = new ArrayList<>(); // 系统线程缓存
    private ScheduledExecutorService threadScheduler;
    private ScheduledExecutorService sysThreadScheduler; // 系统线程独立调度器
    
    // 视图复用缓存
    private List<View> appThreadRows = new ArrayList<>();
    private List<View> sysThreadRows = new ArrayList<>();
    
    /**
     * 异步更新线程列表（独立周期，不阻塞CPU更新）
     */
    private void updateThreadsAsync() {
        if (isMinimized || packageName == null) return;
        
        try {
            // 用 shell 获取 pid（JNI 没有权限读取其他进程）
            if (pid <= 0 || !ProcessHelper.isProcessRunning(pid)) {
                pid = ProcessHelper.getPidByPackage(packageName);
                Log.d(TAG, "updateThreadsAsync: shell got pid=" + pid + " for " + packageName);
            }
            
            if (pid > 0) {
                // 获取APP线程
                List<ThreadInfo> threads = getTopThreadsWithCpu(pid, 10);
                Log.d(TAG, "updateThreadsAsync: got " + (threads != null ? threads.size() : 0) + " threads for pid=" + pid);
                if (threads != null && !threads.isEmpty()) {
                    cachedThreads = threads;
                }
            } else {
                Log.w(TAG, "updateThreadsAsync: pid invalid for " + packageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating threads: " + e.getMessage());
        }
    }
    
    // 系统线程更新计数器
    private int sysThreadUpdateCounter = 0;
    
    /**
     * 异步更新系统线程列表（独立周期，3秒一次）
     */
    private void updateSystemThreadsAsync() {
        if (isMinimized) return;
        
        try {
            // 获取系统线程（排除当前监控的APP）
            int excludePid = pid > 0 ? pid : -1;
            List<ThreadInfo> sysThreads = getTopSystemThreads(excludePid, 10);
            // 只有获取到数据时才更新缓存，避免闪烁
            if (sysThreads != null && !sysThreads.isEmpty()) {
                cachedSystemThreads = sysThreads;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating system threads: " + e.getMessage());
        }
    }
    
    // CPU 时间缓存，用于计算 CPU 占用率
    private Map<Integer, Long> lastThreadCpuTimes = new HashMap<>();
    private long lastSampleTime = 0;
    
    /**
     * 获取系统其他进程的高CPU线程（排除指定APP）
     * 优化：使用 /proc 直接读取代替 top 命令，大幅降低 CPU 占用
     */
    private List<ThreadInfo> getTopSystemThreads(int excludePid, int limit) {
        Map<String, ThreadInfo> mergedThreads = new LinkedHashMap<>();
        
        try {
            int myPid = android.os.Process.myPid();
            long currentTime = System.currentTimeMillis();
            long timeDiff = lastSampleTime > 0 ? currentTime - lastSampleTime : 1000;
            if (timeDiff < 100) timeDiff = 100; // 最小 100ms
            
            // 需要整合为"本APP"的线程名
            Set<String> mergeToSelfNames = new HashSet<>();
            mergeToSelfNames.add("top");
            mergeToSelfNames.add("sh");
            mergeToSelfNames.add("awk");
            
            // 获取 surfaceflinger 和 system_server 的 PID
            String sfPid = RootHelper.executeRootCommand("pidof surfaceflinger 2>/dev/null | awk '{print $1}'");
            String ssPid = RootHelper.executeRootCommand("pidof system_server 2>/dev/null | awk '{print $1}'");
            if (sfPid != null) sfPid = sfPid.trim();
            if (ssPid != null) ssPid = ssPid.trim();
            
            // 使用 awk 一次性读取关键进程的线程 CPU 时间
            // 输出格式: tid|comm|cpu_time|core|tgid
            // 注意：comm 可能包含空格，需要先提取 (comm) 再处理后面的字段
            StringBuilder cmd = new StringBuilder();
            cmd.append("awk 'BEGIN { ");
            
            // 采样 surfaceflinger
            if (sfPid != null && !sfPid.isEmpty()) {
                cmd.append("cmd=\"ls /proc/").append(sfPid).append("/task 2>/dev/null\"; ");
                cmd.append("while ((cmd | getline tid) > 0) { ");
                cmd.append("  stat_file=\"/proc/").append(sfPid).append("/task/\"tid\"/stat\"; ");
                cmd.append("  if ((getline line < stat_file) > 0) { ");
                // 提取 (comm) - 找到第一个 ( 和最后一个 )
                cmd.append("    start=index(line, \"(\"); end=0; ");
                cmd.append("    for(i=length(line); i>0; i--) { if(substr(line,i,1)==\")\") { end=i; break; } } ");
                cmd.append("    comm=substr(line, start+1, end-start-1); ");
                cmd.append("    rest=substr(line, end+2); n=split(rest, f, \" \"); ");
                cmd.append("    print tid\"|\"comm\"|\"(f[12]+f[13])\"|\"f[37]\"|").append(sfPid).append("\"; ");
                cmd.append("  } close(stat_file); ");
                cmd.append("} close(cmd); ");
            }
            
            // 采样 system_server (只取前 20 个线程)
            if (ssPid != null && !ssPid.isEmpty()) {
                cmd.append("cmd=\"ls /proc/").append(ssPid).append("/task 2>/dev/null | head -20\"; ");
                cmd.append("while ((cmd | getline tid) > 0) { ");
                cmd.append("  stat_file=\"/proc/").append(ssPid).append("/task/\"tid\"/stat\"; ");
                cmd.append("  if ((getline line < stat_file) > 0) { ");
                cmd.append("    start=index(line, \"(\"); end=0; ");
                cmd.append("    for(i=length(line); i>0; i--) { if(substr(line,i,1)==\")\") { end=i; break; } } ");
                cmd.append("    comm=substr(line, start+1, end-start-1); ");
                cmd.append("    rest=substr(line, end+2); n=split(rest, f, \" \"); ");
                cmd.append("    print tid\"|\"comm\"|\"(f[12]+f[13])\"|\"f[37]\"|").append(ssPid).append("\"; ");
                cmd.append("  } close(stat_file); ");
                cmd.append("} close(cmd); ");
            }
            
            // 采样本 APP
            cmd.append("cmd=\"ls /proc/").append(myPid).append("/task 2>/dev/null\"; ");
            cmd.append("while ((cmd | getline tid) > 0) { ");
            cmd.append("  stat_file=\"/proc/").append(myPid).append("/task/\"tid\"/stat\"; ");
            cmd.append("  if ((getline line < stat_file) > 0) { ");
            cmd.append("    start=index(line, \"(\"); end=0; ");
            cmd.append("    for(i=length(line); i>0; i--) { if(substr(line,i,1)==\")\") { end=i; break; } } ");
            cmd.append("    comm=substr(line, start+1, end-start-1); ");
            cmd.append("    rest=substr(line, end+2); n=split(rest, f, \" \"); ");
            cmd.append("    print tid\"|\"comm\"|\"(f[12]+f[13])\"|\"f[37]\"|").append(myPid).append("\"; ");
            cmd.append("  } close(stat_file); ");
            cmd.append("} close(cmd); ");
            
            cmd.append("}'");
            
            String result = RootHelper.executeRootCommand(cmd.toString());
            
            if (result == null || result.trim().isEmpty()) {
                return new ArrayList<>();
            }
            
            Map<Integer, Long> currentCpuTimes = new HashMap<>();
            
            for (String line : result.trim().split("\n")) {
                try {
                    String[] parts = line.split("\\|");
                    if (parts.length < 5) continue;
                    
                    int tid = Integer.parseInt(parts[0].trim());
                    String threadName = parts[1].trim();
                    long cpuTime = Long.parseLong(parts[2].trim());
                    int runningCpu = !parts[3].trim().isEmpty() ? Integer.parseInt(parts[3].trim()) : -1;
                    int threadPid = Integer.parseInt(parts[4].trim());
                    
                    currentCpuTimes.put(tid, cpuTime);
                    
                    // 排除监控的目标APP
                    if (threadPid == excludePid) continue;
                    
                    // 计算 CPU 占用率
                    float cpuUsage = 0;
                    if (lastThreadCpuTimes.containsKey(tid)) {
                        long lastTime = lastThreadCpuTimes.get(tid);
                        long diff = cpuTime - lastTime;
                        // CPU% = (diff_ticks / time_ms) * 1000 / CLK_TCK * 100
                        cpuUsage = diff * 100000f / (timeDiff * 100);
                    }
                    
                    if (cpuUsage < 0.5f && lastSampleTime > 0) continue;
                    
                    // 判断是否需要整合为"本APP"
                    boolean isSelf = (threadPid == myPid) || 
                                     mergeToSelfNames.contains(threadName) ||
                                     threadName.contains("ffinity");
                    
                    String thisAppLabel = localizedContext.getString(R.string.this_app);
                    String displayName = isSelf ? thisAppLabel : threadName;
                    
                    if (mergedThreads.containsKey(displayName)) {
                        ThreadInfo existing = mergedThreads.get(displayName);
                        existing.setCpuUsage(existing.getCpuUsage() + cpuUsage);
                        existing.setSameNameCount(existing.getSameNameCount() + 1);
                        if (runningCpu >= 0) existing.setRunningCpu(runningCpu);
                    } else {
                        ThreadInfo info = new ThreadInfo(tid, displayName);
                        info.setCpuUsage(cpuUsage);
                        info.setRunningCpu(runningCpu);
                        info.setSameNameCount(1);
                        mergedThreads.put(displayName, info);
                    }
                } catch (Exception e) {}
            }
            
            // 更新缓存
            lastThreadCpuTimes = currentCpuTimes;
            lastSampleTime = currentTime;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to get system threads: " + e.getMessage());
        }
        
        List<ThreadInfo> sortedList = new ArrayList<>(mergedThreads.values());
        sortedList.sort((a, b) -> Float.compare(b.getCpuUsage(), a.getCpuUsage()));
        return sortedList.size() > limit ? sortedList.subList(0, limit) : sortedList;
    }
    
    /**
     * 获取线程所属进程PID
     */
    private int getThreadPid(int tid) {
        try {
            String content = readProcFile("/proc/" + tid + "/status");
            if (content != null) {
                for (String line : content.split("\n")) {
                    if (line.startsWith("Tgid:")) {
                        return Integer.parseInt(line.substring(5).trim());
                    }
                }
            }
        } catch (Exception e) {}
        return -1;
    }
    
    /**
     * 快速获取线程运行核心
     */
    private int getThreadRunningCpuFast(int tid) {
        try {
            String stat = readProcFile("/proc/" + tid + "/stat");
            if (stat != null) {
                // stat格式: pid (comm) state ... 第39个字段是processor
                String[] parts = stat.split("\\s+");
                if (parts.length > 38) {
                    return Integer.parseInt(parts[38]);
                }
            }
        } catch (Exception e) {}
        return -1;
    }
    
    /**
     * 用root读取proc文件
     */
    private String readProcFile(String path) {
        return RootHelper.executeRootCommand("cat " + path + " 2>/dev/null");
    }
    
    private String getThreadName(int tid) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + tid + "/comm"));
            String name = reader.readLine();
            reader.close();
            return name != null ? name.trim() : null;
        } catch (Exception e) {}
        return null;
    }
    
    private int getThreadRunningCpu(int tid) {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader("/proc/" + tid + "/stat"));
            String line = reader.readLine();
            reader.close();
            if (line != null) {
                // 跳过前面的字段，第39个字段是processor
                String[] parts = line.split("\\s+");
                if (parts.length > 38) {
                    return Integer.parseInt(parts[38]);
                }
            }
        } catch (Exception e) {}
        return -1;
    }
    
    // APP 线程 CPU 时间缓存
    private Map<Integer, Long> lastAppThreadCpuTimes = new HashMap<>();
    private long lastAppSampleTime = 0;
    
    /**
     * 获取进程的线程列表及CPU占用，合并同名线程
     * 优化：使用 /proc 直接读取代替 top 命令，大幅降低 CPU 占用
     */
    private List<ThreadInfo> getTopThreadsWithCpu(int pid, int limit) {
        Map<String, ThreadInfo> mergedThreads = new LinkedHashMap<>();
        
        try {
            long currentTime = System.currentTimeMillis();
            long timeDiff = lastAppSampleTime > 0 ? currentTime - lastAppSampleTime : 1000;
            if (timeDiff < 100) timeDiff = 100;
            
            // 使用 awk 一次性读取所有线程的 CPU 时间
            // 输出格式: tid|comm|cpu_time|core
            // 注意：comm 可能包含空格（如 "Job.worker 1"），需要先提取 (comm) 再处理后面的字段
            String result = RootHelper.executeRootCommand(
                "ls /proc/" + pid + "/task 2>/dev/null | awk -v pid=" + pid + " '{" +
                "tid=$1; stat_file=\"/proc/\"pid\"/task/\"tid\"/stat\"; " +
                "if ((getline line < stat_file) > 0) { " +
                // 提取 (comm) - 找到第一个 ( 和最后一个 )
                "  start=index(line, \"(\"); end=0; " +
                "  for(i=length(line); i>0; i--) { if(substr(line,i,1)==\")\") { end=i; break; } } " +
                "  comm=substr(line, start+1, end-start-1); " +
                // 从 ) 之后的部分分割字段
                "  rest=substr(line, end+2); " +
                "  n=split(rest, f, \" \"); " +
                // f[1]=state, f[12]=utime, f[13]=stime, f[37]=processor (从 ) 后算起)
                "  print tid\"|\"comm\"|\"(f[12]+f[13])\"|\"f[37]; " +
                "} close(stat_file); " +
                "}'");
            
            if (result == null || result.trim().isEmpty()) {
                return new ArrayList<>();
            }
            
            Map<Integer, Long> currentCpuTimes = new HashMap<>();
            
            for (String line : result.trim().split("\n")) {
                String[] parts = line.split("\\|");
                if (parts.length >= 4) {
                    try {
                        int tid = Integer.parseInt(parts[0].trim());
                        String name = parts[1].trim();
                        long cpuTime = Long.parseLong(parts[2].trim());
                        int runningCpu = !parts[3].trim().isEmpty() ? Integer.parseInt(parts[3].trim()) : -1;
                        
                        if (name.isEmpty()) continue;
                        
                        currentCpuTimes.put(tid, cpuTime);
                        
                        // 计算 CPU 占用率
                        float cpuUsage = 0;
                        if (lastAppThreadCpuTimes.containsKey(tid)) {
                            long lastTime = lastAppThreadCpuTimes.get(tid);
                            long diff = cpuTime - lastTime;
                            cpuUsage = diff * 100000f / (timeDiff * 100);
                        }
                        
                        // 合并同名线程
                        if (mergedThreads.containsKey(name)) {
                            ThreadInfo existing = mergedThreads.get(name);
                            existing.setCpuUsage(existing.getCpuUsage() + cpuUsage);
                            existing.setSameNameCount(existing.getSameNameCount() + 1);
                            if (cpuUsage > 0 && runningCpu >= 0) {
                                existing.setRunningCpu(runningCpu);
                            }
                        } else {
                            ThreadInfo info = new ThreadInfo(tid, name);
                            info.setCpuUsage(cpuUsage);
                            info.setRunningCpu(runningCpu);
                            info.setSameNameCount(1);
                            mergedThreads.put(name, info);
                        }
                    } catch (Exception e) {}
                }
            }
            
            // 更新缓存
            lastAppThreadCpuTimes = currentCpuTimes;
            lastAppSampleTime = currentTime;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to get threads: " + e.getMessage());
        }
        
        // 按CPU占用率排序，取前limit个
        List<ThreadInfo> sortedList = new ArrayList<>(mergedThreads.values());
        sortedList.sort((a, b) -> Float.compare(b.getCpuUsage(), a.getCpuUsage()));
        
        if (sortedList.size() > limit) {
            return sortedList.subList(0, limit);
        }
        return sortedList;
    }

    private int[] getCpuFrequencies() {
        int[] freqs = new int[8];
        // 直接用Java读取（APP有权限读取这个文件）
        for (int i = 0; i < 8; i++) {
            try {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq"));
                String line = reader.readLine();
                reader.close();
                if (line != null) {
                    freqs[i] = Integer.parseInt(line.trim()) / 1000;
                }
            } catch (Exception e) {
                // 核心可能离线，忽略
            }
        }
        return freqs;
    }
    
    /**
     * 从 SurfaceFlinger --list 输出行中提取 Layer 名称
     * 自适应两种格式：
     * - KernelSU Next: RequestedLayerState{LayerName#ID parentId=...} -> LayerName#ID
     * - Magisk: LayerName -> LayerName
     */
    private String extractLayerName(String line) {
        int start = line.indexOf('{');
        if (start < 0) return line.trim(); // Magisk 格式，直接返回
        
        // KernelSU Next 格式，提取 {} 内第一个空格前的内容
        int end = line.indexOf(' ', start);
        if (end < 0) end = line.indexOf('}', start);
        if (end < 0) return line.trim();
        
        return line.substring(start + 1, end).trim();
    }
    
    /**
     * 获取当前实时帧率
     * 通过 SurfaceFlinger 获取目标应用 SurfaceView 的帧时间戳
     */
    private int getCurrentFps() {
        try {
            String layerName = null;
            
            // 获取目标应用的 SurfaceView Layer
            if (packageName != null && !packageName.isEmpty()) {
                // 先获取所有 Layer 列表
                String result = RootHelper.executeRootCommand(
                    "dumpsys SurfaceFlinger --list 2>/dev/null");
                
                if (result != null && !result.trim().isEmpty()) {
                    String[] layers = result.split("\n");
                    // 优先找 BLAST SurfaceView
                    for (String layer : layers) {
                        if (layer.contains(packageName) && layer.contains("BLAST")) {
                            layerName = extractLayerName(layer);
                            break;
                        }
                    }
                    // 其次找普通 SurfaceView
                    if (layerName == null) {
                        for (String layer : layers) {
                            if (layer.contains(packageName) && layer.contains("SurfaceView[")) {
                                layerName = extractLayerName(layer);
                                break;
                            }
                        }
                    }
                    // 最后找主窗口
                    if (layerName == null) {
                        for (String layer : layers) {
                            if (layer.contains(packageName) && !layer.contains("Background") 
                                && !layer.contains("Bounds") && !layer.contains("Task")
                                && !layer.contains("ActivityRecord")) {
                                layerName = extractLayerName(layer);
                                break;
                            }
                        }
                    }
                }
            }
            
            if (layerName != null) {
                // 获取该 Layer 的帧时间戳
                String result = RootHelper.executeRootCommand(
                    "dumpsys SurfaceFlinger --latency '" + layerName + "' 2>/dev/null");
                
                if (result != null && !result.trim().isEmpty()) {
                    String[] lines = result.trim().split("\n");
                    
                    // 收集有效的帧时间戳（第一列是 desiredPresentTime）
                    List<Long> timestamps = new ArrayList<>();
                    
                    for (int i = 1; i < lines.length; i++) { // 跳过第一行（刷新周期）
                        String[] parts = lines[i].trim().split("\\s+");
                        if (parts.length >= 1) {
                            try {
                                long timestamp = Long.parseLong(parts[0]);
                                // 过滤无效值
                                if (timestamp > 0 && timestamp < 9000000000000000000L) {
                                    timestamps.add(timestamp);
                                }
                            } catch (NumberFormatException e) {}
                        }
                    }
                    
                    if (timestamps.size() >= 10) {
                        // 找到最新的连续帧序列
                        long maxGap = 100000000L; // 100ms
                        
                        int continuousCount = 1;
                        long startTime = timestamps.get(timestamps.size() - 1);
                        long endTime = startTime;
                        
                        for (int i = timestamps.size() - 2; i >= 0; i--) {
                            long gap = timestamps.get(i + 1) - timestamps.get(i);
                            if (gap > 0 && gap < maxGap) {
                                continuousCount++;
                                startTime = timestamps.get(i);
                            } else {
                                break;
                            }
                        }
                        
                        if (continuousCount >= 5) {
                            long duration = endTime - startTime;
                            if (duration > 0) {
                                float fps = (continuousCount - 1) * 1000000000.0f / duration;
                                if (fps > 0 && fps <= 240) {
                                    currentFps = Math.round(fps);
                                    return currentFps;
                                }
                            }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting FPS: " + e.getMessage());
        }
        
        return currentFps > 0 ? currentFps : 0;
    }
    
    /**
     * 从 /proc/stat 读取CPU负载
     * @return 负载数组，index 0是总体，1-8是各核心；如果是刚重置返回null
     */
    private float[] getCpuLoads() {
        float[] loads = new float[9];
        boolean wasInitialized = cpuTimesInitialized;
        try {
            // 用root命令读取（使用head更快）
            String result = RootHelper.executeRootCommand("head -9 /proc/stat");
            if (result == null || result.isEmpty()) return null;
            
            String[] lines = result.split("\n");
            int cpuIndex = 0;
            
            for (String line : lines) {
                if (!line.startsWith("cpu")) break;
                
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 5) continue;
                
                // cpu user nice system idle iowait irq softirq steal
                long user = Long.parseLong(parts[1]);
                long nice = Long.parseLong(parts[2]);
                long system = Long.parseLong(parts[3]);
                long idle = Long.parseLong(parts[4]);
                long iowait = parts.length > 5 ? Long.parseLong(parts[5]) : 0;
                long irq = parts.length > 6 ? Long.parseLong(parts[6]) : 0;
                long softirq = parts.length > 7 ? Long.parseLong(parts[7]) : 0;
                long steal = parts.length > 8 ? Long.parseLong(parts[8]) : 0;
                
                long totalIdle = idle + iowait;
                long total = user + nice + system + idle + iowait + irq + softirq + steal;
                
                if (cpuTimesInitialized && cpuIndex < 9) {
                    long idleDiff = totalIdle - lastCpuTimes[cpuIndex][0];
                    long totalDiff = total - lastCpuTimes[cpuIndex][1];
                    
                    if (totalDiff > 0) {
                        loads[cpuIndex] = 100.0f * (1.0f - (float) idleDiff / totalDiff);
                        loads[cpuIndex] = Math.max(0, Math.min(100, loads[cpuIndex]));
                    }
                }
                
                if (cpuIndex < 9) {
                    lastCpuTimes[cpuIndex][0] = totalIdle;
                    lastCpuTimes[cpuIndex][1] = total;
                }
                
                cpuIndex++;
                if (cpuIndex > 8) break;
            }
            
            cpuTimesInitialized = true;
        } catch (Exception e) {
            Log.e(TAG, "Error reading CPU loads: " + e.getMessage());
        }
        // 如果之前未初始化，这次只是建立基准，返回null跳过UI更新
        if (!wasInitialized) return null;
        return loads;
    }

    private void updateUI(float[] cpuUsage, int[] cpuFreqs, List<ThreadInfo> threads) {
        if (floatingView == null) return;
        
        // 总CPU使用率
        if (cpuUsage != null && cpuUsage.length > 0) {
            float total = cpuUsage[0];
            tvCpuTotal.setText(String.format(Locale.US, "%.0f%%", total));
            tvCpuTotal.setTextColor(getGradientColor(total / 100f));
        }
        
        float density = getResources().getDisplayMetrics().density;
        int maxBarHeightMini = (int) (14 * density);  // 最小化视图柱状图高度14dp
        int maxBarHeightMax = (int) (28 * density);   // 最大化视图柱状图高度28dp
        
        // 更新8核心
        for (int i = 0; i < 8; i++) {
            int freq = cpuFreqs[i];
            // cpuUsage[0]是总CPU，cpuUsage[1]是cpu0，以此类推
            float load = (cpuUsage != null && i + 1 < cpuUsage.length) ? cpuUsage[i + 1] : 0;
            
            // 计算频率占比 (0.0 ~ 1.0)，用于颜色渐变
            float freqRatio = maxFreqs[i] > 0 ? (float) freq / maxFreqs[i] : 0;
            freqRatio = Math.min(freqRatio, 1.0f);
            
            // 获取渐变颜色：绿色(低) → 黄色(中) → 红色(高)
            int gradientColor = getGradientColor(freqRatio);
            
            // 负载比例 (用于柱状图高度)
            float loadRatio = Math.max(load / 100f, 0.05f);  // 最小5%
            loadRatio = Math.min(loadRatio, 1.0f);
            
            // === 最小化视图 ===
            if (tvMiniCores[i] != null) {
                tvMiniCores[i].setText(String.valueOf(freq));
                tvMiniCores[i].setTextColor(gradientColor);
            }
            // 最小化视图柱状图 - 按负载填充
            if (barMinis[i] != null) {
                int heightPx = (int) (maxBarHeightMini * loadRatio);
                ViewGroup.LayoutParams lp = barMinis[i].getLayoutParams();
                if (lp != null) {
                    lp.height = heightPx;
                    barMinis[i].setLayoutParams(lp);
                }
                barMinis[i].setBackgroundColor(gradientColor);
            }
            
            // === 最大化视图 ===
            if (tvFreqs[i] != null) {
                tvFreqs[i].setText(String.valueOf(freq));
                tvFreqs[i].setTextColor(gradientColor);
            }
            // 最大化视图柱状图 - 按负载填充
            if (barCores[i] != null) {
                int heightPx = (int) (maxBarHeightMax * loadRatio);
                ViewGroup.LayoutParams lp = barCores[i].getLayoutParams();
                if (lp != null) {
                    lp.height = heightPx;
                    barCores[i].setLayoutParams(lp);
                }
                barCores[i].setBackgroundColor(gradientColor);
            }
        }
        
        // APP线程列表 - 复用视图避免频繁创建
        if (!isMinimized && layoutThreads != null) {
            // 加载APP配置
            AppConfig appConfig = configManager.loadConfig(packageName);
            
            int threadCount = threads.size();
            int existingRows = appThreadRows.size();
            
            // 确保有足够的行
            while (appThreadRows.size() < threadCount) {
                LinearLayout row = createThreadRow(false);
                appThreadRows.add(row);
                layoutThreads.addView(row);
            }
            
            // 更新每一行的数据
            for (int i = 0; i < threadCount; i++) {
                ThreadInfo thread = threads.get(i);
                View row = appThreadRows.get(i);
                row.setVisibility(View.VISIBLE);
                updateThreadRow(row, thread, appConfig, false);
            }
            
            // 隐藏多余的行
            for (int i = threadCount; i < appThreadRows.size(); i++) {
                appThreadRows.get(i).setVisibility(View.GONE);
            }
        }
        
        // 系统线程列表 - 复用视图避免频繁创建
        if (!isMinimized && layoutSystemThreads != null) {
            List<ThreadInfo> sysThreads = cachedSystemThreads;
            
            // 加载全局系统配置
            AppConfig sysConfig = configManager.loadConfig("_system_global_");
            
            int threadCount = sysThreads.size();
            
            // 确保有足够的行
            while (sysThreadRows.size() < threadCount) {
                LinearLayout row = createThreadRow(true);
                sysThreadRows.add(row);
                layoutSystemThreads.addView(row);
            }
            
            // 更新每一行的数据
            for (int i = 0; i < threadCount; i++) {
                ThreadInfo thread = sysThreads.get(i);
                View row = sysThreadRows.get(i);
                row.setVisibility(View.VISIBLE);
                updateThreadRow(row, thread, sysConfig, true);
            }
            
            // 隐藏多余的行
            for (int i = threadCount; i < sysThreadRows.size(); i++) {
                sysThreadRows.get(i).setVisibility(View.GONE);
            }
        }
    }
    
    /**
     * 创建线程行视图（复用）
     */
    private LinearLayout createThreadRow(boolean isSystem) {
        float density = getResources().getDisplayMetrics().density;
        
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 2, 0, 2);
        row.setBackgroundColor(0x00000000);
        row.setTag(R.id.tvTitle, "row"); // 标记为行
        
        // 线程名
        TextView tvName = new TextView(this);
        tvName.setTextColor(isSystem ? 0xFFFF9800 : 0xFFAAAAAA);
        tvName.setTextSize(8);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvName.setSingleLine(true);
        tvName.setTag("name");
        
        // 配置的亲和性
        TextView tvAffinity = new TextView(this);
        tvAffinity.setTextSize(7);
        tvAffinity.setWidth((int)(22 * density));
        tvAffinity.setTag("affinity");
        
        // 运行核心
        TextView tvCore = new TextView(this);
        tvCore.setTextSize(8);
        tvCore.setWidth((int)(16 * density));
        tvCore.setTag("core");
        
        // CPU占用率
        TextView tvUsage = new TextView(this);
        tvUsage.setTextSize(8);
        tvUsage.setWidth((int)(28 * density));
        tvUsage.setGravity(android.view.Gravity.END);
        tvUsage.setTag("usage");
        
        row.addView(tvName);
        row.addView(tvAffinity);
        row.addView(tvCore);
        row.addView(tvUsage);
        
        return row;
    }
    
    /**
     * 更新线程行数据（复用视图）
     * @param config 配置对象，可以为 null
     */
    private void updateThreadRow(View row, ThreadInfo thread, AppConfig config, boolean isSystem) {
        LinearLayout layout = (LinearLayout) row;
        
        TextView tvName = (TextView) layout.getChildAt(0);
        TextView tvAffinity = (TextView) layout.getChildAt(1);
        TextView tvCore = (TextView) layout.getChildAt(2);
        TextView tvUsage = (TextView) layout.getChildAt(3);
        
        // 更新线程名
        String displayName = thread.getName();
        int count = thread.getSameNameCount();
        if (count > 1) {
            displayName += "(" + count + ")";
        }
        tvName.setText(displayName);
        
        // 更新亲和性（从配置获取十六进制字符串）
        Long configMask = (config != null) ? config.getThreadAffinity(thread.getName()) : null;
        if (configMask != null) {
            tvAffinity.setTextColor(0xFF4CAF50);
            tvAffinity.setText(maskToShortString(configMask));
        } else {
            tvAffinity.setTextColor(0xFF555555);
            tvAffinity.setText(localizedContext.getString(R.string.none));
        }
        
        // 更新运行核心
        int cpu = thread.getRunningCpu();
        if (cpu >= 0) {
            tvCore.setTextColor(cpu < 3 ? 0xFF7BA3B5 : (cpu < 7 ? 0xFFD4B445 : 0xFFC45C5C));
            tvCore.setText("@" + cpu);
        } else {
            tvCore.setTextColor(0xFF666666);
            tvCore.setText("");
        }
        
        // 更新CPU占用率
        float usage = thread.getCpuUsage();
        tvUsage.setTextColor(getGradientColor(Math.min(usage / 50f, 1f)));
        tvUsage.setText(String.format(Locale.US, "%.1f%%", usage));
        
        // 更新点击事件
        final String threadName = thread.getName();
        if (isSystem) {
            row.setOnClickListener(v -> showSystemCpuSelector(threadName));
        } else {
            row.setOnClickListener(v -> showCpuSelector(threadName));
        }
    }
    
    /**
     * 将掩码转换为简短字符串
     */
    private String maskToShortString(long mask) {
        // 使用动态 CPU 配置
        String lang = LocaleHelper.getLanguage(this);
        boolean isChinese = LocaleHelper.LANG_CHINESE.equals(lang) || 
            (LocaleHelper.LANG_SYSTEM.equals(lang) && java.util.Locale.getDefault().getLanguage().equals("zh"));
        return CpuInfo.getInstance().maskToShortString(mask, isChinese);
    }
    
    /**
     * 显示CPU核心选择面板
     */
    private void showCpuSelector(String threadName) {
        // 加载当前配置
        AppConfig config = configManager.loadConfig(packageName);
        if (config == null) {
            config = new AppConfig(packageName, packageName);
        }
        final AppConfig finalConfig = config;
        
        // 获取当前线程的亲和性掩码
        Long currentMask = config.getThreadAffinity(threadName);
        if (currentMask == null) currentMask = 0xFFL; // 默认全部核心
        final long mask = currentMask;
        
        // 创建选择面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xE0202020);
        panel.setPadding(16, 12, 16, 12);
        
        // 标题
        TextView title = new TextView(this);
        title.setText(threadName);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(11);
        title.setGravity(android.view.Gravity.CENTER);
        panel.addView(title);
        
        // CPU核心选择行
        LinearLayout coresRow = new LinearLayout(this);
        coresRow.setOrientation(LinearLayout.HORIZONTAL);
        coresRow.setGravity(android.view.Gravity.CENTER);
        coresRow.setPadding(0, 10, 0, 10);
        
        final TextView[] coreButtons = new TextView[8];
        final boolean[] selected = new boolean[8];
        
        // 初始化选中状态
        for (int i = 0; i < 8; i++) {
            selected[i] = (mask & (1L << i)) != 0;
        }
        
        float density = getResources().getDisplayMetrics().density;
        int btnSize = (int)(26 * density);
        int margin = (int)(3 * density);
        
        for (int i = 0; i < 8; i++) {
            final int coreIndex = i;
            TextView btn = new TextView(this);
            btn.setText(String.valueOf(i));
            btn.setTextSize(11);
            btn.setGravity(android.view.Gravity.CENTER);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnSize, btnSize);
            lp.setMargins(margin, 0, margin, 0);
            btn.setLayoutParams(lp);
            
            // 根据核心类型设置颜色
            int coreColor = i < 3 ? 0xFF7BA3B5 : (i < 7 ? 0xFFD4B445 : 0xFFC45C5C);
            
            if (selected[i]) {
                btn.setBackgroundColor(coreColor);
                btn.setTextColor(0xFF000000);
            } else {
                btn.setBackgroundColor(0xFF333333);
                btn.setTextColor(0xFF666666);
            }
            
            btn.setOnClickListener(v -> {
                selected[coreIndex] = !selected[coreIndex];
                if (selected[coreIndex]) {
                    btn.setBackgroundColor(coreIndex < 3 ? 0xFF7BA3B5 : (coreIndex < 7 ? 0xFFD4B445 : 0xFFC45C5C));
                    btn.setTextColor(0xFF000000);
                } else {
                    btn.setBackgroundColor(0xFF333333);
                    btn.setTextColor(0xFF666666);
                }
            });
            
            coreButtons[i] = btn;
            coresRow.addView(btn);
        }
        panel.addView(coresRow);
        
        // 快捷按钮行
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(android.view.Gravity.CENTER);
        quickRow.setPadding(0, 4, 0, 8);
        
        // 使用动态 CPU 配置
        CpuInfo cpuInfo = CpuInfo.getInstance();
        String lang = LocaleHelper.getLanguage(this);
        boolean isChinese = LocaleHelper.LANG_CHINESE.equals(lang) || 
            (LocaleHelper.LANG_SYSTEM.equals(lang) && java.util.Locale.getDefault().getLanguage().equals("zh"));
        String[] quickLabels = isChinese ? cpuInfo.getQuickLabelsZh() : cpuInfo.getQuickLabelsEn();
        long[] quickMasks = cpuInfo.getQuickMasks();
        
        for (int q = 0; q < quickLabels.length; q++) {
            final long qMask = quickMasks[q];
            TextView qBtn = new TextView(this);
            qBtn.setText(quickLabels[q]);
            qBtn.setTextSize(10);
            qBtn.setTextColor(0xFFAAAAAA);
            qBtn.setBackgroundColor(0xFF333333);
            qBtn.setGravity(android.view.Gravity.CENTER);
            qBtn.setPadding((int)(12*density), (int)(4*density), (int)(12*density), (int)(4*density));
            
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qlp.setMargins((int)(4*density), 0, (int)(4*density), 0);
            qBtn.setLayoutParams(qlp);
            
            final int cpuCountFinal = cpuInfo.getCpuCount();
            qBtn.setOnClickListener(v -> {
                for (int i = 0; i < cpuCountFinal && i < 8; i++) {
                    selected[i] = (qMask & (1L << i)) != 0;
                    int coreColor = cpuInfo.getColorForCore(i);
                    if (selected[i]) {
                        coreButtons[i].setBackgroundColor(coreColor);
                        coreButtons[i].setTextColor(0xFF000000);
                    } else {
                        coreButtons[i].setBackgroundColor(0xFF333333);
                        coreButtons[i].setTextColor(0xFF666666);
                    }
                }
            });
            quickRow.addView(qBtn);
        }
        panel.addView(quickRow);
        
        // 确定/取消按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER);
        
        TextView btnCancel = new TextView(this);
        btnCancel.setText(localizedContext.getString(R.string.cancel));
        btnCancel.setTextSize(11);
        btnCancel.setTextColor(0xFF888888);
        btnCancel.setPadding((int)(20*density), (int)(6*density), (int)(20*density), (int)(6*density));
        
        TextView btnOk = new TextView(this);
        btnOk.setText(localizedContext.getString(R.string.apply));
        btnOk.setTextSize(11);
        btnOk.setTextColor(0xFF4CAF50);
        btnOk.setPadding((int)(20*density), (int)(6*density), (int)(20*density), (int)(6*density));
        
        btnRow.addView(btnCancel);
        btnRow.addView(btnOk);
        panel.addView(btnRow);
        
        // 创建悬浮窗显示选择面板
        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.CENTER;
        
        windowManager.addView(panel, panelParams);
        
        btnCancel.setOnClickListener(v -> windowManager.removeView(panel));
        
        btnOk.setOnClickListener(v -> {
            // 计算新掩码
            long newMask = 0;
            for (int i = 0; i < 8; i++) {
                if (selected[i]) newMask |= (1L << i);
            }
            if (newMask == 0) newMask = 0xFFL; // 至少选一个
            
            // 先更新点击的线程
            finalConfig.addThreadAffinity(threadName, newMask);
            
            // 获取当前app所有线程名，未配置的添加默认0xFF
            final long maskToApply = newMask;
            new Thread(() -> {
                try {
                    // 使用awk一次性获取所有线程名，避免while read + cat导致大量fork
                    String allThreadsResult = RootHelper.executeRootCommand(
                        "ls /proc/" + pid + "/task 2>/dev/null | awk -v pid=" + pid + " '{" +
                        "tid=$1; comm_file=\"/proc/\"pid\"/task/\"tid\"/comm\"; name=\"\"; " +
                        "if ((getline name < comm_file) > 0) { gsub(/[ \\t\\r\\n]/, \"\", name); print name; } close(comm_file); " +
                        "}' | sort -u");
                    
                    if (allThreadsResult != null && !allThreadsResult.isEmpty()) {
                        for (String name : allThreadsResult.trim().split("\n")) {
                            name = name.trim();
                            if (!name.isEmpty() && finalConfig.getThreadAffinity(name) == null) {
                                // 未配置的线程添加默认中核3,4,5 (0x38 = 0b00111000)
                                finalConfig.addThreadAffinity(name, 0x38L);
                            }
                        }
                    }
                    
                    // 保存配置
                    finalConfig.setTimestamp(System.currentTimeMillis());
                    configManager.saveConfig(finalConfig);
                    Log.i(TAG, "Saved config with all threads, updated: " + threadName + " -> 0x" + Long.toHexString(maskToApply));
                    
                    // 使用awk一次性查找指定线程名的tid，避免while read + cat
                    String result = RootHelper.executeRootCommand(
                        "ls /proc/" + pid + "/task 2>/dev/null | awk -v pid=" + pid + " -v tname=\"" + threadName + "\" '{" +
                        "tid=$1; comm_file=\"/proc/\"pid\"/task/\"tid\"/comm\"; name=\"\"; " +
                        "if ((getline name < comm_file) > 0) { gsub(/[ \\t\\r\\n]/, \"\", name); if (name == tname) print tid; } close(comm_file); " +
                        "}'");
                    if (result != null && !result.isEmpty()) {
                        for (String tidStr : result.trim().split("\n")) {
                            try {
                                int tid = Integer.parseInt(tidStr.trim());
                                NativeHelper.setThreadAffinity(tid, maskToApply);
                            } catch (Exception e) {}
                        }
                    }
                    Log.i(TAG, "Immediately applied affinity for " + threadName);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to save/apply: " + e.getMessage());
                }
            }).start();
            
            windowManager.removeView(panel);
        });
    }
    
    /**
     * 显示系统线程的CPU核心选择面板（保存到全局配置）
     */
    private void showSystemCpuSelector(String threadName) {
        // 加载全局系统配置
        AppConfig config = configManager.loadConfig("_system_global_");
        if (config == null) {
            config = new AppConfig("_system_global_", "系统全局");
        }
        final AppConfig finalConfig = config;
        
        // 获取当前线程的亲和性掩码
        Long currentMask = config.getThreadAffinity(threadName);
        if (currentMask == null) currentMask = 0xFFL;
        final long mask = currentMask;
        
        // 创建选择面板
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xE0202020);
        panel.setPadding(16, 12, 16, 12);
        
        // 标题（橙色表示系统线程）
        TextView title = new TextView(this);
        title.setText(String.format(localizedContext.getString(R.string.system_prefix), threadName));
        title.setTextColor(0xFFFF9800);
        title.setTextSize(11);
        title.setGravity(android.view.Gravity.CENTER);
        panel.addView(title);
        
        // CPU核心选择行
        LinearLayout coresRow = new LinearLayout(this);
        coresRow.setOrientation(LinearLayout.HORIZONTAL);
        coresRow.setGravity(android.view.Gravity.CENTER);
        coresRow.setPadding(0, 10, 0, 10);
        
        final TextView[] coreButtons = new TextView[8];
        final boolean[] selected = new boolean[8];
        
        for (int i = 0; i < 8; i++) {
            selected[i] = (mask & (1L << i)) != 0;
        }
        
        float density = getResources().getDisplayMetrics().density;
        int btnSize = (int)(26 * density);
        int margin = (int)(3 * density);
        
        for (int i = 0; i < 8; i++) {
            final int coreIndex = i;
            TextView btn = new TextView(this);
            btn.setText(String.valueOf(i));
            btn.setTextSize(11);
            btn.setGravity(android.view.Gravity.CENTER);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(btnSize, btnSize);
            lp.setMargins(margin, 0, margin, 0);
            btn.setLayoutParams(lp);
            
            int coreColor = i < 3 ? 0xFF7BA3B5 : (i < 7 ? 0xFFD4B445 : 0xFFC45C5C);
            
            if (selected[i]) {
                btn.setBackgroundColor(coreColor);
                btn.setTextColor(0xFF000000);
            } else {
                btn.setBackgroundColor(0xFF333333);
                btn.setTextColor(0xFF666666);
            }
            
            btn.setOnClickListener(v -> {
                selected[coreIndex] = !selected[coreIndex];
                if (selected[coreIndex]) {
                    btn.setBackgroundColor(coreIndex < 3 ? 0xFF7BA3B5 : (coreIndex < 7 ? 0xFFD4B445 : 0xFFC45C5C));
                    btn.setTextColor(0xFF000000);
                } else {
                    btn.setBackgroundColor(0xFF333333);
                    btn.setTextColor(0xFF666666);
                }
            });
            
            coreButtons[i] = btn;
            coresRow.addView(btn);
        }
        panel.addView(coresRow);
        
        // 快捷按钮行
        LinearLayout quickRow = new LinearLayout(this);
        quickRow.setOrientation(LinearLayout.HORIZONTAL);
        quickRow.setGravity(android.view.Gravity.CENTER);
        quickRow.setPadding(0, 4, 0, 8);
        
        // 使用动态 CPU 配置
        CpuInfo cpuInfo = CpuInfo.getInstance();
        String lang = LocaleHelper.getLanguage(this);
        boolean isChinese = LocaleHelper.LANG_CHINESE.equals(lang) || 
            (LocaleHelper.LANG_SYSTEM.equals(lang) && java.util.Locale.getDefault().getLanguage().equals("zh"));
        String[] quickLabels = isChinese ? cpuInfo.getQuickLabelsZh() : cpuInfo.getQuickLabelsEn();
        long[] quickMasks = cpuInfo.getQuickMasks();
        
        for (int q = 0; q < quickLabels.length; q++) {
            final long qMask = quickMasks[q];
            TextView qBtn = new TextView(this);
            qBtn.setText(quickLabels[q]);
            qBtn.setTextSize(10);
            qBtn.setTextColor(0xFFAAAAAA);
            qBtn.setBackgroundColor(0xFF333333);
            qBtn.setGravity(android.view.Gravity.CENTER);
            qBtn.setPadding((int)(12*density), (int)(4*density), (int)(12*density), (int)(4*density));
            
            LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            qlp.setMargins((int)(4*density), 0, (int)(4*density), 0);
            qBtn.setLayoutParams(qlp);
            
            final int cpuCount = cpuInfo.getCpuCount();
            qBtn.setOnClickListener(v -> {
                for (int i = 0; i < cpuCount && i < 8; i++) {
                    selected[i] = (qMask & (1L << i)) != 0;
                    int coreColor = cpuInfo.getColorForCore(i);
                    if (selected[i]) {
                        coreButtons[i].setBackgroundColor(coreColor);
                        coreButtons[i].setTextColor(0xFF000000);
                    } else {
                        coreButtons[i].setBackgroundColor(0xFF333333);
                        coreButtons[i].setTextColor(0xFF666666);
                    }
                }
            });
            quickRow.addView(qBtn);
        }
        panel.addView(quickRow);
        
        // 确定/取消按钮行
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.CENTER);
        
        TextView btnCancel = new TextView(this);
        btnCancel.setText(localizedContext.getString(R.string.cancel));
        btnCancel.setTextSize(11);
        btnCancel.setTextColor(0xFF888888);
        btnCancel.setPadding((int)(20*density), (int)(6*density), (int)(20*density), (int)(6*density));
        
        TextView btnOk = new TextView(this);
        btnOk.setText(localizedContext.getString(R.string.apply));
        btnOk.setTextSize(11);
        btnOk.setTextColor(0xFFFF9800); // 橙色
        btnOk.setPadding((int)(20*density), (int)(6*density), (int)(20*density), (int)(6*density));
        
        btnRow.addView(btnCancel);
        btnRow.addView(btnOk);
        panel.addView(btnRow);
        
        // 创建悬浮窗显示选择面板
        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        panelParams.gravity = Gravity.CENTER;
        
        windowManager.addView(panel, panelParams);
        
        btnCancel.setOnClickListener(v -> windowManager.removeView(panel));
        
        btnOk.setOnClickListener(v -> {
            // 计算新掩码
            long newMask = 0;
            for (int i = 0; i < 8; i++) {
                if (selected[i]) newMask |= (1L << i);
            }
            if (newMask == 0) newMask = 0xFFL;
            
            final long maskToApply = newMask;
            
            // 获取"本APP"的显示文本（用于比较用户点击的线程名）
            String thisAppLabel = localizedContext.getString(R.string.this_app);
            
            // 如果是"本APP"，需要获取本APP的所有线程名并分别保存
            if (thisAppLabel.equals(threadName) || CONFIG_KEY_THIS_APP.equals(threadName)) {
                new Thread(() -> {
                    try {
                        int myPid = android.os.Process.myPid();
                        // 获取本APP的所有线程 TID 和名称
                        String result = RootHelper.executeRootCommand(
                            "ls /proc/" + myPid + "/task 2>/dev/null");
                        
                        if (result != null && !result.trim().isEmpty()) {
                            Set<String> savedNames = new HashSet<>();
                            int appliedCount = 0;
                            
                            for (String tidStr : result.trim().split("\n")) {
                                try {
                                    int tid = Integer.parseInt(tidStr.trim());
                                    // 读取线程名
                                    String name = RootHelper.executeRootCommand(
                                        "cat /proc/" + myPid + "/task/" + tid + "/comm 2>/dev/null");
                                    if (name != null) {
                                        name = name.trim();
                                        if (!name.isEmpty()) {
                                            // 保存到配置（去重）
                                            if (!savedNames.contains(name)) {
                                                finalConfig.addThreadAffinity(name, maskToApply);
                                                savedNames.add(name);
                                            }
                                            // 使用 JNI 应用亲和性
                                            if (NativeHelper.setThreadAffinity(tid, maskToApply)) {
                                                appliedCount++;
                                            }
                                        }
                                    }
                                } catch (Exception e) {}
                            }
                            
                            // 也保存配置键作为标记
                            finalConfig.addThreadAffinity(CONFIG_KEY_THIS_APP, maskToApply);
                            finalConfig.setTimestamp(System.currentTimeMillis());
                            configManager.saveConfig(finalConfig);
                            Log.i(TAG, "Saved system global config for ThisApp: " + savedNames.size() + " thread names, applied to " + appliedCount + " threads -> 0x" + Long.toHexString(maskToApply));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to save/apply ThisApp affinity: " + e.getMessage());
                    }
                }).start();
            } else {
                // 普通系统线程，直接保存
                finalConfig.addThreadAffinity(threadName, maskToApply);
                finalConfig.setTimestamp(System.currentTimeMillis());
                configManager.saveConfig(finalConfig);
                Log.i(TAG, "Saved system global config: " + threadName + " -> 0x" + Long.toHexString(maskToApply));
                
                // 立即应用到所有同名系统线程（使用 JNI）
                new Thread(() -> {
                    try {
                        // 从 /proc/*/task/*/stat 查找同名线程
                        // surfaceflinger, system_server, 本APP
                        int myPid = android.os.Process.myPid();
                        String sfPid = RootHelper.executeRootCommand("pidof surfaceflinger 2>/dev/null");
                        String ssPid = RootHelper.executeRootCommand("pidof system_server 2>/dev/null");
                        if (sfPid != null) sfPid = sfPid.trim();
                        if (ssPid != null) ssPid = ssPid.trim();
                        
                        StringBuilder catCmd = new StringBuilder();
                        if (sfPid != null && !sfPid.isEmpty()) {
                            catCmd.append("cat /proc/").append(sfPid).append("/task/*/stat 2>/dev/null; ");
                        }
                        if (ssPid != null && !ssPid.isEmpty()) {
                            catCmd.append("cat /proc/").append(ssPid).append("/task/*/stat 2>/dev/null; ");
                        }
                        catCmd.append("cat /proc/").append(myPid).append("/task/*/stat 2>/dev/null");
                        
                        // 使用 awk 从 stat 文件正确解析线程名
                        String escapedName = threadName.replace("\\", "\\\\").replace("\"", "\\\"");
                        String cmd = "(" + catCmd.toString() + ") | awk '{ " +
                            "start=index($0, \"(\"); end=0; " +
                            "for(i=length($0); i>0; i--) { if(substr($0,i,1)==\")\") { end=i; break; } } " +
                            "if(start>0 && end>start) { " +
                            "  comm=substr($0, start+1, end-start-1); " +
                            "  tid=substr($0, 1, start-2); gsub(/[^0-9]/, \"\", tid); " +
                            "  if(comm==\"" + escapedName + "\") print tid; " +
                            "} }'";
                        
                        String result = RootHelper.executeRootCommand(cmd);
                        int appliedCount = 0;
                        
                        if (result != null && !result.trim().isEmpty()) {
                            for (String tidStr : result.trim().split("\n")) {
                                try {
                                    int tid = Integer.parseInt(tidStr.trim());
                                    if (NativeHelper.setThreadAffinity(tid, maskToApply)) {
                                        appliedCount++;
                                    }
                                } catch (Exception e) {}
                            }
                        }
                        
                        Log.i(TAG, "Applied system affinity: " + threadName + " -> 0x" + Long.toHexString(maskToApply) + " (" + appliedCount + " threads)");
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to apply system affinity: " + e.getMessage());
                    }
                }).start();
            }
            
            windowManager.removeView(panel);
        });
    }
    
    /**
     * 获取渐变颜色：绿色(0) → 黄色(0.5) → 红色(1.0)
     */
    private int getGradientColor(float ratio) {
        ratio = Math.max(0, Math.min(1, ratio));
        
        int r, g, b;
        if (ratio < 0.5f) {
            // 绿色 → 黄色
            float t = ratio * 2;
            r = (int) (0x4C + (0xFF - 0x4C) * t);  // 76 → 255
            g = (int) (0xAF + (0xB7 - 0xAF) * t);  // 175 → 183
            b = (int) (0x50 + (0x4D - 0x50) * t);  // 80 → 77
        } else {
            // 黄色 → 红色
            float t = (ratio - 0.5f) * 2;
            r = (int) (0xFF + (0xE5 - 0xFF) * t);  // 255 → 229
            g = (int) (0xB7 + (0x39 - 0xB7) * t);  // 183 → 57
            b = (int) (0x4D + (0x35 - 0x4D) * t);  // 77 → 53
        }
        
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max - 1) + "…");
    }

    /**
     * 关闭悬浮窗并杀死整个APP进程
     * 相当于强制停止，重新进入app的状态
     */
    private void killApp() {
        // 先置空视图引用，防止后台线程继续更新UI
        View oldView = floatingView;
        floatingView = null;
        
        // 停止所有调度器
        if (scheduler != null) scheduler.shutdownNow();
        if (fpsScheduler != null) fpsScheduler.shutdownNow();
        if (threadScheduler != null) threadScheduler.shutdownNow();
        if (sysThreadScheduler != null) sysThreadScheduler.shutdownNow();
        if (affinityScheduler != null) affinityScheduler.shutdownNow();
        
        // 等待调度器停止（最多200ms）
        try {
            if (scheduler != null) scheduler.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (threadScheduler != null) threadScheduler.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (sysThreadScheduler != null) sysThreadScheduler.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (affinityScheduler != null) affinityScheduler.awaitTermination(50, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        
        // 移除悬浮窗
        if (oldView != null && windowManager != null) {
            try {
                windowManager.removeView(oldView);
            } catch (Exception e) {
                // ignore
            }
        }
        
        // 关闭Shell
        RootHelper.closeShell();
        NativeHelper.closeRootShell();
        
        // 停止前台服务
        stopForeground(true);
        stopSelf();
        
        // 使用 System.exit 退出，比 killProcess 更干净
        System.exit(0);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (scheduler != null) scheduler.shutdown();
        if (threadScheduler != null) threadScheduler.shutdown();
        if (sysThreadScheduler != null) sysThreadScheduler.shutdown();
        if (affinityScheduler != null) affinityScheduler.shutdown();
        if (floatingView != null && windowManager != null) {
            windowManager.removeView(floatingView);
        }
        // 关闭持久化 Root Shell（Java 层和 JNI 层）
        RootHelper.closeShell();
        NativeHelper.closeRootShell();
        Log.i(TAG, "FloatingWindowService destroyed, affinity loop stopped");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
