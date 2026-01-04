#include "root_shell.h"
#include <android/log.h>
#include <cstring>
#include <cstdio>

#define LOG_TAG "RootShell-JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

RootShell& RootShell::getInstance() {
    static RootShell instance;
    return instance;
}

RootShell::RootShell() 
    : m_javaVM(nullptr)
    , m_initialized(false)
    , m_javaRefsInitialized(false)
    , m_rootShellClass(nullptr)
    , m_executeMethod(nullptr) {
    LOGI("RootShell constructor - using Java persistent shell");
}

RootShell::~RootShell() {
    close();
    LOGI("RootShell destructor");
}

void RootShell::setJavaVM(JavaVM* vm) {
    m_javaVM = vm;
    LOGI("JavaVM set: %p", vm);
}

JNIEnv* RootShell::getJNIEnv() {
    if (!m_javaVM) {
        LOGE("JavaVM is null!");
        return nullptr;
    }
    
    JNIEnv* env = nullptr;
    int status = m_javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    
    if (status == JNI_EDETACHED) {
        // 当前线程未附加到 JVM，需要附加
        if (m_javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach current thread to JVM");
            return nullptr;
        }
        LOGD("Thread attached to JVM");
    } else if (status != JNI_OK) {
        LOGE("GetEnv failed with status %d", status);
        return nullptr;
    }
    
    return env;
}

bool RootShell::initJavaRefs(JNIEnv* env) {
    if (m_javaRefsInitialized && m_rootShellClass && m_executeMethod) {
        return true;
    }
    
    LOGI("Initializing Java references...");
    
    // 查找 RootShell 类
    jclass localClass = env->FindClass("com/threadaffinity/manager/util/RootShell");
    if (!localClass) {
        LOGE("Failed to find RootShell class");
        return false;
    }
    
    // 创建全局引用
    m_rootShellClass = (jclass)env->NewGlobalRef(localClass);
    env->DeleteLocalRef(localClass);
    
    if (!m_rootShellClass) {
        LOGE("Failed to create global ref for RootShell class");
        return false;
    }
    
    // 查找 execute 静态方法: public static String execute(String command, long timeoutMs)
    m_executeMethod = env->GetStaticMethodID(m_rootShellClass, "execute", 
                                              "(Ljava/lang/String;J)Ljava/lang/String;");
    if (!m_executeMethod) {
        LOGE("Failed to find execute method");
        env->DeleteGlobalRef(m_rootShellClass);
        m_rootShellClass = nullptr;
        return false;
    }
    
    m_javaRefsInitialized = true;
    LOGI("Java references initialized successfully");
    return true;
}

std::string RootShell::executeViaJava(const std::string& command, int timeoutMs) {
    JNIEnv* env = getJNIEnv();
    if (!env) {
        LOGE("executeViaJava: Failed to get JNIEnv");
        return "";
    }
    
    if (!initJavaRefs(env)) {
        LOGE("executeViaJava: Failed to init Java refs");
        return "";
    }
    
    // 创建 Java 字符串
    jstring jCommand = env->NewStringUTF(command.c_str());
    if (!jCommand) {
        LOGE("executeViaJava: Failed to create jstring");
        return "";
    }
    
    // 调用 Java 方法
    LOGD("Calling Java RootShell.execute('%s', %d)", command.c_str(), timeoutMs);
    jstring jResult = (jstring)env->CallStaticObjectMethod(
        m_rootShellClass, m_executeMethod, jCommand, (jlong)timeoutMs);
    
    env->DeleteLocalRef(jCommand);
    
    // 检查异常
    if (env->ExceptionCheck()) {
        LOGE("executeViaJava: Java exception occurred");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return "";
    }
    
    // 转换结果
    std::string result;
    if (jResult) {
        const char* cResult = env->GetStringUTFChars(jResult, nullptr);
        if (cResult) {
            result = cResult;
            env->ReleaseStringUTFChars(jResult, cResult);
        }
        env->DeleteLocalRef(jResult);
    }
    
    LOGD("Java RootShell.execute returned: length=%zu", result.length());
    return result;
}

