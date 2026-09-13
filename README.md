# SmoothLift

## 介绍
这个模组可以让MTR里的扶梯变得更加平滑 使用 /futispeed X 改变扶梯运行速度（单位：格/秒）,使用石斧点击扶梯下端可以更改扶梯速度。支持MTR4和MTR3。

## 界面（石斧）

拿石斧右键某条扶梯会打开设置界面，里面只有两个输入框和一个按钮：

| 控件 | 作用 |
| --- | --- |
| **扶梯速度** 输入框 | 这条扶梯的运行速度（会同步左右两列） |
| **阶梯速度** 输入框 | 这条扶梯的阶梯动画速度 |
| **阶梯速度对齐扶梯速度** 按钮 | 把阶梯速度框填成扶梯速度框的值 |

- **按 ESC（或回车）退出界面时统一应用**改动，没有「确定」按钮；两项都没改就什么都不发。
- 改 **扶梯速度** 时，**阶梯速度会跟着一起变**（除非你先手动改过阶梯速度框）。
- 改 **阶梯速度** 时，**扶梯速度不会跟着变**。
- 多条扶梯能同时各自动各自的速度（真正的逐条独立，互不干扰）。
- **石斧调速会自动同步左右两边**：MTR 一条扶梯本来是 `side=left` / `side=right` 两列并排，
  现在点任意一边都会把两边一起设置，不会再出现「只能半边半边地调」。

### 阶梯速度的取值优先级

1. 这条扶梯被**单独设置**过阶梯速度 → 用单独值（界面里的「阶梯速度」框）；
2. 否则如果它的**运行速度**被石斧单独改过 → 跟随它自己的运行速度
   （视作「专门调过的扶梯」，全局阶梯速度不再影响它）；
3. 否则如果 `/jietispeed` 设过**全局阶梯速度** → 用全局值；
4. 否则 → **跟随自己的运行速度**（= 全局运行速度）。

> 也就是说：默认什么都没设时，阶梯速度就等于扶梯速度。

### 指令

先约定两个词：

- **全局扶梯** = 没被石斧单独改过的扶梯（也包括「改过、但运行速度和阶梯速度都仍然与全局值一致」的）。
- **全局扶梯速度 / 全局阶梯速度** = 这两条指令设置的全局值；一条扶梯没被单独设置时就跟随它。

**总规则（所有指令与界面都遵守）**

> **设置「扶梯速度」时，「阶梯速度」一起跟随变成同一个值；设置「阶梯速度」时，绝不改「扶梯速度」。**
>
> 例：`/futispeed -f 5` → 全游戏扶梯速度变 5，**阶梯速度也变 5**；
> `/jietispeed -f 5` → **只有**全游戏阶梯速度变 5，扶梯速度一点不动。

**`/futispeed`（改运行速度 ⇒ 阶梯速度一起跟随）**

| 指令 | 作用 |
| --- | --- |
| `/futispeed X` | 全局运行速度 = X，**全局阶梯速度同步镜像为 X**。只改全局扶梯；运行速度或阶梯速度与全局不同的扶梯原封不动。 |
| `/futispeed X to Y` | 只有当前全局运行速度正好是 X 时才改成 Y（阶梯速度同步跟随）；没有速度为 X 的就不改。 |
| `/futispeed -f X` | **强制**游戏内所有扶梯运行速度 = X（不管有没有被改过），并清掉单独设置，于是**所有扶梯的阶梯速度也变成 X**。 |
| `/futispeed -f X to Y` | 把所有运行速度为 X 的扶梯（含被改过的）改成 Y，它们的阶梯速度一起跟随变成 Y；速度不是 X 的保持不变。 |

**`/jietispeed`（只改阶梯速度，永远不动运行速度）**

| 指令 | 作用 |
| --- | --- |
| `/jietispeed X` | 全局阶梯速度 = X。**只改全局扶梯**；速度与全局不同的扶梯原封不动。 |
| `/jietispeed X to Y` | 只有当前全局阶梯速度正好是 X 时才改成 Y；没有速度为 X 的就不改。 |
| `/jietispeed -f X` | **强制**所有扶梯阶梯速度 = X——包括被石斧单独改过运行速度的那些（给它们显式补一条阶梯设置覆盖）。**扶梯速度一点不动。** |
| `/jietispeed -f X to Y` | 把所有阶梯速度为 X 的扶梯改成 Y（会按取值优先级识别「阶梯速度确实等于 X」的扶梯，含只改过运行速度的）；不是 X 的保持不变。 |

> 联动关系：**`/futispeed` 改了运行速度，阶梯速度一定跟着变**（清掉单独阶梯设置 / 镜像全局阶梯值）；
> **`/jietispeed` 只写阶梯速度，绝不会反过来改运行速度**——这条规则对 `X`、`X to Y`、`-f X`、
> `-f X to Y` 每一种写法都成立。
>
> 只保留 `-f` 一种强制写法（旧写法 `f` 已删除）。

