#!/usr/bin/env bash
# 一次跑完全部离线回归校验（不参与打包）。
# 前置：`gradlew build`（要有 build/classes/java/main 与 build/libs 的 jar）
# 用法：bash _tools/check-all.sh
set -e
cd "$(dirname "$0")/.."
PY="C:/Users/user/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
# jar 名随 mod_version 变（本脚本在 4 个工程里通用），扫目录取最新一只，
# 别写死版本号 —— 否则换个版本号重编就会校验到上一只旧 jar（或直接报文件不存在）。
JAR="$(ls -1 build/libs/smooth-escalator-*.jar 2>/dev/null | grep -v sources | sort | tail -1)"
if [ -z "$JAR" ]; then
    echo "找不到 build/libs/smooth-escalator-*.jar，先跑 gradlew build"
    exit 1
fi

echo "########## 1/4 字节码 + 资源校验（jar） ##########"
"$PY" _tools/check-port-lift.py "$JAR"

echo
echo "########## 2/4 Mixin 配置插件安全校验（禁止准备期加载类） ##########"
"$PY" _tools/check-mixin-plugin-safety.py

echo
echo "########## 3/4 MTR3/MTR4 契约 + 同一层重开门判定表/可达性 ##########"
"$PY" _tools/check-mtr3-contract.py

echo
echo "########## 4/4 离线 Brigadier 指令树校验 ##########"
./_tools/check-command-tree.sh
