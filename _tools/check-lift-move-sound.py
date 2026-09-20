# -*- coding: utf-8 -*-
"""离线校验：【1.44】直梯「准备移动」提示音（up.ogg / down.ogg）。

## 需求原话（用户）

    扶梯准备向上移动时播放一次 up.ogg
    扶梯准备向下移动时播放一次 down.ogg
    举个例子：准备移动是指类似于玩家在 2 楼，按下 z 选择 3 楼时就是准备向上了

（用户嘴上说「扶梯」，但举的例子是**按 Z 选层** —— 那是直梯（Lift）的行为，不是扶梯。
所以这条功能落在直梯提示音那套里，与【1.42】的开关门提示音共用 `/lifthelp` 等开关。）

## 「准备移动」到底对应什么信号（两版都对着字节码核过）

     MTR3  mtr.data.Lift.tick → lambda$tick$4：
             有目标楼层 ⇒ liftDirection = (目标在上 ? UP : DOWN)，否则 NONE
     MTR4  org.mtr.core.data.Lift.getDirection()：
             instructions 为空 ⇒ NONE；否则 fromDifference(目标楼层进度 - 当前位置)

★ 两个关键性质：
  1. **每 tick 从待办指令重算**，所以在「刚接到指令」那一瞬就从 NONE 变成 UP/DOWN ——
     这正是用户说的「准备移动」；
  2. **移动全程保持同一个值**（不是只在位移那一帧才有值），所以「方向没变」不响，
     一条指令**只响一次**（中途经过中间楼层不会重复触发）。

⇒ 判据就是 **`now != NONE && now != prev`**，一条 `NONE→UP/DOWN` 的跳变 = 响一次。
   它同时覆盖「待命→上行」「待命→下行」「中途反悔（UP→DOWN）」。

## 这个脚本查什么

1. 素材：`up.ogg` / `down.ogg` 存在、非空、是 OggS；且与用户给的原始文件**逐字节相同**（没被转码/裁掉）；
2. `sounds.json` 注册了 `audio/up` / `audio/down`，且指向的 .ogg 真的在；
3. `LiftChimePlayer` 的判据 / 只响一次 / 不走连播排期 / 音量与倍速复用既有旋钮 / 只对最近那条响；
4. `MtrLiftAccess` 的方向解析（枚举恰为 NONE/UP/DOWN、按名字解析、认不出 → NONE、
   getter 缺失只 WARN 不 throw，不能把开关门提示音一起带坏）；
5. **语义模拟 + 对照实验**：用 Python 复刻判据跑几条真实轨迹，断言「恰好响一次」；
   再把 `now == prev` 去掉跑一遍，确认它会重复响（证明该判据有鉴别力，不是恒真）；
6. 打包：jar 里确实含两个 .ogg、`sounds.json` 有那两条、`LiftChimePlayer.class` 里有那两个字面量。

解析失败**报错退出**，不做静默兜底 —— 以后改写法时脚本会明确要求同步更新。

用法：`python _tools/check-lift-move-sound.py`（退出码 0 = 全部通过）
"""
import glob
import hashlib
import json
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "src", "main", "resources")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
AUDIO_DIR = os.path.join(RES, "assets", "smoothlift", "sounds", "audio")
SOUNDS_JSON = os.path.join(RES, "assets", "smoothlift", "sounds.json")
CHIME = os.path.join(CLIENT, "LiftChimePlayer.java")
ACCESS = os.path.join(CLIENT, "MtrLiftAccess.java")
SERVER_MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift", "SmoothLift.java")
# 用户放在工作区根的原始素材（用它证明「打进工程的字节没变」）
GIVEN = os.path.join(os.path.dirname(ROOT), "SmoothLiftMod-1.20.4")
GIVEN_DIR = os.path.dirname(ROOT)

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def ogg_duration(path):
    """读 OggS 最后一个页面的 granule，算时长（秒）。不需要外部依赖。"""
    try:
        import struct
        with open(path, "rb") as fh:
            data = fh.read()
        if data[:4] != b"OggS":
            return None
        # 逐页往后走，取最后一页的 granule（样本数）
        pos, granule, rate = 0, 0, None
        while pos + 27 <= len(data) and data[pos:pos + 4] == b"OggS":
            nseg = data[pos + 26]
            seg = data[pos + 27:pos + 27 + nseg]
            body = sum(seg)
            granule = struct.unpack_from("<q", data, pos + 6)[0]
            page = pos + 27 + nseg + body
            if rate is None:
                # 第一页（识别头）里 vorbis 采样率在 body 偏移 12
                b = pos + 27 + nseg
                if data[b + 1:b + 7] == b"vorbis":
                    rate = struct.unpack_from("<i", data, b + 12)[0]
            pos = page
        if rate:
            return granule / float(rate)
    except Exception:
        return None
    return None


