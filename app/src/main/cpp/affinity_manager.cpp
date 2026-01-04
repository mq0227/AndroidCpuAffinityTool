#include "affinity_manager.h"
#include "root_shell.h"
#include <android/log.h>

#define LOG_TAG "AffinityMgr-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AffinityManager::AffinityManager() {
    LOGI("AffinityManager constructor called");
}

AffinityManager::~AffinityManager() {
    LOGI("AffinityManager destructor called");
}

bool AffinityManager::setAffinity(int tid, uint64_t mask) {
    LOGI("setAffinity() called: tid=%d, mask=0x%llX", tid, (unsigned long long)mask);
    
    // 使用持久化 shell 设置亲和性
    bool result = RootShell::getInstance().setThreadAffinity(tid, mask);
    
    LOGI("setAffinity() result: %s", result ? "SUCCESS" : "FAILED");
    return result;
}

uint64_t AffinityManager::getAffinity(int tid) {
    LOGD("getAffinity() called: tid=%d", tid);
    
    // 使用持久化 shell 获取亲和性
    uint64_t mask = RootShell::getInstance().getThreadAffinity(tid);
    
    LOGD("getAffinity() result: mask=0x%llX", (unsigned long long)mask);
    return mask;
}