> 实现说明（为什么能做到逐条独立）：
> MTR 的 `escalator_up/down.png` 是 **320×5120 的 16 帧纵向长条**。由于 Minecraft 的
> `AnimationMetadataSection.calculateFrameSize` 在未显式指定帧尺寸时取 `Math.min(w, h)`，
> 在贴图图集里这张贴图只占 **320×320（单帧）** 一个格子，原版是靠每 tick 上传长条的不同
> 320 高条带来“播放动画”的——也就是说图集里的这份资源 **全地图只有一套、只有一个相位**，
> 靠它无法让不同扶梯各动各的。
>
> SmoothLift 于是绕开共享图集：把 MTR 那张 **320×5120 长条** 作为模组自己的
> `DynamicTexture` 载入；把 MTR 的 12 个阶梯模型覆写为指向 SmoothLift 的底图；再在
> `WorldRenderEvents.AFTER_ENTITIES` 里，按 **每个台阶方块所属扶梯自己的速度** 计算帧号，
> 从长条贴图里取对应的一段重绘台阶面。
> 因为每个方块引用的是长条贴图里**不同的条带**，所以 **真正实现了逐条扶梯独立动画**。
>
> 那张底图是**全透明**的，用来把 MTR 原版那份「静止的台阶面」彻底隐藏掉：
> MTR 的 `ESCALATOR_STEP` 注册在 **cutout** 渲染层（`InitClient` 里 `RenderLayer.getCutout()`），
> cutout 会做 alpha 裁剪，全透明像素被整片丢弃 —— 既不显示也不写深度。
> 于是**只剩下 SmoothLift 重绘的这份会动的台阶**，不会再出现「两个重叠的台阶，一个不动一个动」。
> （底图不能删：SmoothLift 是靠「sprite 名字 = `smoothlift:block/step_static_up|down`」
> 从 MTR 的烘焙模型里认出哪些面是台阶面的，它相当于一个占位标记。）
>
> 因为静止的那份已被隐藏，**没被重绘的地方就是空的（露空）**，所以重绘范围不能是固定值：
> 直接取客户端的**有效渲染距离**（`Options.getEffectiveRenderDistance()`，即客户端设置与服务端
> 视距的较小值）再加一个区块和 32 格余量 —— 「能看见的区块」和「能看见的台阶」永远是同一批，
> 玩家走远也不会出现台阶在远处消失/露空。
> 放大范围带来的额外开销用**真正的视锥剔除**抵消：每个阶梯方块的 `AABB` 随索引一起缓存
> （见 `EscalatorStepIndex#boxes()`），每帧对视野内的方块做一次 `Frustum#isVisible`，
> 没有逐帧分配；拿不到视锥时才退回「点积 + 距离」的粗略剔除。
>
> 帧号公式：`frame = floor((tick + partialTick) * (speed / 0.625)) mod 16`（0.625 = MTR 原版
> 阶梯动画对应的标定速度）。无状态、确定性：同速度的扶梯同步，不同速度的扶梯相位自然分叉。

## Introduction
This mod makes the escalators in MTR run more smoothly. Use /futispeed X to change the escalator speed (in blocks per second). Use a stone axe to right‑click the left and right ends at the very bottom of an escalator to adjust its speed individually.

## Screen (stone axe)

Right‑clicking an escalator with a stone axe opens a screen with exactly two text fields and one button:

| Widget | Purpose |
| --- | --- |
| **Elevator speed** field | running speed of this escalator (both parallel columns) |
| **Step speed** field | step animation speed of this escalator |
| **Align step speed to elevator speed** button | fills the step field with the elevator field's value |

- Changes are applied **when you leave the screen with ESC** (Enter also works); there is no confirm
  button, and nothing is sent if neither value changed.
- Changing the **elevator speed** also changes the **step speed** (unless you manually edited the step field).
- Changing the **step speed** never changes the elevator speed.
- Multiple escalators animate at their own individual speeds at the same time.
- Adjusting one side with the stone axe **syncs both sides** (an MTR escalator is two parallel columns,
  `side=left` and `side=right`), so you no longer only change half of it.

### Step speed precedence

1. an **individual** step speed set for this escalator (the step field) → use it;
2. otherwise, if its **running speed** was individually changed with the stone axe → follow its own
   running speed (it counts as a "specially tuned" escalator, so the global step speed no longer applies);
3. otherwise, if `/jietispeed` has a **global step speed** enabled → use that;
4. otherwise → **follow its own running speed** (= the global running speed).

### Commands

Two terms first:

