#!/system/bin/sh
# ven11: 常驻 logcat，post-fs-data 阶段启动（早于 zygote/system_server），
# 完整记录含框架侧装钩在内的开机窗口；16M×3 轮转防写爆 /data。
LOGDIR=/data/local/tmp/ven11/log
mkdir -p "$LOGDIR"
chmod 777 "$LOGDIR"
if pgrep -f "ven11/log/boot.log" >/dev/null 2>&1; then
  exit 0
fi
/system/bin/logcat -v time -f "$LOGDIR/boot.log" -r 16384 -n 3 >/dev/null 2>&1 &
exit 0
