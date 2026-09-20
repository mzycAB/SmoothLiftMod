# -*- coding: utf-8 -*-
"""离线校验：MTR 4.1.x（1.21.1 / 1.21.4）的楼层轨道碰撞箱收窄 mixin 是对的。

## 背景（这次 bug 的来龙去脉，别再踩）

1.44 已经给 MTR 4.0.x 做过一次「模型窄柱化 ⇒ 形状必须跟着窄」的修复
（Mtr4LiftTrackFloorShapeMixin，目标 org.mtr.mod.block.BlockLiftTrackFloor）。
MTR 4.1（4.1.0-beta.2 是 1.21.1 / 1.21.4 唯一对应的 fabric 版本）把方块类挪到了
org.mtr.block.BlockLiftTrackFloor（顺带弃用了 org.mtr.mapping 封装、方向参数换成原版
net.minecraft.core.Direction）。插件门禁「目标类在不在」对新包名为 false ⇒
旧 mixin 被**静默跳过**（编译、jar 校验全过），碰撞箱回到全宽 (0,0,0,16,16,1) 薄板，
与窄柱模型不一致 —— 用户 1.21.x 上报「没有贴图的地方变成类似 x 光方块那样透明的了」。
本脚本守住新增的 Mtr4LiftTrackFloorShapeMixinV2 的每一处细节。

## 查什么

1. V2 mixin 源文件存在、targets == org.mtr.block.BlockLiftTrackFloor、带 @Pseudo、
   remap=false、method == getShape（4.1 覆写的是原版 Block.getShape，intermediary
   method_9530 → mojmap getShape）；
2. 只改 minX(index 0)/maxX(index 3) 两个实参，替换值 == 6.0 / 10.0（MTR3 窄柱边界）；
3. SHAPE_CALL 描述符与真实 4.1 jar 字节码里的调用**逐字对撞**（mojmap 类名
   ↔ intermediary 类名按位置对应：Direction↔class_2350、VoxelShape↔class_265）；
4. 真实 4.1 字节码里的六个 double == (0,0,0,16,16,1)（隐形墙的来源）；
5. 插件按类名分派（V2 先判，旧名后判）、mixins.json 注册、插件无 Class.forName。

用法：`python _tools/check-lift-track-look-v41.py`
"""
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIXIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                     "Mtr4LiftTrackFloorShapeMixinV2.java")
MIXINS_JSON = os.path.join(ROOT, "src", "main", "resources", "smoothlift.mtr.mixins.json")
PLUGIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr",
                      "Mtr3LiftMixinPlugin.java")
MTR3_SHAPE = [6.0, 0.0, 0.0, 10.0, 16.0, 1.0]
MTR4_SHAPE = [0.0, 0.0, 0.0, 16.0, 16.0, 1.0]

# 4.1 字节码里的 intermediary 名字（从 4.1.0-beta.2 的真实字节码读出，1.21.1 / 1.21.4 相同）
SHAPE_METHOD_INTER = ("method_9530(Lnet/minecraft/class_2680;"
                      "Lnet/minecraft/class_1922;"
                      "Lnet/minecraft/class_2338;"
                      "Lnet/minecraft/class_3726;)"
                      "Lnet/minecraft/class_265;")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def find_41_jar():
    """在工作区里按内容认 MTR 4.1+ 的 jar（有 org/mtr/block/BlockLiftTrackFloor.class）。"""
    for d in (os.path.dirname(ROOT), ROOT):
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.lower().endswith(".jar"):
                continue
            path = os.path.join(d, fn)
            try:
                with zipfile.ZipFile(path) as z:
                    names = set(z.namelist())
            except Exception:
                continue
            if "org/mtr/block/BlockLiftTrackFloor.class" in names:
                return path
    return None


