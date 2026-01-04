#ifndef AFFINITY_MANAGER_H
#define AFFINITY_MANAGER_H

#include <cstdint>

class AffinityManager {
public:
    AffinityManager();
    ~AffinityManager();

    // 设置线程亲和性（只使用 force_cpu_affinity sysctl）
    bool setAffinity(int tid, uint64_t mask);
    
    // 获取线程亲和性
    uint64_t getAffinity(int tid);
};

#endif // AFFINITY_MANAGER_H