bool RootShell::init() {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    LOGI("Testing root access via Java persistent shell...");
    
    std::string result = executeViaJava("id", 5000);
    LOGI("Root test result: '%s'", result.c_str());
    
    if (result.find("uid=0") != std::string::npos) {
        m_initialized = true;
        LOGI("Root access confirmed (uid=0) via Java persistent shell");
        return true;
    } else {
        LOGE("Root access NOT available! Result: '%s'", result.c_str());
        m_initialized = false;
        return false;
    }
}

bool RootShell::isAlive() {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_initialized && m_javaVM != nullptr;
}

void RootShell::close() {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    // 清理 Java 全局引用
    if (m_rootShellClass && m_javaVM) {
        JNIEnv* env = nullptr;
        if (m_javaVM->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK && env) {
            env->DeleteGlobalRef(m_rootShellClass);
        }
    }
    
    m_rootShellClass = nullptr;
    m_executeMethod = nullptr;
    m_javaRefsInitialized = false;
    m_initialized = false;
    
    LOGI("RootShell closed");
}

std::string RootShell::execute(const std::string& command, int timeoutMs) {
    std::lock_guard<std::mutex> lock(m_mutex);
    
    LOGI("execute() via Java shell: cmd='%s'", command.c_str());
    
    std::string result = executeViaJava(command, timeoutMs);
    
    LOGD("execute() result length=%zu", result.length());
    
    return result;
}

bool RootShell::setThreadAffinity(int tid, uint64_t mask) {
    LOGI("setThreadAffinity() via Java shell: tid=%d, mask=0x%llX", 
         tid, (unsigned long long)mask);
    
    // 先移到 top-app cpuset
    bool cpusetResult = moveToTopAppCpuset(tid);
    LOGD("moveToTopAppCpuset result: %d", cpusetResult);
    
    // 使用 taskset 设置亲和性，掩码以十六进制格式
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "taskset -p %llX %d 2>&1", 
             (unsigned long long)mask, tid);
    
    LOGD("Executing via Java shell: %s", cmd);
    
    std::string result = execute(cmd, 3000);
    
    LOGD("taskset result: '%s'", result.c_str());
    
    // 检查是否成功
    bool success = !result.empty() && 
                   result.find("failed") == std::string::npos &&
                   result.find("Invalid") == std::string::npos &&
                   result.find("error") == std::string::npos &&
                   result.find("No such") == std::string::npos;
    
    if (success) {
        LOGI("setThreadAffinity SUCCESS via Java shell: tid=%d, mask=0x%llX", 
             tid, (unsigned long long)mask);
    } else {
        LOGE("setThreadAffinity FAILED via Java shell: tid=%d, mask=0x%llX, result='%s'", 
             tid, (unsigned long long)mask, result.c_str());
    }
    
    return success;
}

uint64_t RootShell::getThreadAffinity(int tid) {
    char cmd[64];
    snprintf(cmd, sizeof(cmd), "taskset -p %d 2>&1", tid);
    
    std::string result = execute(cmd, 2000);
    
    // 解析输出: "pid 12345's current affinity mask: ff"
    uint64_t mask = 0;
    size_t pos = result.find("mask:");
    if (pos != std::string::npos) {
        pos += 5;
        while (pos < result.length() && result[pos] == ' ') pos++;
        mask = strtoull(result.c_str() + pos, nullptr, 16);
    }
    
    LOGD("getThreadAffinity via Java shell: tid=%d, mask=0x%llX", 
         tid, (unsigned long long)mask);
    return mask;
}

bool RootShell::moveToTopAppCpuset(int tid) {
    char cmd[128];
    snprintf(cmd, sizeof(cmd), "echo %d > /dev/cpuset/top-app/tasks 2>&1", tid);
    
    std::string result = execute(cmd, 1000);
    
    // 如果没有错误输出，认为成功
    bool success = result.empty() || 
                   (result.find("error") == std::string::npos &&
                    result.find("Permission") == std::string::npos);
    
    return success;
}