def find_javap():
    home = os.path.expanduser("~")
    for jdk in ("eclipse_adoptium-17-amd64-windows.2", "jdk-21.0.11.10-hotspot"):
        for exe in ("javap", "javap.exe"):
            p = os.path.join(home, ".gradle", "jdks", jdk, "bin", exe)
            if os.path.isfile(p):
                return p
    return shutil.which("javap")


def javap_method(javap, jar, cls, method):
    """从真实 4.1 字节码读出 (六个 double 常量, 规范化后的调用描述符串)。"""
    try:
        out = subprocess.run([javap, "-c", "-p", "-cp", jar, cls],
                             capture_output=True, text=True, errors="replace").stdout
    except Exception:
        return None, None
    vals, desc, started = [], None, False
    for ln in out.splitlines():
        if not started:
            if method in ln and "(" in ln and ln.strip().endswith(";"):
                started = True
            continue
        if "getVoxelShapeByDirection" in ln:
            mm = re.search(r"//\s*(?:Interface)?Method\s+([\w/$]+)\.([\w$<>]+):(\(.*?\))(\S*)", ln)
            if mm:
                desc = "L%s;%s%s%s" % (mm.group(1), mm.group(2), mm.group(3), mm.group(4))
            break
        mm = re.search(r"^\s*\d+:\s*([a-z0-9_]+)", ln)
        if not mm:
            continue
        op = mm.group(1)
        if op == "dconst_0":
            vals.append(0.0)
        elif op == "dconst_1":
            vals.append(1.0)
        elif op == "ldc2_w":
            d = re.search(r"//\s*double\s+([0-9.]+)d", ln)
            if d:
                vals.append(float(d.group(1)))
    return vals, desc


# ----------------------------------------------------------------------
# 1) 源文件与基本注解
# ----------------------------------------------------------------------
check(os.path.isfile(MIXIN), "【1.49】4.1 碰撞箱 mixin 源文件存在",
      os.path.relpath(MIXIN, ROOT))

src = ""
if os.path.isfile(MIXIN):
    with open(MIXIN, encoding="utf-8") as fh:
        src = fh.read()

m_target = re.search(r'@Mixin\(\s*targets\s*=\s*"([^"]+)"', src)
check(bool(m_target) and m_target.group(1) == "org.mtr.block.BlockLiftTrackFloor",
      "【1.49】mixin 目标是 MTR 4.1 的楼层轨道方块类（org.mtr.block.BlockLiftTrackFloor）",
      "实际 %s" % (m_target.group(1) if m_target else None))

check("@Pseudo" in src, "【1.49】带 @Pseudo（MTR 不是编译依赖）")
check("remap = false" in src, "【1.49】@At/@ModifyArg 带 remap = false")
check("SHAPE_METHOD" in src and "method_9530" in src,
      "【1.49】目标方法是运行期 method_9530（4.1 覆写的 Block.getShape 的 intermediary 名；"
      "★ 不能写 mojmap 的 getShape —— 发布 jar 运行在 intermediary 下，写错 = 静默失效）")


def parse_modify_args(src, consts):
    """从 mixin 源码解析 (method引用, target引用, index, 替换值) —— 常量引用也支持。"""
    out = []
    for chunk in src.split("@ModifyArg")[1:]:
        m = re.search(r"method\s*=\s*([A-Z_]+|\"[^\"]*\")", chunk)
        t = re.search(r"target\s*=\s*([A-Z_]+)", chunk)
        i = re.search(r"index\s*=\s*(\d+)", chunk)
        v = re.search(r"return\s+([0-9.]+)f?\s*;", chunk)
        if not (m and i and v):
            continue
        mref = m.group(1)
        method = consts.get(mref.strip('"'), mref.strip('"'))
        out.append((method, t.group(1) if t else None, int(i.group(1)), float(v.group(1))))
    return out


_m = re.search(r'SHAPE_CALL\s*=\s*(.*?");', src, re.S)
call_str = "".join(re.findall(r'"([^"]*)"', _m.group(1))) if _m else ""
check(call_str.startswith("Lorg/mtr/block/IBlock;getVoxelShapeByDirection(DDDDDD"),
      "【1.49】SHAPE_CALL 描述符形状正确（六个 double + 接口静态方法）", call_str)
