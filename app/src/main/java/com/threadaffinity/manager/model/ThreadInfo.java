package com.threadaffinity.manager.model;

/**
 * 线程信息模型
 */
public class ThreadInfo {
    private int tid;
    private String name;
    private long affinityMask;
    private float cpuUsage;
    private int runningCpu;
    private String state;
    private int sameNameCount;  // 同名线程数量

    public ThreadInfo(int tid, String name) {
        this.tid = tid;
        this.name = name;
        this.affinityMask = -1L; // 默认所有CPU
        this.cpuUsage = 0f;
        this.runningCpu = -1;
        this.state = "unknown";
        this.sameNameCount = 1;
    }

    public int getTid() { return tid; }
    public void setTid(int tid) { this.tid = tid; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getAffinityMask() { return affinityMask; }
    public void setAffinityMask(long affinityMask) { this.affinityMask = affinityMask; }

    public float getCpuUsage() { return cpuUsage; }
    public void setCpuUsage(float cpuUsage) { this.cpuUsage = cpuUsage; }

    public int getRunningCpu() { return runningCpu; }
    public void setRunningCpu(int runningCpu) { this.runningCpu = runningCpu; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public int getSameNameCount() { return sameNameCount; }
    public void setSameNameCount(int sameNameCount) { this.sameNameCount = sameNameCount; }

    public String getAffinityString(int cpuCount) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cpuCount; i++) {
            if ((affinityMask & (1L << i)) != 0) {
                if (sb.length() > 0) sb.append(",");
                sb.append(i);
            }
        }
        return sb.length() > 0 ? sb.toString() : "all";
    }

    /**
     * 获取友好的亲和性显示文本
     * 例如: "大核 (CPU 3-6)" 或 "小核 (CPU 0-2)"
     */
    public String getAffinityFriendlyString(int cpuCount) {
        if (affinityMask <= 0 || affinityMask == ((1L << cpuCount) - 1)) {
            return "全部核心";
        }

        // 8gen2: CPU 0-2 小核, CPU 3-6 大核, CPU 7 超大核
        // 通用判断逻辑
        boolean hasSmall = false, hasBig = false, hasPrime = false;
        int smallCount = 0, bigCount = 0, primeCount = 0;
        
        // 假设前 3 个是小核，中间是大核，最后 1 个是超大核
        int smallEnd = Math.min(3, cpuCount);
        int bigEnd = Math.max(smallEnd, cpuCount - 1);
        
        for (int i = 0; i < cpuCount; i++) {
            if ((affinityMask & (1L << i)) != 0) {
                if (i < smallEnd) {
                    hasSmall = true;
                    smallCount++;
                } else if (i < bigEnd) {
                    hasBig = true;
                    bigCount++;
                } else {
                    hasPrime = true;
                    primeCount++;
                }
            }
        }

        StringBuilder result = new StringBuilder();
        
        // 判断是否是完整的核心组
        if (hasSmall && !hasBig && !hasPrime && smallCount == smallEnd) {
            return "小核 (CPU 0-" + (smallEnd - 1) + ")";
        }
        if (!hasSmall && hasBig && !hasPrime && bigCount == (bigEnd - smallEnd)) {
            return "大核 (CPU " + smallEnd + "-" + (bigEnd - 1) + ")";
        }
        if (!hasSmall && !hasBig && hasPrime) {
            return "超大核 (CPU " + (cpuCount - 1) + ")";
        }
        if (!hasSmall && hasBig && hasPrime) {
            return "大核+超大核 (CPU " + smallEnd + "-" + (cpuCount - 1) + ")";
        }
        
        // 显示具体的 CPU 列表
        StringBuilder cpuList = new StringBuilder();
        int rangeStart = -1, rangeEnd = -1;
        
        for (int i = 0; i <= cpuCount; i++) {
            boolean isSet = i < cpuCount && (affinityMask & (1L << i)) != 0;
            
            if (isSet) {
                if (rangeStart < 0) {
                    rangeStart = i;
                }
                rangeEnd = i;
            } else if (rangeStart >= 0) {
                if (cpuList.length() > 0) cpuList.append(",");
                if (rangeStart == rangeEnd) {
                    cpuList.append(rangeStart);
                } else {
                    cpuList.append(rangeStart).append("-").append(rangeEnd);
                }
                rangeStart = -1;
            }
        }
        
        return "CPU " + cpuList.toString();
    }
}
