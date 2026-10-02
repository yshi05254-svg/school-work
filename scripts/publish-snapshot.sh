#!/system/bin/sh
# 快照双路径发布脚本（P1 读取机制的发布侧）：
#   /data/local/tmp/ven11/snapshot.json —— 普通应用域可读（沿用）
#   /data/system/ven11/snapshot.json    —— system_server 域可读（P1 解法）
# 用法（主机侧）：adb push snapshot.json /data/local/tmp/，然后
#   adb shell su -c "sh /data/local/tmp/publish-snapshot.sh"
# 或直接在设备 root shell：sh publish-snapshot.sh [本地json路径]
set -e
SRC="${1:-/data/local/tmp/snapshot.json}"

[ -f "$SRC" ] || { echo "source not found: $SRC"; exit 1; }

# local 路径：shell 属主、644
mkdir -p /data/local/tmp/ven11
cp "$SRC" /data/local/tmp/ven11/snapshot.json
chown shell:shell /data/local/tmp/ven11/snapshot.json 2>/dev/null || true
chmod 644 /data/local/tmp/ven11/snapshot.json

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
ls -laZ /data/local/tmp/ven11/snapshot.json /data/system/ven11/snapshot.json 2>/dev/null || \
  ls -la /data/local/tmp/ven11/snapshot.json /data/system/ven11/snapshot.json
