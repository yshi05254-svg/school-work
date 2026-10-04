#!/system/bin/sh
# 快照发布脚本（system 通道）：
#   /data/system/ven11/snapshot.json —— system_server 域可读
# 应用进程走模块 provider + 进程内缓存，不再落 /data/local/tmp（去掉调试残留）。
# 用法（主机侧）：adb push snapshot.json /data/local/tmp/，然后
#   adb shell su -c "sh /data/local/tmp/publish-snapshot.sh"
# 或直接在设备 root shell：sh publish-snapshot.sh [本地json路径]
set -e
SRC="${1:-/data/local/tmp/snapshot.json}"

[ -f "$SRC" ] || { echo "source not found: $SRC"; exit 1; }

# system 路径：目录必须属主 system（system_server uid 1000 要穿越此目录——
# root 属主 700 会导致 stat 失败报 missing，P1 真因）；文件 644，restorecon 继承标签
mkdir -p /data/system/ven11
chown system:system /data/system/ven11
chmod 700 /data/system/ven11
cp "$SRC" /data/system/ven11/snapshot.json
chown system:system /data/system/ven11/snapshot.json
chmod 644 /data/system/ven11/snapshot.json
restorecon /data/system/ven11/snapshot.json 2>/dev/null || true

echo "--- published ---"
ls -laZ /data/system/ven11/snapshot.json 2>/dev/null || \
  ls -la /data/system/ven11/snapshot.json