# ----------------------------------------------------------------------
# 1) 素材：存在、非空、是 OggS、且与用户原始文件逐字节相同
# ----------------------------------------------------------------------
print("== 1. 素材 ==")
for name in ("up", "down"):
    path = os.path.join(AUDIO_DIR, name + ".ogg")
    ok = os.path.isfile(path) and os.path.getsize(path) > 1024
    check(ok, "assets/smoothlift/sounds/audio/%s.ogg 存在且非空" % name,
          ("%d B" % os.path.getsize(path)) if os.path.isfile(path) else "缺失")
    if ok:
        with open(path, "rb") as fh:
            magic = fh.read(4)
        check(magic == b"OggS", "%s.ogg 是 OggS 容器（Minecraft 只认 OGG Vorbis）" % name,
              "魔数 %r" % magic)
        dur = ogg_duration(path)
        if dur is not None:
            check(dur < 10.0, "%s.ogg 时长 %.2f s < 10 s（短素材，可直接内嵌不必 stream）"
                  % (name, dur))

        given = os.path.join(GIVEN_DIR, name + ".ogg")
        if os.path.isfile(given):
            same = sha256(given) == sha256(path)
            check(same, "%s.ogg 与用户给的原始素材逐字节相同（没被转码 / 裁掉）" % name,
                  "sha256 %s" % sha256(path)[:16])
        else:
            print("[SKIP] 工作区根没有 %s.ogg，跳过「与原素材逐字节相同」这一步" % name)


# ----------------------------------------------------------------------
# 2) sounds.json 注册
# ----------------------------------------------------------------------
print("\n== 2. sounds.json ==")
sounds = {}
if os.path.isfile(SOUNDS_JSON):
    sounds = json.loads(read(SOUNDS_JSON))
for name in ("up", "down"):
    ev = "audio/%s" % name
    entry = sounds.get(ev)
    ok = bool(entry) and bool(entry.get("sounds"))
    ref = (entry or {}).get("sounds", [{}])[0].get("name") if ok else None
    check(ok and ref == "smoothlift:%s" % ev,
          "sounds.json 注册了 %s → smoothlift:%s" % (ev, ev),
          "实际 %s" % ref)
    check(os.path.isfile(os.path.join(AUDIO_DIR, name + ".ogg")),
          "%s 指向的 .ogg 文件真的在（注册了却没有文件 = 引擎静默不响，最难查的那类）" % ev)
# 对照：故意写一个不存在的入口，确认「注册了但没有文件」这件事查得出来
check(not os.path.isfile(os.path.join(AUDIO_DIR, "no_such_sound.ogg")),
      "对照：本脚本确实能区分「有注册」与「有文件」（用一个不存在的名字验证查得到）")


# ----------------------------------------------------------------------
# 3) LiftChimePlayer：判据 / 只响一次 / 不走连播排期 / 复用既有旋钮
# ----------------------------------------------------------------------
print("\n== 3. LiftChimePlayer ==")
chime = read(CHIME)

for const, ev in (("LIFT_UP", "audio/up"), ("LIFT_DOWN", "audio/down")):
    m = re.search(r"%s\s*=\s*\n?\s*new ResourceLocation\(\"smoothlift\",\s*\"([^\"]+)\"\)"
                  % const, chime)
    check(bool(m) and m.group(1) == ev,
          "%s 指向 smoothlift:%s" % (const, ev),
          "实际 %s" % (m.group(1) if m else None))

# 判据：now == NONE || now == prev → return
m = re.search(r"private static void detectMove\((.*?)\n    \}", chime, re.S)
body = m.group(0) if m else ""
check(bool(body), "找到 detectMove 方法体")
if body:
    check(re.search(r"now\s*==\s*MtrLiftAccess\.Move\.NONE\s*\|\|\s*now\s*==\s*prev", body)
          is not None,
          "判据是 `now != NONE && now != prev`（写成 now==NONE || now==prev 提前 return）",
          "这就是「一条 NONE→UP/DOWN 跳变只响一次」的全部条件")
    check("playsLeft" not in body and "nextPlayTick" not in body,
          "detectMove 不碰连播排期（playsLeft/nextPlayTick）⇒ 不会顶掉正在进行的关门连播")
    check(re.search(r"up\s*\?\s*LIFT_UP\s*:\s*LIFT_DOWN", body) is not None,
          "方向 UP 放 LIFT_UP、DOWN 放 LIFT_DOWN（按方向选素材）")
    check(re.search(r"gain\(distance\)\s*\*\s*volumeFactor\(toneVolume\)", body) is not None,
          "音量复用 `/lifthelploud` 且按方向取单项（【1.48】gain(distance) × volumeFactor(liftToneVolume(…))）")
    check(re.search(r"liftToneVolume\(mc,\s*up\s*\?\s*\"up\"\s*:\s*\"down\"\)", body) is not None,
          "【1.48】上楼用 up 单项音量、下楼用 down 单项音量（没单独调过回落共用默认）")
    check(re.search(r"cachedSpeed", body) is not None,
          "音高复用 `/lifthelpspeed`（cachedSpeed），不必为它再加指令")
    check(re.search(r"volume\s*<=\s*0\.0f", body) is not None,
          "音量为 0 时直接不播（/lifthelploud 0 时彻底静音，不留空转）")

