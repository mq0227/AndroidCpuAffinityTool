#include <jni.h>
#include <string>
#include <vector>
#include <dirent.h>
#include <errno.h>
#include <unistd.h>
#include <cstring>
#include <android/log.h>
#include "affinity_manager.h"
#include "cpu_monitor.h"
#include "root_shell.h"

#define LOG_TAG "ThreadAffinity-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static AffinityManager g_affinityManager;
static CpuMonitor g_cpuMonitor;

// JNI_OnLoad - 在库加载时调用，设置 JavaVM
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGI("JNI_OnLoad called - setting JavaVM for RootShell");
    
    // 设置 JavaVM 到 RootShell，用于回调 Java 层的持久化 shell
    RootShell::getInstance().setJavaVM(vm);
    
    LOGI("JNI_OnLoad completed - RootShell will use Java persistent shell (12MB buffer)");
    return JNI_VERSION_1_6;
}

// 读取文件内容（单行）
static std::string readFile(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return "";
    char buf[256];
    std::string result;
    if (fgets(buf, sizeof(buf), f)) {
        result = buf;
        while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) {
            result.pop_back();
        }
    }
    fclose(f);
    return result;
}

// 读取文件全部内容
static std::string readFileAll(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return "";
    std::string result;
    char buf[1024];
    while (fgets(buf, sizeof(buf), f)) {
        result += buf;
    }
    fclose(f);
    return result;
}

// 读取线程运行的CPU核心
static int readThreadCpu(int tid) {
    char path[128];
    snprintf(path, sizeof(path), "/proc/%d/stat", tid);
    FILE* f = fopen(path, "r");
    if (!f) return -1;
    
    char buf[1024];
    if (!fgets(buf, sizeof(buf), f)) {
        fclose(f);
        return -1;
    }
    fclose(f);
    
    int cpu = -1;
    char* p = buf;
    char* paren = strchr(p, ')');
    if (paren) {
        p = paren + 2;
        int field = 3;
        while (*p && field < 39) {
            if (*p == ' ') {
                field++;
                while (*p == ' ') p++;
            } else {
                p++;
            }
        }
        if (field == 39) {
            cpu = atoi(p);
        }
    }
    return cpu;
}

