# Release v1.1.2 - 音量键控制悬浮窗

## 🎉 新功能

### 音量键快捷控制
- ✅ **按一次音量减键**：隐藏悬浮窗
- ✅ **再按一次音量减键**：显示悬浮窗
- ✅ 后台 CPU 亲和性管理线程**不受影响**，持续运行
- ✅ 防抖处理（300ms），避免误触

## 📝 功能说明

### 使用场景
在游戏或应用运行时，如果悬浮窗遮挡视线，可以快速按音量减键隐藏悬浮窗。需要查看监控数据时，再按一次音量减键即可显示。

### 工作原理
- 使用 `getevent` 监听系统按键事件
- 通过广播机制通知应用切换悬浮窗可见性
- **重要**：只是隐藏 UI 视图，所有后台监控和亲和性管理线程继续运行

### 技术特性
- 🔧 自动启动/停止音量键监听服务
- 🛡️ 防抖机制，避免误触
- 🔄 自动清理，Service 销毁时停止监听
- 📱 兼容所有 Android 设备

## 🔧 技术细节

### 修改的文件
1. **FloatingWindowService.java**
   - 添加 `VolumeKeyReceiver` 广播接收器
   - 添加音量键监听启动/停止逻辑
   - 添加悬浮窗显示/隐藏切换方法

2. **RootHelper.java**
   - 添加 `executeRootCommandAsync()` 方法
   - 支持长时间运行的后台任务

### 后台线程不受影响
以下线程持续运行，不受悬浮窗显示/隐藏影响：
- ✅ CPU 信息更新（1.8秒周期）
- ✅ 帧率更新（1.2秒周期）
- ✅ APP 线程列表更新（1.8秒周期）
- ✅ 系统线程列表更新（1.8秒周期）
- ✅ **循环应用亲和性（10秒周期）** ← 核心功能

## 📦 安装说明

### 要求
- Android 8.0+
- Root 权限
- 悬浮窗权限

### 安装步骤
1. 下载 `app-debug.apk`
2. 安装到设备
3. 授予悬浮窗权限和 Root 权限
4. 启动应用，输入目标应用包名
5. 点击"启动悬浮窗"

## 🧪 测试

运行测试脚本验证功能：
```bash
test_volume_key.cmd
```

或手动测试：
```bash
# 启动悬浮窗
adb shell "am start -n com.threadaffinity.manager/.QuickStartActivity -e package com.tencent.tmgp.sgame -e minimized true"

# 按音量减键控制显示/隐藏
adb shell "input keyevent KEYCODE_VOLUME_DOWN"

# 查看日志
adb logcat -s FloatingWindowService:I | grep -i volume
```

## 🐛 故障排除

### 问题：按音量键没有反应
**解决方案**：
1. 检查 Root 权限：`adb shell "su -c id"`
2. 检查 getevent 进程：`adb shell "ps -ef | grep getevent"`
3. 查看日志：`adb logcat -s FloatingWindowService:*`

### 问题：悬浮窗隐藏后无法显示
**解决方案**：
重启服务：
```bash
adb shell "am stopservice com.threadaffinity.manager/.service.FloatingWindowService"
adb shell "am start -n com.threadaffinity.manager/.QuickStartActivity -e package <包名>"
```

## 📚 文档

详细文档请查看：
- [VOLUME_KEY_FEATURE.md](VOLUME_KEY_FEATURE.md) - 音量键功能详细说明
- [README.md](README.md) - 项目总体说明

## 🙏 致谢

感谢所有使用和反馈的用户！

---

**完整更新日志**: https://github.com/mq0227/AndroidCpuAffinityTool/compare/v1.1.1...v1.1.2