- **global escalator** = an escalator never individually changed with the stone axe (this also covers ones
  that were changed but whose running speed *and* step speed still equal the global values).
- **global running / step speed** = the values these commands set; escalators without an individual
  setting follow them.

**Global rule (applies to every command and to the screen)**

> **Setting the *running* speed also sets the *step* speed to the same value; setting the *step* speed
> never touches the running speed.**
>
> e.g. `/futispeed -f 5` → every escalator's running speed becomes 5 **and its step speed becomes 5**;
> `/jietispeed -f 5` → **only** every escalator's step speed becomes 5, running speeds stay as they were.

**`/futispeed` (sets the running speed ⇒ the step speed follows)**

| Command | Effect |
| --- | --- |
| `/futispeed X` | global running speed = X, and the global step speed is **mirrored to X**. **Only global escalators**; escalators whose running or step speed differs from the global values are left untouched. |
| `/futispeed X to Y` | only if the current global running speed is exactly X → Y (step speed follows); otherwise nothing changes. |
| `/futispeed -f X` | **force** every escalator in the world to X (customised or not), clearing individual settings, so **every escalator's step speed becomes X too**. |
| `/futispeed -f X to Y` | every escalator whose running speed is X (customised ones included) → Y, and their step speed follows to Y; others unchanged. |

**`/jietispeed` (sets the step speed only, never touches the running speed)**

| Command | Effect |
| --- | --- |
| `/jietispeed X` | global step speed = X. **Only global escalators**; escalators differing from the global values are left untouched. |
| `/jietispeed X to Y` | only if the current global step speed is exactly X → Y; otherwise nothing changes. |
| `/jietispeed -f X` | **force** every escalator's step speed to X — including ones whose *running* speed was individually changed (an explicit step entry is written for them so the force really covers everything). **Running speeds are never touched.** |
| `/jietispeed -f X to Y` | every escalator whose step speed is X → Y (resolved through the precedence chain, so escalators that only had their running speed changed are covered too); others unchanged. |

> Only the `-f` form is kept (the older `f` alias has been removed).
>
> Coupling: **`/futispeed` changes the running speed and the step speed always follows** (individual step
> settings are cleared / the global step value is mirrored); **`/jietispeed` only writes the step speed and
> never changes the running speed** — true for every form (`X`, `X to Y`, `-f X`, `-f X to Y`).

> Why it can do true per‑escalator animation: MTR's `escalator_up/down.png` is a **320×5120, 16‑frame
> vertical strip**. Because Minecraft's `AnimationMetadataSection.calculateFrameSize` uses `Math.min(w, h)`
> when the frame size is unspecified, in the atlas this texture only occupies a **320×320 (single‑frame)**
> slot, and vanilla "plays" it by uploading a different 320‑tall band each tick — i.e. the atlas holds a
> **single, shared, single‑phase** copy. SmoothLift sidesteps the shared atlas: it loads MTR's full
> **320×5120 strip** as its **own** `DynamicTexture`, overrides MTR's 12 step models to point at SmoothLift's
> base texture, then in `WorldRenderEvents.AFTER_ENTITIES` re‑draws each step block's faces selecting a
> frame from **that escalator's own speed**. Since every block references a **different band** of the strip,
> true per‑escalator independence is achieved.
>
> That base texture is **fully transparent**, which hides MTR's own static step faces completely:
> `ESCALATOR_STEP` is registered on the **cutout** layer (`RenderLayer.getCutout()` in `InitClient`), and
> cutout alpha‑test discards fully transparent texels — no colour, no depth. So **only SmoothLift's animated
> copy remains** and the "two overlapping steps, one static one moving" artefact is gone. (The texture can't
> be deleted: SmoothLift identifies the step faces of MTR's baked model by the sprite **name**
> `smoothlift:block/step_static_up|down`, so it acts as a placeholder marker.)
>
> Because the static copy is now hidden, **whatever is not re‑drawn simply isn't there** (holes), so the
> re‑draw range is **not a constant**: it follows the client's **effective render distance**
> (`Options.getEffectiveRenderDistance()`, i.e. client setting clamped by server view distance) plus one
> chunk and a 32‑block margin — the chunks you can see and the steps you can see are always the same set,
> so steps no longer disappear when you walk away. The extra cost of the larger range is paid back with
> **real frustum culling**: every step block's `AABB` is cached alongside the chunk index
> (`EscalatorStepIndex#boxes()`) and tested once per frame with `Frustum#isVisible`, with no per‑frame
> allocation. The old cheap "dot product + distance" test is only a fallback when no frustum is available.
>
> Frame formula: `frame = floor((tick + partialTick) * (speed / 0.625)) mod 16` (0.625 = MTR's vanilla step
> animation calibration speed).

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.