#ifndef ROOT_SHELL_H
#define ROOT_SHELL_H

#include <jni.h>
#include <string>
#include <mutex>
#include <cstdint>

/**
 * Root Shell - 通过 JNI 回调 Java 层的持久化 Shell
 * Java 层的 RootShell 有 12MB 缓冲区，真正的持久化 su 进程
 */
class RootShell {
public:
    static RootShell& getInstance();
    
    // 禁止拷贝和赋值
    RootShell(const RootShell&) = delete;
    RootShell& operator=(const RootShell&) = delete;
    
    /**
     * 设置 JavaVM（必须在 JNI_OnLoad 中调用）
     */
    void setJavaVM(JavaVM* vm);
    
    /**
     * 初始化（测试 root 权限）
     */
    bool init();
    
    /**
     * 执行命令（通过 Java 层的持久化 shell）
     * @param command 要执行的命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 命令输出，失败返回空字符串
     */
    std::string execute(const std::string& command, int timeoutMs = 5000);
    
    /**
     * 设置线程亲和性（通过 taskset）
     * @param tid 线程ID
     * @param mask CPU亲和性掩码
     * @return 是否成功
     */
    bool setThreadAffinity(int tid, uint64_t mask);
    
    /**
     * 获取线程亲和性
     * @param tid 线程ID
     * @return CPU亲和性掩码
     */
    uint64_t getThreadAffinity(int tid);
    
    /**
     * 将线程移到 top-app cpuset
     * @param tid 线程ID
     * @return 是否成功
     */
    bool moveToTopAppCpuset(int tid);
    
    /**
     * 关闭 shell
     */
    void close();
    
    /**
     * 检查 shell 是否存活
     */
    bool isAlive();

private:
    RootShell();
    ~RootShell();
    
    /**
     * 获取当前线程的 JNIEnv
     */
    JNIEnv* getJNIEnv();
    
    /**
     * 调用 Java 层的 RootShell.execute()
     */
    std::string executeViaJava(const std::string& command, int timeoutMs);
    
    /**
     * 初始化 Java 类和方法引用
     */
    bool initJavaRefs(JNIEnv* env);
    
    JavaVM* m_javaVM;
    bool m_initialized;
    bool m_javaRefsInitialized;
    std::mutex m_mutex;
    
    // 缓存的 Java 类和方法引用（全局引用）
    jclass m_rootShellClass;
    jmethodID m_executeMethod;
};

#endif // ROOT_SHELL_H