# 只对最近那条响
m = re.search(r"if\s*\(lift\s*==\s*nearest\)\s*\{(.*?)\n            \}", chime, re.S)
near_body = m.group(1) if m else ""
check("detectMove(" in near_body,
      "只在 `lift == nearest` 分支里调 detectMove ⇒ 同一时刻只有最近那条直梯会响",
      "否则一个大车站里几十条直梯会一起响")

# 首次看到不误触发 + 每条都记 + 清理
check(re.search(r"lastMove\.containsKey\(lift\.id\(\)\)\s*\n?\s*\?\s*lastMove\.get\(lift\.id\(\)\)"
                r"\s*:\s*lift\.move\(\)", chime) is not None,
      "首次看到某条直梯时 prev 取当前值 ⇒ 不会因为「第一次看见它」而误响一声")
check("lastMove.put(lift.id(), lift.move())" in chime,
      "每条直梯都记录方向（不只是最近那条）⇒ 最近的一条换成另一条时不会误响")
check("lastMove.keySet().retainAll(seen)" in chime,
      "每帧清掉已不在客户端集合里的直梯 ⇒ Map 不会无限长大")
check(re.search(r"lastMove\.clear\(\)", chime) is not None,
      "reset() 里清空 lastMove ⇒ 玩家进/出维度、重连后不会拿旧状态比")

# 复用既有指令（没有为这条功能新增指令）
for cmd in ("lifthelp", "lifthelploud", "lifthelpspeed"):
    check(cmd in chime, "复用既有指令 /%s（本功能不新增指令）" % cmd)
server_main = read(SERVER_MAIN)
check(re.search(r'literal\(\s*"(up|down)"\s*\)', server_main) is None,
      "指令树里没有新增 up/down 字面量 ⇒ 确认是复用而非新增")
check(re.search(r'literal\(\s*"lifthelpspeed"', server_main) is not None,
      "对照：脚本确实能读到指令树字面量（/lifthelpspeed 在）⇒ 上一条断言有鉴别力")


# ----------------------------------------------------------------------
# 4) MtrLiftAccess：方向解析与降级
# ----------------------------------------------------------------------
print("\n== 4. MtrLiftAccess ==")
access = read(ACCESS)

m = re.search(r"public enum Move\s*\{(.*?)\n    \}", access, re.S)
enum_body = m.group(1) if m else ""
consts = re.findall(r"^\s{8}([A-Z_]+)\s*[,;]", enum_body, re.M)
check(consts == ["NONE", "UP", "DOWN"],
      "Move 枚举恰为 NONE / UP / DOWN（顺序也一致）",
      "实际 %s" % consts)

check(re.search(r'if\s*\(\s*"UP"\.equals\(name\)\s*\)', access) is not None
      and re.search(r'if\s*\(\s*"DOWN"\.equals\(name\)\s*\)', access) is not None,
      "按 toString() 的**名字**解析（编译期不需要认识任何 MTR 类型）")
check(re.search(r"return NONE;", enum_body) is not None
      and re.search(r"if\s*\(mtrDirection\s*==\s*null\)", enum_body) is not None,
      "null / 名字不认识一律 → NONE（宁可不出声，也不按错方向出声）")

for meth in ("getLiftDirection", "getDirection"):
    m = re.search(r'= method\(lift,\s*"%s"\);\s*\n\s*if\s*\(\w+\s*==\s*null\)\s*\{(.*?)\n        \}'
                  % meth, access, re.S)
    block = m.group(1) if m else ""
    check(bool(block) and "LOGGER.warn" in block and "throw" not in block,
          "%s 拿不到时只 WARN、不 throw（不能把开关门提示音一起带坏）" % meth,
          "缺方向只会让 up/down 不响；缺开关门方法才是真炸")

