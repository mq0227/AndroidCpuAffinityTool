package com.threadaffinity.manager;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.threadaffinity.manager.adapter.AppAdapter;
import com.threadaffinity.manager.model.AppConfig;
import com.threadaffinity.manager.model.AppInfo;
import com.threadaffinity.manager.service.FloatingWindowService;
import com.threadaffinity.manager.util.*;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int OVERLAY_PERMISSION_CODE = 1001;
    private static final int NOTIFICATION_PERMISSION_CODE = 1002;

    private TextView tvRootStatus, tvCpuInfo, tvProcessInfo, tvConfigCount, tvNoConfigs;
    private TextView tvLanguage;
    private EditText etPackageName;
    private Button btnSelectApp, btnToggleMonitor;
    private LinearLayout layoutSavedConfigs;
    
    private ConfigManager configManager;
    private ExecutorService executor;
    private Handler mainHandler;
    
    private int currentPid = -1;
    private String currentPackage = "";
    private int cpuCount = 8;
    private boolean isMonitoring = false;
    
    private List<AppInfo> allApps = new ArrayList<>();
    private List<AppInfo> runningApps = new ArrayList<>();

    @Override
    protected void attachBaseContext(Context newBase) {
        // 应用语言设置
        super.attachBaseContext(LocaleHelper.applyLanguage(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        initViews();
        initData();
        checkRootAccess();
        checkPermissions();
        loadSavedConfigs();
    }

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.notification_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initViews() {
        tvRootStatus = findViewById(R.id.tvRootStatus);
        tvCpuInfo = findViewById(R.id.tvCpuInfo);
        tvProcessInfo = findViewById(R.id.tvProcessInfo);
        tvConfigCount = findViewById(R.id.tvConfigCount);
        tvNoConfigs = findViewById(R.id.tvNoConfigs);
        etPackageName = findViewById(R.id.etPackageName);
        btnSelectApp = findViewById(R.id.btnSelectApp);
        btnToggleMonitor = findViewById(R.id.btnToggleMonitor);
        layoutSavedConfigs = findViewById(R.id.layoutSavedConfigs);
        tvLanguage = findViewById(R.id.tvLanguage);

        btnSelectApp.setOnClickListener(v -> showAppSelector());
        btnToggleMonitor.setOnClickListener(v -> toggleMonitor());
        tvLanguage.setOnClickListener(v -> showLanguageSelector());
        
        // 捐赠图片长按保存
        ImageView ivDonateWechat = findViewById(R.id.ivDonateWechat);
        ImageView ivDonateAlipay = findViewById(R.id.ivDonateAlipay);
        if (ivDonateWechat != null) {
            ivDonateWechat.setOnLongClickListener(v -> {
                saveDrawableToGallery(R.drawable.donate_qrcode, "donate_wechat.png");
                return true;
            });
        }
        if (ivDonateAlipay != null) {
            ivDonateAlipay.setOnLongClickListener(v -> {
                saveDrawableToGallery(R.drawable.donate_alipay, "donate_alipay.jpg");
                return true;
            });
        }
        
        // 更新语言按钮显示
        updateLanguageButton();
    }
    
    /**
     * 更新语言按钮显示
     */
    private void updateLanguageButton() {
        String lang = LocaleHelper.getLanguage(this);
        String displayText;
        switch (lang) {
            case LocaleHelper.LANG_CHINESE:
                displayText = "中";
                break;
            case LocaleHelper.LANG_ENGLISH:
                displayText = "EN";
                break;
            default:
                displayText = "Auto";
                break;
        }
        tvLanguage.setText(displayText);
    }
    
    /**
     * 显示语言选择对话框
     */
    private void showLanguageSelector() {
        String[] languages = {
            getString(R.string.lang_system),
            getString(R.string.lang_english),
            getString(R.string.lang_chinese)
        };
        String[] langCodes = {
            LocaleHelper.LANG_SYSTEM,
            LocaleHelper.LANG_ENGLISH,
            LocaleHelper.LANG_CHINESE
        };
        
        String currentLang = LocaleHelper.getLanguage(this);
        int checkedItem = 0;
        for (int i = 0; i < langCodes.length; i++) {
            if (langCodes[i].equals(currentLang)) {
                checkedItem = i;
                break;
            }
        }
        
        new AlertDialog.Builder(this)
            .setTitle(R.string.select_language)
            .setSingleChoiceItems(languages, checkedItem, (dialog, which) -> {
                String selectedLang = langCodes[which];
                if (!selectedLang.equals(currentLang)) {
                    LocaleHelper.setLanguage(this, selectedLang);
                    dialog.dismiss();
                    
                    // 显示提示并重启 Activity
                    Toast.makeText(this, R.string.language_changed, Toast.LENGTH_SHORT).show();
                    
                    // 延迟重启以显示 Toast
                    mainHandler.postDelayed(() -> {
                        Intent intent = getIntent();
                        finish();
                        startActivity(intent);
                        overridePendingTransition(0, 0);
                    }, 500);
                } else {
                    dialog.dismiss();
                }
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void initData() {
        configManager = new ConfigManager(this);
        executor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
        
        // 使用动态 CPU 检测
        CpuInfo cpuInfo = CpuInfo.getInstance();
        cpuCount = cpuInfo.getCpuCount();
        
        // 根据当前语言显示 CPU 信息
        String lang = LocaleHelper.getLanguage(this);
        boolean isChinese = LocaleHelper.LANG_CHINESE.equals(lang) || 
            (LocaleHelper.LANG_SYSTEM.equals(lang) && java.util.Locale.getDefault().getLanguage().equals("zh"));
        tvCpuInfo.setText(cpuInfo.getCpuDescription(isChinese));
        
        // 动态更新核心分组显示
        updateCoreGroupsDisplay(cpuInfo, isChinese);
    }
    
    /**
     * 动态更新核心分组显示
     */
    private void updateCoreGroupsDisplay(CpuInfo cpuInfo, boolean isChinese) {
        LinearLayout coreGroupsLayout = findViewById(R.id.layoutCoreGroups);
        if (coreGroupsLayout == null) return;
        
        coreGroupsLayout.removeAllViews();
        
        for (CpuInfo.CoreGroup group : cpuInfo.getCoreGroups()) {
            TextView tv = new TextView(this);
            String text = (isChinese ? group.name : group.nameEn) + " " + group.getCoreRange();
            tv.setText(text);
            tv.setTextSize(11);
            tv.setTextColor(group.color);
            tv.setPadding(16, 4, 16, 4);
            
            // 设置背景色（浅色版本）
            int bgColor = (group.color & 0x00FFFFFF) | 0x20000000;
            tv.setBackgroundColor(bgColor);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMargins(0, 0, 16, 0);
            tv.setLayoutParams(lp);
            
            coreGroupsLayout.addView(tv);
        }
    }

    private void checkRootAccess() {
        executor.execute(() -> {
            boolean hasRoot = RootHelper.hasRootAccess();
            mainHandler.post(() -> {
                if (hasRoot) {
                    tvRootStatus.setText(R.string.root_yes);
                    tvRootStatus.setBackgroundColor(0xFF4CAF50);
                } else {
                    tvRootStatus.setText(R.string.root_no);
                    tvRootStatus.setBackgroundColor(0xFFF44336);
                }
                tvRootStatus.setTextColor(0xFFFFFFFF);
            });
        });
    }

    private void showAppSelector() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_app_selector, null);
        builder.setView(dialogView);
        builder.setTitle(R.string.select_app);
        
        EditText etSearch = dialogView.findViewById(R.id.etSearch);
        RadioGroup rgFilter = dialogView.findViewById(R.id.rgFilter);
        RadioButton rbRunning = dialogView.findViewById(R.id.rbRunning);
        TextView tvAppCount = dialogView.findViewById(R.id.tvAppCount);
        ListView lvApps = dialogView.findViewById(R.id.lvApps);
        
        AppAdapter adapter = new AppAdapter(this);
        lvApps.setAdapter(adapter);
        
        AlertDialog dialog = builder.create();
        
        Runnable loadApps = () -> {
            boolean showRunning = rbRunning.isChecked();
            String query = etSearch.getText().toString();
            
            executor.execute(() -> {
                List<AppInfo> apps;
                if (showRunning) {
                    if (runningApps.isEmpty()) {
                        runningApps = AppListHelper.getRunningApps(this);
                    }
                    apps = AppListHelper.searchApps(runningApps, query);
                } else {
                    if (allApps.isEmpty()) {
                        allApps = AppListHelper.getInstalledApps(this, false);
                    }
                    apps = AppListHelper.searchApps(allApps, query);
                }
                
                mainHandler.post(() -> {
                    adapter.setApps(apps);
                    tvAppCount.setText(getString(R.string.app_count, apps.size()));
                });
            });
        };
        
        loadApps.run();
        
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { loadApps.run(); }
        });
        
        rgFilter.setOnCheckedChangeListener((group, checkedId) -> loadApps.run());
        
        lvApps.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo app = adapter.getItem(position);
            etPackageName.setText(app.getPackageName());
            currentPackage = app.getPackageName();
            if (app.getPid() > 0) {
                currentPid = app.getPid();
                tvProcessInfo.setText(getString(R.string.pid_info, currentPid));
            } else {
                currentPid = ProcessHelper.getPidByPackage(currentPackage);
                tvProcessInfo.setText(currentPid > 0 ? getString(R.string.pid_info, currentPid) : getString(R.string.app_not_running));
            }
            dialog.dismiss();
            
            // 自动创建配置（如果不存在）
            createConfigIfNotExists(app.getPackageName(), app.getAppName());
        });
        
        dialog.show();
    }

    private void toggleMonitor() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE);
            return;
        }
        
        if (isMonitoring) {
            stopService(new Intent(this, FloatingWindowService.class));
            btnToggleMonitor.setText(R.string.start_monitor);
            isMonitoring = false;
        } else {
            String packageName = etPackageName.getText().toString().trim();
            if (packageName.isEmpty()) {
                Toast.makeText(this, R.string.please_select_app, Toast.LENGTH_SHORT).show();
                return;
            }
            
            currentPackage = packageName;
            currentPid = ProcessHelper.getPidByPackage(packageName);
            
            if (currentPid <= 0) {
                Toast.makeText(this, R.string.please_start_app, Toast.LENGTH_SHORT).show();
                return;
            }
            
            Intent intent = new Intent(this, FloatingWindowService.class);
            intent.putExtra("pid", currentPid);
            intent.putExtra("package", currentPackage);
            startForegroundService(intent);
            btnToggleMonitor.setText(R.string.stop_monitor);
            isMonitoring = true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION_CODE && Settings.canDrawOverlays(this)) {
            toggleMonitor();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSavedConfigs();
    }

    /**
     * 如果配置不存在则创建空配置
     */
    private void createConfigIfNotExists(String packageName, String appName) {
        executor.execute(() -> {
            AppConfig existing = configManager.loadConfig(packageName);
            if (existing == null) {
                AppConfig newConfig = new AppConfig(packageName, appName);
                configManager.saveConfig(newConfig);
                mainHandler.post(() -> {
                    Toast.makeText(this, R.string.added_to_config, Toast.LENGTH_SHORT).show();
                    loadSavedConfigs();
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void loadSavedConfigs() {
        executor.execute(() -> {
            List<AppConfig> configs = configManager.getAllConfigs();
            
            mainHandler.post(() -> {
                layoutSavedConfigs.removeAllViews();
                
                if (configs.isEmpty()) {
                    tvNoConfigs.setVisibility(View.VISIBLE);
                    tvConfigCount.setText(getString(R.string.config_count, 0));
                    return;
                }
                
                tvNoConfigs.setVisibility(View.GONE);
                tvConfigCount.setText(getString(R.string.config_count, configs.size()));
                
                for (AppConfig config : configs) {
                    addConfigCard(config);
                }
            });
        });
    }

    private void addConfigCard(AppConfig config) {
        View cardView = LayoutInflater.from(this).inflate(R.layout.item_saved_config, layoutSavedConfigs, false);
        
        ImageView ivAppIcon = cardView.findViewById(R.id.ivAppIcon);
        TextView tvConfigAppName = cardView.findViewById(R.id.tvConfigAppName);
        TextView tvConfigPackage = cardView.findViewById(R.id.tvConfigPackage);
        TextView tvConfigRuleCount = cardView.findViewById(R.id.tvConfigRuleCount);
        TextView btnQuickApply = cardView.findViewById(R.id.btnQuickApply);
        TextView btnQuickStart = cardView.findViewById(R.id.btnQuickStart);
        
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(config.getPackageName(), 0);
            ivAppIcon.setImageDrawable(pm.getApplicationIcon(appInfo));
            tvConfigAppName.setText(pm.getApplicationLabel(appInfo).toString());
        } catch (PackageManager.NameNotFoundException e) {
            ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon);
            tvConfigAppName.setText(config.getAppName());
        }
        
        tvConfigPackage.setText(config.getPackageName());
        int ruleCount = config.getThreadAffinities() != null ? config.getThreadAffinities().size() : 0;
        tvConfigRuleCount.setText(getString(R.string.rules_count, ruleCount));
        
        // 快速启动监控
        btnQuickStart.setOnClickListener(v -> {
            etPackageName.setText(config.getPackageName());
            currentPackage = config.getPackageName();
            currentPid = ProcessHelper.getPidByPackage(currentPackage);
            
            if (currentPid > 0) {
                tvProcessInfo.setText(getString(R.string.pid_info, currentPid));
                if (!isMonitoring) {
                    toggleMonitor();
                }
            } else {
                tvProcessInfo.setText(R.string.app_not_running);
                Toast.makeText(this, R.string.please_start_app, Toast.LENGTH_SHORT).show();
            }
        });
        
        // 隐藏快速应用按钮（现在通过悬浮窗配置）
        btnQuickApply.setVisibility(View.GONE);
        
        // 点击卡片查看配置详情
        cardView.setOnClickListener(v -> showConfigDetails(config));
        
        layoutSavedConfigs.addView(cardView);
    }
    
    /**
     * 显示配置详情对话框（只读）
     */
    private void showConfigDetails(AppConfig config) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(config.getAppName() != null ? config.getAppName() : config.getPackageName());
        
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.package_name, config.getPackageName())).append("\n\n");
        
        if (config.getThreadAffinities() == null || config.getThreadAffinities().isEmpty()) {
            sb.append(getString(R.string.no_affinity_config));
        } else {
            sb.append(getString(R.string.affinity_config_count, config.getThreadAffinities().size())).append("\n\n");
            // 按线程名 A-Z 排序
            java.util.List<String> sortedNames = new java.util.ArrayList<>(config.getThreadAffinities().keySet());
            java.util.Collections.sort(sortedNames, String.CASE_INSENSITIVE_ORDER);
            for (String threadName : sortedNames) {
                // 从十六进制字符串获取掩码
                Long mask = config.getThreadAffinity(threadName);
                if (mask != null) {
                    sb.append(threadName).append(" → ").append(maskToString(mask)).append("\n");
                }
            }
        }
        
        builder.setMessage(sb.toString());
        builder.setPositiveButton(R.string.close, null);
        builder.setNeutralButton(R.string.delete_config, (dialog, which) -> {
            new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete)
                .setMessage(getString(R.string.confirm_delete_msg, config.getPackageName()))
                .setPositiveButton(R.string.delete_config, (d, w) -> {
                    configManager.deleteConfig(config.getPackageName());
                    loadSavedConfigs();
                    Toast.makeText(this, R.string.deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        });
        builder.show();
    }
    
    /**
     * 将掩码转换为可读字符串
     */
    private String maskToString(long mask) {
        String lang = LocaleHelper.getLanguage(this);
        boolean isChinese = LocaleHelper.LANG_CHINESE.equals(lang) || 
            (LocaleHelper.LANG_SYSTEM.equals(lang) && java.util.Locale.getDefault().getLanguage().equals("zh"));
        return CpuInfo.getInstance().maskToFullString(mask, isChinese);
    }
    
    /**
     * 保存 drawable 资源到相册（使用 MediaStore，无需存储权限）
     */
    private void saveDrawableToGallery(int drawableId, String fileName) {
        executor.execute(() -> {
            try {
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), drawableId);
                if (bitmap == null) {
                    mainHandler.post(() -> Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show());
                    return;
                }
                
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, fileName.endsWith(".png") ? "image/png" : "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ThreadAffinityManager");
                
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            Bitmap.CompressFormat format = fileName.endsWith(".png") ? 
                                Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
                            bitmap.compress(format, 100, out);
                            mainHandler.post(() -> Toast.makeText(this, R.string.image_saved, Toast.LENGTH_SHORT).show());
                        }
                    }
                } else {
                    mainHandler.post(() -> Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show());
                }
                bitmap.recycle();
            } catch (Exception e) {
                mainHandler.post(() -> Toast.makeText(this, R.string.image_save_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
