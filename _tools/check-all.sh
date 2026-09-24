#!/usr/bin/env bash
# 一键跑完所有离线回归（都不需要开游戏）。
#
# 前置：
#   1) 先跑过一次构建（要有 build/classes/java/main）：`gradlew build --offline -x test`
#   2) _tools/runtime.cp 有效。**工程被搬过目录后这个文件会静默失效**（里面的绝对路径
#      还指向旧位置，报错却是「程序包 net.minecraft.* 不存在」）。重建方式：
#        gradlew -I _tools/dumpcp.gradle dumpRuntimeClasspath   # 取 CLASSPATH_START~END 之间那行
#
# 覆盖：
#   check-mixin-plugin-safety.py  Mixin 准备阶段不得加载类（会崩别的模组的 mixin）
#   check-psd-anchor.py           屏蔽门锚点几何 + 开关门跳变判定
#   check-psd-nbt.py              存档字段「写/读」成对 + 数值夹取
#   check-psd-tone.py             屏蔽门提示音素材（三段内置 + 两层回落 + 指令/UI/同步）
#   check-psd-align.py            屏蔽门关门提示音「结尾对齐门关上那一刻」**路 A**（门速实测 + 剪头算式）
#                                 ★ 兜底主干：default-s / 素材无两段结构 / 路 B 没排上的兜底
#   check-psd-split.py            屏蔽门关门提示音「语音播报 + 嘀嘀**整段提前播**、结尾正好落在门上」
#                                 （分界点实测 + 提前量算术 + 顺序证明 + 两条路的互斥与兜底）
#   check-psd-predict.py          ★ 提前量**状态机**逐 tick 跑一遍（算术对了不等于时序对）：
#                                 同一扇门 / **跨站换门** / 停站太短 / 停站变长 / 借来的周期偏长
#                                 —— 用户报「停站很久也只有嘀嘀」就出在这里
#                                 第 7 节：**MTR 时刻表**那一层（停站时长 → 周期，不等实测；
#                                 A=10s / B=20s / C=30s 的逐站结果与「结尾落在门上」）
#                                 第 8 节：★「改成 30 秒也没用」那三处 ——
#                                 第一道闸门（announce/splitMs）不许再静默 return、
#                                 跨门借用要喊出来、时刻表×实测交叉核对、
#                                 认站台从「到中点」改成「到中轴**线段**」（含数值对撞）
#                                 第 9 节：★★「改了停留时间还是 10000ms」取证 ——
#                                 站台清单（车站名+坐标+停留时长，「从头没改过」标出来）、
#                                 被认亲上限挡掉的最近站台也要报、
#                                 以及**直接算出**「这一站至少要设多少秒」（防死数）
#   check-psd-volume.py           【1.22】音量 1~1000（三个指令 + UI 两个框 + 底部合并同行）
#                                 + 距离淡入淡出（每 tick 现算）+ 列车内 -80% + 列表 ≥5 行，
#                                 ★ 核心：两条同步包的**读写序逐格一致**（错位不报错，只串字段）
#                                 + 打包名（archives_base_name 真的应用了 ⇒ jar 叫 mzycBetterMTR-*）
#   check-psd-broadcast.py        【1.26】到站播报 / 进站报站 = **一串门的站台广播**：
#                                 串跨度 55 格 vs 射程 16 格（症状数值化）、一串只排一条（键 = runKey）、
#                                 距离与声源取「本串里离玩家最近的那一扇」、哨兵 Long.MIN_VALUE
#                                 按 asLong 位布局复算它不可达、以及最近门选择的纯逻辑复算
#   check-psd-platform-group.py   【1.27】串身份必须优先落到 **MTR 站台**上（不能停在连通串）：
#                                 LOG5 取证（同站台 3~4 个串 / z=72 单串对照 / 缺口 9 格）、
#                                 站在中段时前后两段增益 0.0625（听觉=静音）vs 中段 0.875、
#                                 站台身份 Long.MIN_VALUE+id 与 asLong 不撞键的数值证明、
#                                 认到才缓存 + 2 秒节流重试 + 换维度作废、1.26 的机器保持原样
#   check-lift-chime.py           直梯提示音：开关/倍速/音量链路 + 同步包读写顺序配对
#   check-lift-tone.py            直梯三提示音（up/down/chime）素材：数据/指令/UI/播放端
#   check-lift-move-sound.py      直梯「准备移动」up.ogg / down.ogg 的触发判据与素材选择
#   check-chime-tiers.py          无障碍提示音分档素材（5 档 pitch）
#   check-lift-track-look.py      直梯楼层轨道外观
#   check-command-tree.sh         指令树可达性 / 补全 / 边界拒绝 / 别名同形
set -u
cd "$(dirname "$0")/.."

PY="C:/Users/user/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
if [ ! -x "$PY" ]; then PY="python"; fi

TMP="$(mktemp -d)"
rc=0

run() {
    local name="$1"; shift
    echo
    echo "############################################################"
    echo "# $name"
    echo "############################################################"
    if "$@" > "$TMP/$(echo "$name" | tr -cd 'a-zA-Z0-9').log" 2>&1; then
        echo "  ==> 通过"
    else
        echo "  ==> 失败（完整输出见下方）"
        cat "$TMP/$(echo "$name" | tr -cd 'a-zA-Z0-9').log"
        rc=1
    fi
}

run "mixin 插件安全"        "$PY" _tools/check-mixin-plugin-safety.py
run "屏蔽门锚点与跳变"      "$PY" _tools/check-psd-anchor.py
run "屏蔽门存档字段"        "$PY" _tools/check-psd-nbt.py
run "屏蔽门提示音素材"      "$PY" _tools/check-psd-tone.py
run "屏蔽门关门提示音对齐"  "$PY" _tools/check-psd-align.py
run "屏蔽门提示音顺序"      "$PY" _tools/check-psd-split.py
run "屏蔽门提前量状态机"    "$PY" _tools/check-psd-predict.py
run "屏蔽门音量与淡入淡出"  "$PY" _tools/check-psd-volume.py
run "屏蔽门站台广播"        "$PY" _tools/check-psd-broadcast.py
run "屏蔽门串身份=站台"     "$PY" _tools/check-psd-platform-group.py
run "屏蔽门1.28身份迁移与列车倍率" "$PY" _tools/check-psd-128.py
run "屏蔽门铃声/播报归属"   "$PY" _tools/check-psd-scope.py
run "屏蔽门1.31借用与列车挡" "$PY" _tools/check-psd-131.py
run "直梯提示音链路"        "$PY" _tools/check-lift-chime.py
run "直梯三提示音素材"      "$PY" _tools/check-lift-tone.py
run "直梯准备移动音"        "$PY" _tools/check-lift-move-sound.py
run "无障碍提示音分档"      "$PY" _tools/check-chime-tiers.py
run "直梯楼层轨道外观"      "$PY" _tools/check-lift-track-look.py
run "指令树"                bash _tools/check-command-tree.sh

echo
echo "############################################################"
if [ "$rc" -eq 0 ]; then
    echo "# 全部离线回归通过"
else
    echo "# 有回归失败，见上面明细"
fi
echo "############################################################"
rm -rf "$TMP"
exit "$rc"
