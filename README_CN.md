# 线程亲和性管理器

[English](README.md)

## 为什么需要这个工具？

安卓设备的内核调度策略往往不够精准，经常出现"小核干活、大核围观"的尴尬局面：

- **负载不均衡**：效率核心（小核）满载运行，而性能核心（大核）却在摸鱼
- **发热严重**：任务分配不合理导致小核过载发热
- **性能浪费**：任务不会主动迁移到空闲的高性能核心
- **亲和性被重置**：手动设置的 CPU 亲和性会被系统调度器覆盖

线程亲和性管理器可以解决这些问题。通过循环脚本配置，你可以将指定的同名线程绑定到喜欢的核心上运行。工具会持续强制执行你的亲和性规则，无论内核调度器怎么想，线程都会乖乖待在你指定的核心上。

---

一款强大的 Android 应用，用于监控和管理线程 CPU 亲和性。需要 Root 权限。

**专为中兴、努比亚、红魔手机优化。**

## 支持设备

本应用针对搭载骁龙 8 Gen2/Gen3 处理器的设备进行了优化，特别是：
- **中兴（ZTE）** 系列
- **努比亚（Nubia）** 系列
- **红魔（RedMagic）** 游戏手机（红魔 8 Pro、9 Pro 等）

CPU Boost 配置专门针对努比亚内核参数进行了调优（如 `cpufreq_ctrl`、`walt` 调度器）。

## 功能特性

### 核心功能
- **实时线程监控**：查看目标应用的所有线程及 CPU 占用率
- **CPU 亲和性配置**：将指定线程绑定到特定 CPU 核心
- **悬浮窗监控**：始终置顶的悬浮窗，显示 CPU 频率、负载和线程状态
- **系统线程管理**：配置系统进程（surfaceflinger、system_server）的亲和性
- **配置持久化**：保存配置并自动应用

### CPU 优化
- 自动配置 CPU Boost（禁用 EAS、core_ctl 等）
- 激进的升频参数设置
- 调度器参数调优，防止亲和性被重置

CPU Boost 命令位于 `FloatingWindowService.java` → `applyAffinityInBackground()` 方法中。这些命令每 10 秒执行一次，防止系统重置设置。

### 用户界面
- 悬浮窗最小化/最大化模式
- 可调节透明度（5 档）
- 可拖动窗口位置
- 彩色 CPU 核心指示器（小核/中核/大核）

## 环境要求

### 编译要求
- Android Studio 或 Gradle 命令行
- Android SDK 34+
- NDK 25.x 或更高版本
- CMake 3.22.1+

### 运行要求
- Android 8.0+（API 26+）
- Root 权限（推荐 Magisk）
- 悬浮窗权限

## 编译步骤

1. 创建 `local.properties` 文件，配置 SDK/NDK 路径：
```properties
sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk
ndk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk\\ndk\\25.2.9519653
```

2. 命令行编译：
```bash
cd ThreadAffinityManager
./gradlew assembleDebug
```

3. APK 输出位置：`app/build/outputs/apk/debug/app-debug.apk`

## 使用说明

### 基本使用
1. 在已 Root 的设备上安装 APK
2. 授予悬浮窗权限
3. 从应用列表中选择目标应用
4. 点击"启动悬浮监控"启动悬浮窗
5. 在悬浮窗中点击任意线程配置其 CPU 亲和性
6. 配置自动保存，每 10 秒循环应用

### 截图说明

#### 主界面

| English | 中文 |
|:-------:|:----:|
| <img src="imageen.png" width="280" alt="Main Interface (English)"> | <img src="imagecn.png" width="280" alt="主界面（中文）"> |

#### 悬浮监控

| 悬浮监控窗口 | 线程亲和性选择 |
|:------------:|:--------------:|
| <img src="image1.png" width="280" alt="悬浮监控"> | <img src="image2.png" width="280" alt="线程亲和性"> |

- **悬浮监控**：显示 CPU 频率、负载和线程列表（含 CPU 占用率）
- **线程亲和性**：点击任意线程，选择该线程应该运行在哪些 CPU 核心上

### 通过 ADB 快速启动
```bash
# 启动指定包名的监控
am start -a com.threadaffinity.QUICK_START -e package com.example.app
```

### 悬浮窗控制
- **点击标题栏**：拖动移动窗口
- **α 按钮**：切换透明度
- **− 按钮**：最小化为紧凑视图
- **× 按钮**：关闭并退出应用

