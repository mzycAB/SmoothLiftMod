# -*- coding: utf-8 -*-
"""离线校验：【1.45】石斧右键直梯楼层轨道、三列表换直梯提示音（up / down / chime）。

## 需求（用户原话归纳）

    石斧右键直梯楼层轨道可以更换直梯无障碍提示音（上楼提示音、下楼提示音、开关门提示音），
    分别 3 个列表；导入的 ogg 放在 smoothlift_audio 文件夹里（复用扶梯那套导入机制）。

## 设计要点（脚本要钉住的四条不变量）

1. **「哪条直梯」没有稳定 ID**：直梯 ID 跨重启会变，所以用「楼层轨道所在竖井的那一列
   (X, Z)」当身份 —— 一条直梯的所有楼层轨道共享 X/Z、只有 Y 不同 ⇒ key = `BlockPos.asLong(x, 0, z)`。
   石斧右键**任意**一层的楼层轨道都定位到同一个 key。
2. **三列表各自独立**：`up`（准备向上）/ `down`（准备向下）/ `chime`（开关门连播）。
   取值三种语义：`default`（内置素材）/ `off`（这条不播）/ 音频库文件名（从 smoothlift_audio 导入）。
3. **「待导入」要真的先导入**：列表里 smoothlift_audio 文件夹中的文件还没进音频库，
   点它必须走导入通道（IMPORT_FOLDER_LIFT_TONE_CHANNEL），点已入库的文件才走设置通道
   （SET_LIFT_TONE_CHANNEL）—— 不然服务端会以「音频不存在」拒绝。
4. **播放端按竖井列查素材**：LiftChimePlayer 播开关门（chime）与准备移动（up/down）时，
   用「最近直梯的位置 → 竖井列 key」去查客户端镜像；`default`→内置事件、`off`→静默跳过、
   其它→`injectAudio` 自定义分支。删除音频时引用它的那一项要退化成默认（removeAudio 里处理）。

用法：`python _tools/check-lift-tone.py`（退出码 0 = 全部通过）
"""
import os
import re
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "src", "main", "java", "smooth", "lift")
CLIENT = os.path.join(ROOT, "src", "client", "java", "smooth", "lift", "client")
DATA = os.path.join(MAIN, "EscalatorSpeedData.java")
MGR = os.path.join(MAIN, "EscalatorSpeedManager.java")
SL = os.path.join(MAIN, "SmoothLift.java")
SLC = os.path.join(CLIENT, "SmoothLiftClient.java")
SCREEN = os.path.join(CLIENT, "LiftToneSetupScreen.java")
CHIME = os.path.join(CLIENT, "LiftChimePlayer.java")
JAR = os.path.join(ROOT, "build", "libs", "smooth-escalator-1.12.1204.jar")

FAILS = []


def check(ok, label, detail=""):
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", label, ("  -- " + detail) if detail else ""))
    if not ok:
        FAILS.append(label)
    return ok


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def join(src, *subs):
    """把「同一串名字被 Java 拆成多段字符串拼接」还原成真实值（如三个通道名）。"""
    out = []
    for sub in subs:
        m = re.search(sub + r"\s*=\s*new ResourceLocation\(\"smoothlift\",\s*\"([^\"]+)\"\)", src)
        out.append(m.group(1) if m else None)
    return out


print("== 1. 通道（客户端/服务端各一个收发点） ==")
main = read(SL)
client = read(SLC)
mgr = read(MGR)
data = read(DATA)

set_chan, import_chan, sync_chan = join(main, r"SET_LIFT_TONE_CHANNEL", r"IMPORT_FOLDER_LIFT_TONE_CHANNEL",
                                        r"LIFT_TONE_SYNC_CHANNEL")
check(all([set_chan, import_chan, sync_chan]),
      "SmoothLift 定义了三个【1.45】通道",
      "set=%s import=%s sync=%s" % (set_chan, import_chan, sync_chan))
check("SET_LIFT_TONE_CHANNEL" in main and "IMPORT_FOLDER_LIFT_TONE_CHANNEL" in main
      and "LIFT_TONE_SYNC_CHANNEL" in main,
      "服务端注册了三个接收器（送出者都能收到处理）")
check("LIFT_TONE_SYNC_CHANNEL" in client,
      "客户端注册了 LIFT_TONE_SYNC 接收器（镜像会更新）")
check("syncLiftToneToAll(server)" in main and "sendLiftToneSyncTo(player, level)" in main,
      "JOIN / REQUEST_SYNC 两处都会推送直梯提示音表（进世界就能拿到）")

print("\n== 2. 数据层（竖井列 key + 三字段 + 三种语义） ==")
check("liftToneAudio" in data and "LiftToneAudio(" in data,
      "SavedData 里加了竖井列 → 三音频 id 的表")
