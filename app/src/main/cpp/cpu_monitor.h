#ifndef CPU_MONITOR_H
#define CPU_MONITOR_H

#include <vector>
#include <map>
#include <cstdint>

struct CpuTime {
    uint64_t idle = 0;
    uint64_t total = 0;
};

class CpuMonitor {
public:
    CpuMonitor();
    ~CpuMonitor();

    // 获取CPU核心数
    int getCpuCount();
    
    // 获取各CPU使用率 (index 0为总体，1-N为各核心)
    std::vector<float> getCpuUsage();
    
    // 获取指定线程的CPU使用率
    float getThreadCpuUsage(int pid, int tid);
    
    // 获取线程当前运行的CPU核心
    int getThreadRunningCpu(int tid);

private:
    int cpuCount;
    std::vector<CpuTime> lastCpuTimes;
    std::map<int, uint64_t> lastThreadTimes;
    
    void readCpuTimes(std::vector<CpuTime>& times);
};

#endif // CPU_MONITOR_H