// 通过包名获取pid
static int getPidByPackageName(const char* packageName) {
    DIR* procDir = opendir("/proc");
    if (!procDir) {
        LOGE("getPidByPackageName: cannot open /proc");
        return -1;
    }
    
    struct dirent* entry;
    while ((entry = readdir(procDir)) != nullptr) {
        // 只处理数字目录（进程ID）
        if (entry->d_type != DT_DIR) continue;
        
        int pid = atoi(entry->d_name);
        if (pid <= 0) continue;
        
        // 读取 /proc/pid/cmdline
        char cmdlinePath[128];
        snprintf(cmdlinePath, sizeof(cmdlinePath), "/proc/%d/cmdline", pid);
        
        FILE* f = fopen(cmdlinePath, "r");
        if (!f) continue;
        
        char cmdline[256] = {0};
        fread(cmdline, 1, sizeof(cmdline) - 1, f);
        fclose(f);
        
        // cmdline 以 null 结尾，比较包名
        if (strcmp(cmdline, packageName) == 0) {
            closedir(procDir);
            LOGI("getPidByPackageName: found pid=%d for %s", pid, packageName);
            return pid;
        }
    }
    
    closedir(procDir);
    LOGE("getPidByPackageName: not found for %s", packageName);
    return -1;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_threadaffinity_manager_NativeHelper_setThreadAffinity(
        JNIEnv *env, jclass clazz, jint tid, jlong mask) {
    LOGI("JNI setThreadAffinity CALLED: tid=%d, mask=0x%llX", tid, (unsigned long long)mask);
    bool result = g_affinityManager.setAffinity(tid, mask);
    LOGI("JNI setThreadAffinity RESULT: %s", result ? "SUCCESS" : "FAILED");
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_threadaffinity_manager_NativeHelper_getThreadAffinity(
        JNIEnv *env, jclass clazz, jint tid) {
    LOGD("JNI getThreadAffinity: tid=%d", tid);
    return g_affinityManager.getAffinity(tid);
}

JNIEXPORT jint JNICALL
Java_com_threadaffinity_manager_NativeHelper_getCpuCount(
        JNIEnv *env, jclass clazz) {
    int count = g_cpuMonitor.getCpuCount();
    LOGD("getCpuCount: %d", count);
    return count;
}

JNIEXPORT jfloatArray JNICALL
Java_com_threadaffinity_manager_NativeHelper_getCpuUsage(
        JNIEnv *env, jclass clazz) {
    std::vector<float> usage = g_cpuMonitor.getCpuUsage();
    jfloatArray result = env->NewFloatArray(usage.size());
    if (result != nullptr) {
        env->SetFloatArrayRegion(result, 0, usage.size(), usage.data());
    }
    return result;
}

JNIEXPORT jfloat JNICALL
Java_com_threadaffinity_manager_NativeHelper_getThreadCpuUsage(
        JNIEnv *env, jclass clazz, jint pid, jint tid) {
    return g_cpuMonitor.getThreadCpuUsage(pid, tid);
}

JNIEXPORT jint JNICALL
Java_com_threadaffinity_manager_NativeHelper_getThreadRunningCpu(
        JNIEnv *env, jclass clazz, jint tid) {
    return g_cpuMonitor.getThreadRunningCpu(tid);
}

JNIEXPORT jobjectArray JNICALL
Java_com_threadaffinity_manager_NativeHelper_getProcessThreads(
        JNIEnv *env, jclass clazz, jint pid) {
    std::vector<std::string> threads;
    
    char taskPath[128];
    snprintf(taskPath, sizeof(taskPath), "/proc/%d/task", pid);
    
    LOGI("getProcessThreads: pid=%d, path=%s", pid, taskPath);
    
    DIR* dir = opendir(taskPath);
    if (!dir) {
        LOGE("getProcessThreads: failed to open %s, errno=%d", taskPath, errno);
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        
        int tid = atoi(entry->d_name);
        if (tid <= 0) continue;
        
        // 读取线程名
        char commPath[256];
        snprintf(commPath, sizeof(commPath), "/proc/%d/task/%d/comm", pid, tid);
        std::string name = readFile(commPath);
        if (name.empty()) continue;
        
        // 读取运行核心
        int cpu = readThreadCpu(tid);
        
        // 格式: tid:name:cpu
        char info[512];
        snprintf(info, sizeof(info), "%d:%s:%d", tid, name.c_str(), cpu);
        threads.push_back(info);
    }
    closedir(dir);
    
    LOGI("getProcessThreads: found %zu threads", threads.size());
    
    // 转换为Java数组
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(threads.size(), stringClass, nullptr);
    for (size_t i = 0; i < threads.size(); i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(threads[i].c_str()));
    }
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_threadaffinity_manager_NativeHelper_getThreadName(
        JNIEnv *env, jclass clazz, jint pid, jint tid) {
    char path[256];
    snprintf(path, sizeof(path), "/proc/%d/task/%d/comm", pid, tid);
    std::string name = readFile(path);
    return env->NewStringUTF(name.c_str());
}

// 通过包名获取PID
JNIEXPORT jint JNICALL
Java_com_threadaffinity_manager_NativeHelper_getPidByPackage(
        JNIEnv *env, jclass clazz, jstring packageNameStr) {
    const char* packageName = env->GetStringUTFChars(packageNameStr, nullptr);
    int pid = getPidByPackageName(packageName);
    env->ReleaseStringUTFChars(packageNameStr, packageName);
    return pid;
}

// 检查进程是否存在
JNIEXPORT jboolean JNICALL
Java_com_threadaffinity_manager_NativeHelper_isProcessRunning(
        JNIEnv *env, jclass clazz, jint pid) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d", pid);
    return access(path, F_OK) == 0 ? JNI_TRUE : JNI_FALSE;
}

// 关闭持久化 Root Shell
JNIEXPORT void JNICALL
Java_com_threadaffinity_manager_NativeHelper_closeRootShell(
        JNIEnv *env, jclass clazz) {
    LOGI("closeRootShell called");
    RootShell::getInstance().close();
}

// 检查 Root Shell 是否存活
JNIEXPORT jboolean JNICALL
Java_com_threadaffinity_manager_NativeHelper_isRootShellAlive(
        JNIEnv *env, jclass clazz) {
    return RootShell::getInstance().isAlive() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