check("LIFT_TONE_DEFAULT" in data and re.search(r'LIFT_TONE_DEFAULT\s*=\s*"default"', data) is not None
      and "LIFT_TONE_OFF" in data and re.search(r'LIFT_TONE_OFF\s*=\s*"off"', data) is not None,
      "default / off 两个哨兵值定义在数据层（与扶梯 HELP_AUDIO 同风格）")
check('tag.getList("liftToneAudio", 10)' in data and 'tag.put("liftToneAudio", toneList)' in data,
      "NBT 读写成对存在（旧存档缺表 → 空表 = 全部默认素材）")
check("record LiftToneAudio(String up, String down, String chime)" in data,
      "三项是 record：字段 final ⇒ 删除音频退默认时要整条替换（脚本第 5 段接着验）")

print("\n== 3. 服务端读写（校验 + 全默认即删除） ==")
check("setServerLiftTone" in mgr and "getServerLiftTone" in mgr and "getClientLiftTone" in mgr,
      "服务端读写 / 客户端读取三个入口都在 Manager")
m = re.search(r"public static boolean setServerLiftTone\((.*?)\n    }", mgr, re.S)
set_body = m.group(0) if m else ""
check(bool(set_body) and "audioLibrary.containsKey" in set_body,
      "写入前校验 audioId 必须 ∈ {default, off, 音频库}（否则拒绝）",
      "没有这道校验就能写进任意文件名 → 播放端静默不响还说不清原因")
check("liftToneAudio.remove(key)" in set_body or "LiftToneAudio.NONE" in set_body,
      "三项全默认 = 等于没设置 ⇒ 直接删记录（表只留有价值的行）")

print("\n== 4. 石斧右键（服务端+客户端拦默认交互，UI 三列表） ==")
check("isLiftTrackFloor" in main and "lift_track_floor" in main,
      "主类提供 isLiftTrackFloor（注册名前缀判，不依赖 MTR 编译期）")
check("isLiftTrackFloor(world.getBlockState" in main and "InteractionResult.FAIL" in main,
      "服务端 UseBlockCallback 拦截石斧右键楼层轨道（防 MTR 默认 onUse）")
check("new LiftToneSetupScreen(pos)" in client and "isLiftTrackFloor(world.getBlockState" in client,
      "客户端石斧右键楼层轨道 → 打开 LiftToneSetupScreen")
screen = read(SCREEN)
for which, title in (("up", "上楼提示音"), ("down", "下楼提示音"), ("chime", "开关门提示音")):
    check(('"%s"' % which) in read(CHIME) or ('"%s"' % which) in screen,
          "三列表 %s（%s）存在" % (which, title))
check("IMPORT_FOLDER_LIFT_TONE_CHANNEL" in screen and "SET_LIFT_TONE_CHANNEL" in screen,
      "UI 里既有导入通道又有设置通道（待导入行走导入，已入库行走设置）")
check("liftToneKey" in mgr and "getX()" in screen and "getZ()" in screen,
      "UI 构造时用右键格的竖井列 key（同一条直梯任意层同 key）")

print("\n== 5. 播放端（按竖井列查素材；default/off/custom 三分支） ==")
chime = read(CHIME)
m = re.search(r"private static String liftToneCustomId\(Minecraft mc, MtrLiftAccess.LiftView lift, String which\)"
              r"(.*?)\n    \}", chime, re.S)
body = m.group(1) if m else ""
check(bool(body), "找到 liftToneCustomId（播放端查素材的统一入口）")
if body:
    check("liftToneKeyNear" in body or "liftToneKey(" in body,
          "按最近直梯位置算竖井列 key")
    check("LIFT_TONE_OFF" in body and "LIFT_TONE_DEFAULT" in body,
          "off / default 两个哨兵都处理了（不播 / 内置）")
    check('case "up"' in body or '"up"' in body, "三字段 up 参与解析")
    check('case "down"' in body or '"down"' in body, "三字段 down 参与解析")
    check('case "chime"' in body or '"chime"' in body, "三字段 chime 参与解析")

check("STOP_SENTINEL" in chime and "STOP_SENTINEL.equals(customId)" in chime,
      "「不播」用哨兵值区分于「没设置」（没设置 = 内置素材）")
check("injectAudio(mc, customId)" in chime,
      "自定义素材走 injectAudio 注入分支（复用扶梯那套解码注入）+ 自定义实例播放")
check("new LiftMusicInstance(event, customId)" in chime,
      "播放实例带 customId（resolve 覆盖 → 直接播引擎缓存里那段）")

print("\n== 6. 删除音频 → 直梯引用退默认 ==")
m = re.search(r"public void removeAudio\((.*?)\n    \}", data, re.S)
rm = m.group(1) if m else ""
check("liftToneAudio" in rm and "LIFT_TONE_DEFAULT" in rm and "new LiftToneAudio(" in rm,
      "removeAudio 把引用已删音频的直梯项退化成默认（record 元素整体替换）")