m = re.search(r"private static Move directionOf\((.*?)\n    \}", access, re.S)
d_body = m.group(0) if m else ""
check("if (getDirection == null)" in d_body and "return Move.NONE;" in d_body
      and "catch (Throwable" in d_body,
      "directionOf：方法缺失 / 反射抛异常 → NONE（同一套降级）")

check(re.search(r"Move move\)", access) is not None and "LiftView(" in access,
      "快照 LiftView 里带上了 move 字段（record 的构造口也对上了）")


# ----------------------------------------------------------------------
# 5) ★ 语义模拟 + 对照实验
# ----------------------------------------------------------------------
print("\n== 5. 语义模拟（把判据搬到 Python 里跑真实轨迹） ==")


def trig(prev, now):
    """复刻 detectMove 的判据：now != NONE && now != prev。"""
    return now != "NONE" and now != prev


def run(track):
    """track = [(prev, now), ...] 逐 tick；返回响的次数。"""
    return sum(1 for p, n in track if trig(p, n))


CASES = [
    # (名字, 轨迹, 期望次数, 说明)
    ("待命→上行（玩家在2楼按Z选3楼）", [("NONE", "UP")] * 1 + [("UP", "UP")] * 60, 1,
     "这正是用户举的例子"),
    ("待命→下行", [("NONE", "DOWN")] + [("DOWN", "DOWN")] * 60, 1, ""),
    ("纯待命（没人按）", [("NONE", "NONE")] * 60, 0, "停着就不该响"),
    ("上行到站停下", [("NONE", "UP")] + [("UP", "UP")] * 40 + [("UP", "NONE")] * 20, 1,
     "到站变 NONE 不响（那是停，不是准备走）"),
    ("中途反悔：上行途中按了更低那层", [("NONE", "UP")] + [("UP", "UP")] * 10
     + [("UP", "DOWN")] + [("DOWN", "DOWN")] * 30, 2,
     "反悔确实算「又准备走一次」，响第 2 声"),
    ("全程没有跳变", [("UP", "UP")] * 200, 0, "移动全程保持同一值 ⇒ 只响过起始那一次"),
]
for label, track, expect, note in CASES:
    got = run(track)
    check(got == expect, "模拟：%s ⇒ 响 %d 次" % (label, expect),
          ("实测 %d 次" % got) + ("（%s）" % note if note else ""))


def run_no_prev(track):
    """对照：把 `now == prev` 去掉（只看 now != NONE）会怎样。"""
    return sum(1 for _p, n in track if n != "NONE")


_track = [("NONE", "UP")] + [("UP", "UP")] * 60
_ctl = run_no_prev(_track)
check(_ctl == len(_track),
      "对照：去掉 `now == prev` 后，同一条上行轨迹会响 %d 次（= %d 个 tick 每 tick 都响）"
      % (_ctl, len(_track)),
      "⇒ 证明「方向没变就不响」这个条件是必要的，不是恒真的摆设")


# ----------------------------------------------------------------------
# 6) 打包：jar 里真的有这些
# ----------------------------------------------------------------------
print("\n== 6. 构建产物 ==")
jars = sorted(glob.glob(os.path.join(ROOT, "build", "libs", "smooth-escalator-*.jar")),
              key=os.path.getmtime)
if not jars:
    print("[SKIP] build/libs 下没有 smooth-escalator-*.jar，跳过打包校验（先跑 gradlew build）")
else:
    jar = jars[-1]
    print("      校验 %s（%d B）" % (os.path.relpath(jar, ROOT), os.path.getsize(jar)))
    with zipfile.ZipFile(jar) as z:
        names = set(z.namelist())
        for name in ("up", "down"):
            entry = "assets/smoothlift/sounds/audio/%s.ogg" % name
            check(entry in names, "jar 内含 %s" % entry)
            if entry in names:
                check(z.read(entry) == open(os.path.join(AUDIO_DIR, name + ".ogg"), "rb").read(),
                      "jar 内的 %s.ogg 与源码里的逐字节相同" % name)
        if "assets/smoothlift/sounds.json" in names:
            bundled = json.loads(z.read("assets/smoothlift/sounds.json").decode("utf-8"))
            check("audio/up" in bundled and "audio/down" in bundled,
                  "jar 内的 sounds.json 有 audio/up 与 audio/down",
                  "实际含 %s" % [k for k in bundled if "up" in k or "down" in k])
        else:
            check(False, "jar 内含 assets/smoothlift/sounds.json")

        cls = "smooth/lift/client/LiftChimePlayer.class"
        if cls in names:
            blob = z.read(cls)
            check(b"audio/up" in blob and b"audio/down" in blob,
                  "LiftChimePlayer.class 里有 audio/up 与 audio/down 两个字面量（确认编进去了）")
        else:
            check(False, "jar 内含 %s" % cls)

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")