check("Lnet/minecraft/class_2350;" in call_str and "Lnet/minecraft/class_265;" in call_str,
      "【1.49】SHAPE_CALL 用 intermediary 类名（class_2350=Direction、class_265=VoxelShape；"
      "★ 不能写 mojmap —— 发布 jar 运行在 intermediary 下，remap=false 原样进 jar）",
      call_str)

_mm = re.search(r'SHAPE_METHOD\s*=\s*(.*?");', src, re.S)
method_str = "".join(re.findall(r'"([^"]*)"', _mm.group(1))) if _mm else ""
check(method_str == SHAPE_METHOD_INTER,
      "【1.49】SHAPE_METHOD == 运行期方法 method_9530 的完整签名（intermediary 描述符）",
      method_str)
check('method = SHAPE_METHOD' in src,
      "【1.49】@ModifyArg 的 method 引用 SHAPE_METHOD 常量（便于与真实字节码对撞）")

args = parse_modify_args(src, {"SHAPE_METHOD": method_str, "SHAPE_CALL": call_str})
check(len(args) == 2 and {a[2] for a in args} == {0, 3},
      "【1.49】只改 minX(index 0)/maxX(index 3) 两个实参，其余四个原样保留",
      "实际 %s" % [(a[2], a[3]) for a in args])
check(all(a[0] == method_str for a in args),
      "【1.49】两个 @ModifyArg 的 method 都是 SHAPE_METHOD", "实际 %s" % [a[0] for a in args])
check(all(a[1] == "SHAPE_CALL" for a in args),
      "【1.49】两个 @ModifyArg 的 target 都指向 SHAPE_CALL 常量", "实际 %s" % [a[1] for a in args])

# ----------------------------------------------------------------------
# 2) 插件门禁 + mixins.json 注册
# ----------------------------------------------------------------------
plug = ""
if os.path.isfile(PLUGIN):
    with open(PLUGIN, encoding="utf-8") as fh:
        plug = fh.read()
check("mtr4TrackFloorV2Present" in plug and 'classPresent("org.mtr.block.BlockLiftTrackFloor")' in plug,
      "【1.49】插件按新包名目标类是否存在单独门禁 V2 mixin")
# 分派顺序：V2 必须先判（V2 也以旧名结尾）
idx_v2 = plug.find("Mtr4LiftTrackFloorShapeMixinV2")
idx_v1 = plug.find('endsWith("Mtr4LiftTrackFloorShapeMixin")')
check(idx_v2 != -1 and idx_v1 != -1 and idx_v2 < idx_v1,
      "【1.49】shouldApplyMixin 里 V2 判断在旧名判断**之前**（V2 也以旧名结尾，顺序反了会误判）",
      "V2 位置 %s，旧名位置 %s" % (idx_v2, idx_v1))

mj = {}
if os.path.isfile(MIXINS_JSON):
    with open(MIXINS_JSON, encoding="utf-8") as fh:
        mj = json.load(fh)
_mixins = mj.get("mixins") or []
check("Mtr4LiftTrackFloorShapeMixinV2" in _mixins,
      "【1.49】mixins.json 注册了 V2 mixin", "实际 %s" % _mixins)
_MIXIN_DIR = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "mixin", "mtr")
_missing = [m for m in _mixins if not os.path.isfile(os.path.join(_MIXIN_DIR, m + ".java"))]
check(not _missing, "mixins.json 里注册的每条 mixin 都有对应源文件", "缺失 %s" % _missing)

_plug_code = re.sub(r"/\*.*?\*/", "", plug, flags=re.S)
_plug_code = re.sub(r"//[^\n]*", "", _plug_code)
check("Class.forName" not in _plug_code and ".loadClass(" not in _plug_code,
      "【1.49】插件代码里没有 Class.forName / loadClass（Mixin 准备阶段铁律）")

