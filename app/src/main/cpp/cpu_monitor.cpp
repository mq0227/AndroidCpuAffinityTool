#include "cpu_monitor.h"
#include <android/log.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

#define LOG_TAG "CpuMonitor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

CpuMonitor::CpuMonitor() : cpuCount(0) {
    cpuCount = sysconf(_SC_NPROCESSORS_CONF);
    LOGI("CpuMonitor initialized, CPU count: %d", cpuCount);
    
    // 初始化上一次的CPU时间
    lastCpuTimes.resize(cpuCount + 1);
    readCpuTimes(lastCpuTimes);
}

CpuMonitor::~CpuMonitor() {
    LOGI("CpuMonitor destroyed");
}

int CpuMonitor::getCpuCount() {
    return cpuCount;
}

std::vector<float> CpuMonitor::getCpuUsage() {
    std::vector<CpuTime> currentTimes(cpuCount + 1);
    readCpuTimes(currentTimes);
    
    std::vector<float> usage(cpuCount + 1);
    
    for (int i = 0; i <= cpuCount; i++) {
        uint64_t totalDiff = currentTimes[i].total - lastCpuTimes[i].total;
        uint64_t idleDiff = currentTimes[i].idle - lastCpuTimes[i].idle;
        
        if (totalDiff > 0) {
            usage[i] = 100.0f * (1.0f - (float)idleDiff / (float)totalDiff);
        } else {
            usage[i] = 0.0f;
        }
    }
    
    lastCpuTimes = currentTimes;
    return usage;
}

float CpuMonitor::getThreadCpuUsage(int pid, int tid) {
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/task/%d/stat", pid, tid);
    
    std::ifstream file(path);
    if (!file.is_open()) {
        LOGE("Failed to open %s", path);
        return -1.0f;
    }
    
    std::string line;
    std::getline(file, line);
    file.close();
    
    // 解析stat文件获取utime和stime
    // 格式: pid (comm) state ppid ... utime stime ...
    size_t pos = line.rfind(')');
    if (pos == std::string::npos) {
        return -1.0f;
    }
    
    std::istringstream iss(line.substr(pos + 2));
    std::string token;
    
    // 跳过前面的字段，获取utime(14)和stime(15)
    for (int i = 0; i < 11; i++) {
        iss >> token;
    }
    
    uint64_t utime, stime;
    iss >> utime >> stime;
    
    uint64_t totalTime = utime + stime;
    
    // 计算与上次的差值
    auto it = lastThreadTimes.find(tid);
    float usage = 0.0f;
    
    if (it != lastThreadTimes.end()) {
        uint64_t timeDiff = totalTime - it->second;
        // 假设采样间隔约1秒，HZ通常为100
        usage = (float)timeDiff / sysconf(_SC_CLK_TCK) * 100.0f;
    }
    
    lastThreadTimes[tid] = totalTime;
    
    LOGD("Thread %d CPU usage: %.2f%%", tid, usage);
    return usage;
}

int CpuMonitor::getThreadRunningCpu(int tid) {
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/stat", tid);
    
    std::ifstream file(path);
    if (!file.is_open()) {
        return -1;
    }
    
    std::string line;
    std::getline(file, line);
    file.close();
    
    // 解析获取processor字段(第39个字段)
    size_t pos = line.rfind(')');
    if (pos == std::string::npos) {
        return -1;
    }
    
    std::istringstream iss(line.substr(pos + 2));
    std::string token;
    
    // 跳过前面的字段，获取processor(39-2=37)
    for (int i = 0; i < 36; i++) {
        iss >> token;
    }
    
    int cpu;
    iss >> cpu;
    
    return cpu;
}

void CpuMonitor::readCpuTimes(std::vector<CpuTime>& times) {
    std::ifstream file("/proc/stat");
    if (!file.is_open()) {
        LOGE("Failed to open /proc/stat");
        return;
    }
    
    std::string line;
    int index = 0;
    
    while (std::getline(file, line) && index <= cpuCount) {
        if (line.substr(0, 3) == "cpu") {
            std::istringstream iss(line);
            std::string cpu;
            uint64_t user, nice, system, idle, iowait, irq, softirq, steal;
            
            iss >> cpu >> user >> nice >> system >> idle >> iowait >> irq >> softirq >> steal;
            
            times[index].idle = idle + iowait;
            times[index].total = user + nice + system + idle + iowait + irq + softirq + steal;
            
            index++;
        }
    }
    
    file.close();
}
