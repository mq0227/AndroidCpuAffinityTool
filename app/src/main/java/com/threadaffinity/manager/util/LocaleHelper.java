package com.threadaffinity.manager.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.Locale;

/**
 * 语言切换帮助类
 * 支持应用内切换语言，不依赖系统语言设置
 */
public class LocaleHelper {
    private static final String PREFS_NAME = "language_prefs";
    private static final String KEY_LANGUAGE = "app_language";
    
    // 支持的语言代码
    public static final String LANG_SYSTEM = "system";  // 跟随系统
    public static final String LANG_ENGLISH = "en";
    public static final String LANG_CHINESE = "zh";
    
    /**
     * 获取当前保存的语言设置
     */
    public static String getLanguage(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_LANGUAGE, LANG_SYSTEM);
    }
    
    /**
     * 保存语言设置
     */
    public static void setLanguage(Context context, String language) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_LANGUAGE, language).apply();
    }
    
    /**
     * 应用语言设置到 Context
     * 在 Activity 的 attachBaseContext 中调用
     */
    public static Context applyLanguage(Context context) {
        String language = getLanguage(context);
        return updateResources(context, language);
    }
    
    /**
     * 更新资源配置
     */
    private static Context updateResources(Context context, String language) {
        Locale locale;
        
        if (LANG_SYSTEM.equals(language)) {
            // 跟随系统语言
            locale = Resources.getSystem().getConfiguration().getLocales().get(0);
        } else if (LANG_CHINESE.equals(language)) {
            locale = Locale.SIMPLIFIED_CHINESE;
        } else if (LANG_ENGLISH.equals(language)) {
            locale = Locale.ENGLISH;
        } else {
            locale = Locale.getDefault();
        }
        
        Locale.setDefault(locale);
        
        Resources resources = context.getResources();
        Configuration config = new Configuration(resources.getConfiguration());
        config.setLocale(locale);
        
        return context.createConfigurationContext(config);
    }
    
    /**
     * 获取语言显示名称
     */
    public static String getLanguageDisplayName(Context context, String langCode) {
        switch (langCode) {
            case LANG_SYSTEM:
                return "System";
            case LANG_ENGLISH:
                return "English";
            case LANG_CHINESE:
                return "中文";
            default:
                return langCode;
        }
    }
    
    /**
     * 获取当前语言的显示名称
     */
    public static String getCurrentLanguageDisplayName(Context context) {
        return getLanguageDisplayName(context, getLanguage(context));
    }
}