### CPU 核心布局（骁龙 8 Gen2）
| 核心 | 类型 | 掩码 |
|------|------|------|
| 0-2 | 小核（效率核心） | 0x07 |
| 3-6 | 中核（性能核心） | 0x78 |
| 7 | 大核（超大核心） | 0x80 |

## 配置格式

配置以 JSON 文件形式存储在应用内部存储中。

### 示例：应用配置
```json
{
  "packageName": "com.example.game",
  "appName": "示例游戏",
  "timestamp": 1704067200000,
  "threadAffinities": {
    "GameThread": 64,
    "RenderThread": 128,
    "AudioTrack": 56
  }
}
```

### 示例：系统全局配置
```json
{
  "packageName": "_system_global_",
  "appName": "系统全局",
  "threadAffinities": {
    "surfaceflinger": 224,
    "RenderEngine": 224,
    "InputDispatcher": 48
  }
}
```

### 亲和性掩码值
| 值 | 二进制 | 核心 |
|----|--------|------|
| 7 (0x07) | 00000111 | 0,1,2（小核） |
| 56 (0x38) | 00111000 | 3,4,5（中核） |
| 120 (0x78) | 01111000 | 3,4,5,6（中核） |
| 64 (0x40) | 01000000 | 6 |
| 128 (0x80) | 10000000 | 7（大核） |
| 192 (0xC0) | 11000000 | 6,7（大核） |
| 224 (0xE0) | 11100000 | 5,6,7 |
| 255 (0xFF) | 11111111 | 全部核心 |

## 项目结构

```
ThreadAffinityManager/
├── app/src/main/
│   ├── java/.../
│   │   ├── MainActivity.java       # 主界面
│   │   ├── NativeHelper.java       # JNI 接口
│   │   ├── QuickStartActivity.java # ADB 快速启动
│   │   ├── service/
│   │   │   └── FloatingWindowService.java  # 悬浮窗服务
│   │   ├── model/
│   │   │   ├── AppConfig.java      # 配置模型
│   │   │   └── ThreadInfo.java     # 线程数据模型
│   │   └── util/
│   │       ├── ConfigManager.java  # 配置持久化
│   │       ├── RootHelper.java     # Root 命令执行
│   │       └── ProcessHelper.java  # 进程工具
│   ├── cpp/
│   │   └── native-lib.cpp          # Native 实现
│   └── res/layout/
│       └── layout_floating_window.xml
└── configs/                         # 示例配置
```

## 工作原理

### 线程监控
1. 通过 `/proc/[pid]/task/` 获取进程的所有线程
2. 读取 `/proc/[pid]/task/[tid]/stat` 获取线程 CPU 时间
3. 两次采样计算 CPU 占用率
4. 按线程名聚合同名线程

### 亲和性设置
1. 使用 `sched_setaffinity()` 系统调用设置线程亲和性
2. 通过 JNI 调用 Native 代码执行
3. 需要 Root 权限才能设置其他进程的线程

### CPU Boost 配置
每 10 秒自动刷新以下参数，防止被系统重置：
- 禁用能效感知调度（EAS）
- 禁用强制负载均衡
- 禁用 WALT 大任务轮转
- 禁用 core_ctl
- 开启全局 boost
- 设置激进升频参数

## 常见问题

### 亲和性未生效
- 检查是否已授予 Root 权限
- 部分 ROM 可能会覆盖亲和性设置
- 尝试禁用系统性能管理器

### CPU 占用过高
- 降低监控频率
- 不需要时最小化悬浮窗

### 应用崩溃
- 查看 logcat 日志：
```bash
adb logcat -s FloatingWindowService:* NativeHelper:* RootHelper:*
```

## 更新日志

### v1.0
- 初始版本
- 支持线程监控和亲和性配置
- 悬浮窗实时显示
- 配置持久化

## 捐赠

如果这个项目对你有帮助，欢迎支持开发：

| 微信 | 支付宝 |
|:----:|:------:|
| <img src="donate.png" width="180" alt="微信"> | <img src="donate_alipay.jpg" width="180" alt="支付宝"> |

感谢你的支持！☕

## 许可证

MIT License

## 致谢

- 使用 JNI 实现原生线程亲和性操作
- 灵感来源于 Android 社区的各种 CPU 调优工具
