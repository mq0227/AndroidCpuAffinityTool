package com.threadaffinity.manager.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU 信息检测工具类
 * 自动识别 CPU 核心数量和分组（小核、中核、大核等）
 */
public class CpuInfo {
    private static final String TAG = "CpuInfo";
    private static final String PREFS_NAME = "cpu_info_prefs";
    
    private static CpuInfo instance;
    
    private int cpuCount;
    private int[] maxFreqs;  // 每个核心的最大频率 (MHz)
    private List<CoreGroup> coreGroups;  // 核心分组
    
    /**
     * 核心分组信息
     */
    public static class CoreGroup {
        public String name;       // 分组名称（小核、中核、大核等）
        public String nameEn;     // 英文名称
        public int startCore;     // 起始核心编号
        public int endCore;       // 结束核心编号（包含）
        public long mask;         // 亲和性掩码
        public int maxFreq;       // 该组最大频率
        public int color;         // 显示颜色
        
        public CoreGroup(String name, String nameEn, int start, int end, int maxFreq, int color) {
            this.name = name;
            this.nameEn = nameEn;
            this.startCore = start;
            this.endCore = end;
            this.maxFreq = maxFreq;
            this.color = color;
            
            // 计算掩码
            this.mask = 0;
            for (int i = start; i <= end; i++) {
                this.mask |= (1L << i);
            }
        }
        
        public String getCoreRange() {
            if (startCore == endCore) {
                return String.valueOf(startCore);
            }
            return startCore + "-" + endCore;
        }
        
        public int getCoreCount() {
            return endCore - startCore + 1;
        }
    }
    
    private CpuInfo() {
        detectCpuInfo();
    }
    
    public static synchronized CpuInfo getInstance() {
        if (instance == null) {
            instance = new CpuInfo();
        }
        return instance;
    }
    
    /**
     * 检测 CPU 信息
     */
    private void detectCpuInfo() {
        // 获取 CPU 核心数
        cpuCount = Runtime.getRuntime().availableProcessors();
        if (cpuCount <= 0) cpuCount = 8;
        
        // 读取每个核心的最大频率
        maxFreqs = new int[cpuCount];
        for (int i = 0; i < cpuCount; i++) {
            maxFreqs[i] = readMaxFreq(i);
        }
        
        // 根据频率分组
        coreGroups = detectCoreGroups();
        
        Log.i(TAG, "CPU detected: " + cpuCount + " cores, " + coreGroups.size() + " groups");
        for (CoreGroup group : coreGroups) {
            Log.i(TAG, "  " + group.name + " (" + group.nameEn + "): cores " + 
                  group.getCoreRange() + ", max " + group.maxFreq + " MHz, mask=0x" + 
                  Long.toHexString(group.mask));
        }
    }
    
    /**
     * 读取指定核心的最大频率
     */
    private int readMaxFreq(int core) {
        String path = "/sys/devices/system/cpu/cpu" + core + "/cpufreq/cpuinfo_max_freq";
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line = reader.readLine();
            if (line != null) {
                return Integer.parseInt(line.trim()) / 1000; // 转换为 MHz
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read max freq for cpu" + core + ": " + e.getMessage());
        }
        // 默认值
        return core < 3 ? 2000 : (core < 7 ? 2800 : 3200);
    }
    
    /**
     * 根据频率自动分组
     */
    private List<CoreGroup> detectCoreGroups() {
        List<CoreGroup> groups = new ArrayList<>();
        
        if (cpuCount == 0) {
            return groups;
        }
        
        // 收集所有不同的频率值并排序
        List<Integer> uniqueFreqs = new ArrayList<>();
        for (int freq : maxFreqs) {
            if (!uniqueFreqs.contains(freq)) {
                uniqueFreqs.add(freq);
            }
        }
        uniqueFreqs.sort(Integer::compareTo);
        
        // 定义颜色（从低频到高频）
        int[] colors = {
            0xFF7BA3B5,  // 小核 - 蓝色
            0xFFD4B445,  // 中核 - 黄色
            0xFFC45C5C,  // 大核 - 红色
            0xFFAA44AA   // 超大核 - 紫色（如果有4种）
        };
        
        // 定义名称
        String[][] names;
        if (uniqueFreqs.size() == 2) {
            // 2 种核心（如 8 Elite: 小核 + 大核）
            names = new String[][] {
                {"小核", "Small"},
                {"大核", "Large"}
            };
        } else if (uniqueFreqs.size() == 3) {
            // 3 种核心（如 8 Gen2: 小核 + 中核 + 大核）
            names = new String[][] {
                {"小核", "Small"},
                {"中核", "Medium"},
                {"大核", "Large"}
            };
        } else if (uniqueFreqs.size() >= 4) {
            // 4 种或更多
            names = new String[][] {
                {"小核", "Small"},
                {"中核", "Medium"},
                {"大核", "Large"},
                {"超大核", "Prime"}
            };
        } else {
            // 只有 1 种频率
            names = new String[][] {
                {"全部", "All"}
            };
        }
        
        // 为每种频率创建分组
        for (int i = 0; i < uniqueFreqs.size() && i < names.length; i++) {
            int targetFreq = uniqueFreqs.get(i);
            int startCore = -1;
            int endCore = -1;
            
            // 找到该频率的核心范围
            for (int c = 0; c < cpuCount; c++) {
                if (maxFreqs[c] == targetFreq) {
                    if (startCore == -1) startCore = c;
                    endCore = c;
                }
            }
            
            if (startCore != -1) {
                int colorIndex = Math.min(i, colors.length - 1);
                groups.add(new CoreGroup(
                    names[i][0], names[i][1],
                    startCore, endCore,
                    targetFreq,
                    colors[colorIndex]
                ));
            }
        }
        
        return groups;
    }
    
