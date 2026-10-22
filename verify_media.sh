#!/system/bin/sh
# 音乐控制注入的端到端验证脚本
# 前提：已安装 aicy-unlock-2.8.11-debug.apk 且 LSPosed 作用域含 com.meizu.voiceassistant
# 用法：adb push 后 su -c '/system/bin/sh /data/local/tmp/verify_media.sh'

PKG_AICY=com.meizu.voiceassistant
LOG=/data/local/tmp/verify_media_out.txt
: > $LOG

echo "=== 1. 模块版本 ===" >> $LOG
dumpsys package dev.loong.aicyunlock 2>/dev/null | grep -E "versionName|versionCode" | head -2 >> $LOG

echo "=== 2. Aicy 进程 ===" >> $LOG
pidof $PKG_AICY >> $LOG 2>&1

echo "=== 3. 模块注入日志（hook 是否装上）===" >> $LOG
# LSPosed 模块日志只落在 /data/adb/lspd/log/，不在 logcat
LATEST=$(ls -t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -1)
if [ -n "$LATEST" ]; then
  echo "log file: $LATEST" >> $LOG
  grep -aE "media-control|MediaControlInjector|MediaSession" "$LATEST" 2>/dev/null | tail -20 >> $LOG
else
  echo "NO LSPOSED LOG FOUND" >> $LOG
fi

echo "=== 4. 当前媒体会话 ===" >> $LOG
dumpsys media_session 2>/dev/null | grep -E "Media button session is|package=com\.(netease|tencent|cn\.kuwo)" | head -10 >> $LOG

echo "=== 5. 三个 App 的 MEDIA_BUTTON 接收器 ===" >> $LOG
for p in com.netease.cloudmusic com.tencent.qqmusic cn.kuwo.player; do
  echo "--- $p ---" >> $LOG
  dumpsys package $p 2>/dev/null | grep -B1 "android.intent.action.MEDIA_BUTTON" | grep -oE "[a-zA-Z0-9_.]+Receiver" | sort -u | head -3 >> $LOG
done

echo "=== 6. Aicy 是否已 granted MEDIA_CONTENT_CONTROL ===" >> $LOG
dumpsys package $PKG_AICY 2>/dev/null | grep "MEDIA_CONTENT_CONTROL" | head -2 >> $LOG

cat $LOG
