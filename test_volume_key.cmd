@echo off
echo ========================================
echo 音量键控制悬浮窗测试脚本
echo ========================================
echo.

echo [1] 启动悬浮窗服务（监控和平精英）
adb shell "am start -n com.threadaffinity.manager/.QuickStartActivity -e package com.tencent.tmgp.sgame -e minimized true"
timeout /t 3 /nobreak >nul

echo.
echo [2] 等待 5 秒，让悬浮窗完全启动...
timeout /t 5 /nobreak

echo.
echo [3] 模拟按下音量减键（隐藏悬浮窗）
adb shell "input keyevent KEYCODE_VOLUME_DOWN"
echo 悬浮窗应该已隐藏

echo.
echo [4] 等待 3 秒...
timeout /t 3 /nobreak

echo.
echo [5] 再次按下音量减键（显示悬浮窗）
adb shell "input keyevent KEYCODE_VOLUME_DOWN"
echo 悬浮窗应该已显示

echo.
echo [6] 查看日志（检查音量键事件）
echo ----------------------------------------
adb logcat -d -s FloatingWindowService:I FloatingWindowService:D | findstr /i "volume"

echo.
echo ========================================
echo 测试完成！
echo 提示：后台的 CPU 亲和性管理线程不受影响
echo ========================================
pause