    // ========== Getters ==========
    
    public int getCpuCount() {
        return cpuCount;
    }
    
    public int[] getMaxFreqs() {
        return maxFreqs;
    }
    
    public int getMaxFreq(int core) {
        if (core >= 0 && core < maxFreqs.length) {
            return maxFreqs[core];
        }
        return 0;
    }
    
    public List<CoreGroup> getCoreGroups() {
        return coreGroups;
    }
    
    public int getGroupCount() {
        return coreGroups.size();
    }
    
    /**
     * 获取指定核心所属的分组
     */
    public CoreGroup getGroupForCore(int core) {
        for (CoreGroup group : coreGroups) {
            if (core >= group.startCore && core <= group.endCore) {
                return group;
            }
        }
        return null;
    }
    
    /**
     * 获取指定核心的颜色
     */
    public int getColorForCore(int core) {
        CoreGroup group = getGroupForCore(core);
        return group != null ? group.color : 0xFF888888;
    }
    
    /**
     * 获取 CPU 信息描述字符串
     */
    public String getCpuDescription(boolean chinese) {
        StringBuilder sb = new StringBuilder();
        sb.append("CPU: ").append(cpuCount);
        sb.append(chinese ? "核 (" : " cores (");
        
        for (int i = 0; i < coreGroups.size(); i++) {
            CoreGroup group = coreGroups.get(i);
            if (i > 0) sb.append(" | ");
            sb.append(chinese ? group.name : group.nameEn);
            sb.append(" ").append(group.getCoreRange());
        }
        sb.append(")");
        
        return sb.toString();
    }
    
    /**
     * 获取快捷按钮的掩码数组
     * 返回: [清除, 分组1, 分组2, ..., 全部]
     */
    public long[] getQuickMasks() {
        long[] masks = new long[coreGroups.size() + 2];
        masks[0] = 0x00L;  // 清除
        for (int i = 0; i < coreGroups.size(); i++) {
            masks[i + 1] = coreGroups.get(i).mask;
        }
        masks[masks.length - 1] = (1L << cpuCount) - 1;  // 全部
        return masks;
    }
    
    /**
     * 获取快捷按钮的标签数组（中文）
     */
    public String[] getQuickLabelsZh() {
        String[] labels = new String[coreGroups.size() + 2];
        labels[0] = "清";
        for (int i = 0; i < coreGroups.size(); i++) {
            // 取第一个字
            labels[i + 1] = coreGroups.get(i).name.substring(0, 1);
        }
        labels[labels.length - 1] = "全";
        return labels;
    }
    
    /**
     * 获取快捷按钮的标签数组（英文）
     */
    public String[] getQuickLabelsEn() {
        String[] labels = new String[coreGroups.size() + 2];
        labels[0] = "Clr";
        for (int i = 0; i < coreGroups.size(); i++) {
            // 取第一个字母
            labels[i + 1] = coreGroups.get(i).nameEn.substring(0, 1);
        }
        labels[labels.length - 1] = "All";
        return labels;
    }
    
    /**
     * 将掩码转换为简短字符串
     */
    public String maskToShortString(long mask, boolean chinese) {
        // 检查是否是全部核心
        long allMask = (1L << cpuCount) - 1;
        if (mask == allMask) {
            return chinese ? "全" : "All";
        }
        
        // 检查是否匹配某个分组
        for (CoreGroup group : coreGroups) {
            if (mask == group.mask) {
                return chinese ? group.name.substring(0, 1) : group.nameEn.substring(0, 1);
            }
        }
        
        // 其他情况显示核心编号
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cpuCount; i++) {
            if ((mask & (1L << i)) != 0) {
                sb.append(i);
            }
        }
        return sb.toString();
    }
    
    /**
     * 将掩码转换为完整描述字符串
     */
    public String maskToFullString(long mask, boolean chinese) {
        // 检查是否是全部核心
        long allMask = (1L << cpuCount) - 1;
        if (mask == allMask) {
            return chinese ? "全部核心" : "All Cores";
        }
        
        // 检查是否匹配某个分组
        for (CoreGroup group : coreGroups) {
            if (mask == group.mask) {
                return (chinese ? group.name : group.nameEn) + " " + group.getCoreRange();
            }
        }
        
        // 其他情况显示核心编号
        StringBuilder sb = new StringBuilder();
        sb.append(chinese ? "核心 " : "Core ");
        boolean first = true;
        for (int i = 0; i < cpuCount; i++) {
            if ((mask & (1L << i)) != 0) {
                if (!first) sb.append(",");
                sb.append(i);
                first = false;
            }
        }
        return sb.toString();
    }
}