# ----------------------------------------------------------------------
# 3) 与真实 4.1 jar 对撞
# ----------------------------------------------------------------------
print()
jar41 = find_41_jar()
if not jar41:
    print("[SKIP] 工作区里没找到 MTR 4.1+ jar（需要含 org/mtr/block/BlockLiftTrackFloor.class），"
          "跳过字节码对撞")
else:
    javap = find_javap()
    if not javap:
        print("[SKIP] 缺 javap，跳过字节码对撞")
    else:
        v4, d4 = javap_method(javap, jar41, "org.mtr.block.BlockLiftTrackFloor", "method_9530")
        check(d4 is not None, "能从 %s 读到 getVoxelShapeByDirection 调用" % os.path.basename(jar41),
              "真实描述符 %s" % d4)
        if d4:
            check(call_str == d4,
                  "【1.49】mixin 的 SHAPE_CALL == 真实 4.1 字节码（逐字相同，改错一个字母就查得出来）",
                  "mixin %s\n          真实 %s" % (call_str, d4))
        check(v4 == MTR4_SHAPE,
              "【1.49】真实 4.1 字节码形状 == 全宽薄板 (0,0,0,16,16,1) ⇒ 隐形墙来源确认",
              "实际 %s" % v4)
        by_index = {a[2]: a[3] for a in args}
        check(by_index.get(0) == MTR3_SHAPE[0] and by_index.get(3) == MTR3_SHAPE[3],
              "【1.49】mixin 替换值 == MTR3 窄柱的 minX/maxX（6.0 / 10.0）",
              "mixin index0=%s index3=%s；期望 %s / %s"
              % (by_index.get(0), by_index.get(3), MTR3_SHAPE[0], MTR3_SHAPE[3]))
        # 方法名也逐字校验（method_9530 来自 javap 的方法声明行）
        check(method_str.startswith("method_9530("),
              "【1.49】SHAPE_METHOD 的方法名 == method_9530（运行期名，不能是 mojmap 的 getShape）",
              method_str)

# ----------------------------------------------------------------------
# 4) ★ 发布 jar 里的注解字符串必须保持 intermediary（防 remap 配置被改坏）
#    2026-09-20 实际踩过：写 mojmap + remap=false 或只靠 refmap 都会让运行期匹配不上、
#    mixin 静默跳过 —— 用户看到「还是透」。这里直接从发布 jar 的常量池读字符串断言。
# ----------------------------------------------------------------------
print()
jar_out = None
for d in (ROOT, os.path.join(ROOT, "build", "libs")):
    if not os.path.isdir(d):
        continue
    for fn in sorted(os.listdir(d)):
        if fn.startswith("smooth-escalator-") and fn.endswith(".jar"):
            jar_out = os.path.join(d, fn)
if not jar_out:
    print("[SKIP] 没找到发布 jar（先跑 gradlew build），跳过『发布 jar 字符串』校验")
else:
    try:
        with zipfile.ZipFile(jar_out) as z:
            data = z.read("smooth/lift/mixin/mtr/Mtr4LiftTrackFloorShapeMixinV2.class").decode("latin-1")
        check("method_9530" in data and "class_2350" in data and "class_2680" in data,
              "【1.49】发布 jar 里 V2 注解字符串是 intermediary（method_9530 / class_2350）",
              os.path.basename(jar_out))
        check("getShape" not in data and "net/minecraft/core/Direction" not in data
              and "world/phys/shapes/VoxelShape" not in data,
              "【1.49】发布 jar 里 V2 注解字符串**没有** mojmap 名残留"
              "（有残留 = remap 配置被改坏，运行期必失配）")
    except KeyError:
        print("[SKIP] 发布 jar 里没有 V2 mixin 类（没重新 build？）")

if FAILS:
    print("== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("== 全部通过 ==")