print("\n== 6b. 【1.46】三提示音独立子开关（指令 + UI + 播放端 + 同步包） ==")
check('Commands.literal("lifthelp" + which)' in main and "liftToneSwitchCommand" in main,
      "指令树由 liftToneSwitchCommand(which) 动态生成（lifthelp + up/down/chime）")
for which in ("up", "down", "chime"):
    check('liftToneSwitchCommand("%s")' % which in main,
          "注册了 /lifthelp%s（%s 的独立开关）" % (which, which))
check("SET_LIFT_TONE_SWITCH_CHANNEL" in main,
      "定义并注册了 UI 开关通道 SET_LIFT_TONE_SWITCH")
check("defaultLiftToneUpEnabled" in data and "defaultLiftToneDownEnabled" in data
      and "defaultLiftToneChimeEnabled" in data,
      "数据层三个子开关字段（缺省 true，旧存档兼容）")
check("isLiftToneEnabled" in mgr and "setDefaultLiftToneEnabled" in mgr
      and "replaceDefaultLiftToneEnabledAll" in mgr,
      "Manager 提供子开关读写（单维度 / 全维度 / X to Y）")
check("applyClientLiftToneSwitchLocal" in mgr,
      "客户端镜像有本地翻子开关的入口（UI 点完立即回显）")
chime = read(CHIME)
check("isLiftToneEnabled(mc.level, which)" in chime and "STOP_SENTINEL" in chime,
      "播放端：维度默认子开关关 → 该项静默（总开关之外还能再关一层）")
check("TOGGLE_SENTINEL" in screen and "toggleToneEnabled" in screen
      and "SET_LIFT_TONE_SWITCH_CHANNEL" in screen,
      "UI 每个列表第一行是「开关」按钮（点=切换维度默认子开关）")
check('"开关："' in screen, "开关行显示当前开/关状态")

print("\n== 6c. 【1.48】UI 三按钮+音量输入框 + lifthelploud up/down/door 单项音量 ==")
check('PAGES' in screen and '"up"' in screen and '"down"' in screen and '"chime"' in screen,
      "UI 主界面 = 三项跳转（PAGES = up/down/chime）")
check('buildMainPage' in screen and 'buildTonePage' in screen,
      "UI 分两页：主界面（三按钮+共用音量输入框）与单项列表")
check("defaultVolumeInput" in screen and "applyDefaultVolume" in screen,
      "主界面有「共用默认音量」输入框（= /lifthelploud <音量>，三项跟随）")
check("toneVolumeInput" in screen and "applyToneVolume" in screen,
      "单项列表有「这一项的音量」输入框（= /lifthelploud up|down|door <音量>）")
check("SET_LIFT_CHIME_VOLUME_CHANNEL" in screen and "SET_LIFT_TONE_VOLUME_CHANNEL" in screen,
      "UI 两个音量输入框各走一个通道（共用 / 单项）")
check('liftToneLoudCommand("up", "up")' in main and 'liftToneLoudCommand("door", "chime")' in main,
      "/lifthelploud 注册 up/down/door 三项（door = chime 别名）")
check("liftToneLoudForceBranch" in main,
      "-f 节点下也带 up/down/door 分支（/lifthelploud -f up 200 全维度）")
check("LIFT_TONE_VOLUME_UNSET" in screen or "clampLiftToneVolume" in main,
      "单项音量用 -1 哨兵 = 未设置（跟随共用默认）")
chime = read(CHIME)
check('liftToneVolume(mc, up ? "up" : "down")' in chime,
      "播放端：准备移动用 up/down 单项音量（detectMove 内按方向取）")
check('liftToneVolume(mc, "chime")' in chime,
      "播放端：开关门连播用 chime 单项音量（advance 内）")

print("\n== 7. 构建产物 ==")
if not os.path.isfile(JAR):
    print("[SKIP] 未找到 %s，跳过打包校验（先跑 gradlew build）" % os.path.basename(JAR))
else:
    with zipfile.ZipFile(JAR) as z:
        names = set(z.namelist())
        check("smooth/lift/client/LiftToneSetupScreen.class" in names,
              "jar 内含 LiftToneSetupScreen（三列表界面编进去了）")
        blob = z.read("smooth/lift/client/LiftChimePlayer.class")
        check(b"liftToneCustomId" in blob, "LiftChimePlayer.class 里有 liftToneCustomId（播放端分支编进去了）")

if FAILS:
    print("\n== 失败 %d 项 ==" % len(FAILS))
    for f in FAILS:
        print("   - " + f)
    sys.exit(1)
print("\n== 全部通过 ==")