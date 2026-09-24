package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.ConstantFloat;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.openal.AL10;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【1.42】MTR 直梯（Lift）开关门提示音：关门连播 {@value EscalatorSpeedData#LIFT_HELP_CLOSE_REPEATS} 次
 * liftmusic.ogg、开门连播 {@value EscalatorSpeedData#LIFT_HELP_OPEN_REPEATS} 次。
 *
 * <p><b>怎么知道「门在关」还是「门在开」</b>：不看 MTR 自己的任何布尔量（两个版本的名字不一样），
 * 只看**门开合程度的变化方向** —— {@link MtrLiftAccess.LiftView#doorFraction()} 从「全开」（1.0）
 * 掉下来 = 开始关门；从「全关」（0.0）涨上去 = 开始开门。
 * 两个跳变点都是**单 tick 内可观测**的（服务端把 doorValue / stoppingCoolDown 都同步给了客户端，
 * MTR4 的 {@code RenderLifts} 自己就是靠 {@code getDoorValue()} 画门的），所以不会漏。
 *
 * <p><b>只对「离玩家最近的那条直梯」发声</b>：与 {@link EscalatorChimePlayer} 沿用同一个约定 ——
 * 玩家周围同时有好几条直梯时，只让最近那条的开关门声发声，否则几路门声叠在一起就分不出方向了。
 * 但我们**仍然记录所有直梯**的门值（{@link #lastDoor}），这样「最近的那条」换成另一条时
 * 不会因为「第一次看到它」而误触发一次 —— 只有真正发生跳变才响。
 *
 * <p><b>连播节奏是固定的</b>（用户选定）：不管门运动用多久，按
 * {@link EscalatorSpeedData#LIFT_HELP_INTERVAL_SECONDS} / 倍速 的间隔连播完就停，
 * 不跟着门运动时长伸缩。所以「关门 4 下」永远是 4 下，只是快慢不同。
 *
 * <p><b>倍速</b>直接写进 {@code SoundInstance.pitch}，而原版
 * {@code SoundEngine.calculatePitch} 把 pitch **硬夹在 [0.5, 2.0]**，所以
 * {@link EscalatorSpeedData#LIFT_HELP_SPEED_MIN}~{@link EscalatorSpeedData#LIFT_HELP_SPEED_MAX}
 * 就是能真正生效的全部区间 —— 指令参数类型已经夹住了，这里不再夹第二次。
 *
 * <p><b>【1.43】音量</b>由 {@code /lifthelploud}（1~1000）设置，在距离增益之上再乘一个
 * 「音量百分比 / 100」（100 → 1.0 = 原始音量、1000 → 10×、1 → 0.01）。
 * {@link #volumeFactor} 与倍速一样**逐 tick 现算**（带同一份代次缓存），所以指令改完下一个 tick 就生效。
 *
 * <p><b>音量 &gt; 100 需要成对做两件事</b>（只做一件仍然最多 1.0×，1.20 在扶梯那边真踩过）：
 * <ol>
 *   <li>{@link LiftMusicInstance} 实现 {@link GainManagedSound} —— 让
 *       {@code SoundEngineVolumeMixin} 把该实例的 {@code calculateVolume} 上限从原版的 1.0
 *       抬到 {@link EscalatorAudioPlayer#MAX_GAIN}（= 10×）；</li>
 *   <li>开播时把该 OpenAL 源的 {@code AL_MAX_GAIN} 也抬到同一个值 —— 否则 OpenAL 自己
 *       还会把有效增益压回 1.0×（见 {@link #play}）。</li>
 * </ol>
 * 音量在 {@code play()} **之前**就摆进实例，所以开播当刻就是正确音量（既不炸一下，也没有开头空白）。
 *
 * <p>入口由 {@code SmoothLiftClient} 注册：{@link #onClientTick(Minecraft)}、{@link #onDisconnect()}。
 * 诊断日志前缀 {@code [SmoothLift/LiftChime]}：只在「真的响了一下」和「开关被关掉」时各打一条。
 *
 * <h2>【1.44】准备移动提示音：{@code up.ogg} / {@code down.ogg}（各一次）</h2>
 *
 * 需求原话：「扶梯准备向上移动时播放一次 up.ogg / 扶梯准备向下移动时播放一次 down.ogg；
 * 准备移动是指类似于玩家在 2 楼，按下 z 选择 3 楼时就是准备向上了」。
 *
 * <p><b>触发判据</b>：{@link MtrLiftAccess.LiftView#move()} 发生
 * <b>{@code NONE → UP/DOWN}</b>（或反向）的跳变。两版 MTR 的方向都是**每 tick 从待办指令重算**
 * 出来的（MTR3 在 {@code tick} 的 lambda 里、MTR4 在 {@code getDirection()} 里，
 * 都对着字节码核过）：只要还有目标楼层就一直是 UP/DOWN，空闲才是 NONE。
 * 所以「按下目标楼层 → 下一 tick 方向变成 UP」正好就是用户说的「准备向上」，
 * 而且**移动全程方向不变**，途中经过楼层不会重复触发（一个方向只响一次）。
 *
 * <p><b>与开关门提示音互不干扰</b>：这个是一次性音效，检测到就**立刻放**，
 * 不占用 {@link #playsLeft} 那条连播排期 —— 否则「关门连播 4 下」会被紧接着的
 * 「准备上行」打断（两者在时间上本来就是挨着的：关门 → 起步）。
 *
 * <p><b>受同一套开关管</b>：开关沿用 {@code /lifthelp}、音量沿用 {@code /lifthelploud}、
 * 音高沿用倍速（{@code /lifthelpspeed}，【1.15】已删除该指令 ⇒ 新档恒为 1.0 = 原样）。
 * 这样不必为它再加一条指令，
 * 而用户仍然能一键静音。可闻距离与开关门提示音同为 {@link #RANGE}。
 */
public final class LiftChimePlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /**
     * 内置素材事件：{@code smoothlift:audio/liftmusic}
     * （由 {@code assets/smoothlift/sounds.json} 注册，文件在
     * {@code assets/smoothlift/sounds/audio/liftmusic.ogg}）。
     */
    private static final ResourceLocation LIFT_MUSIC =
            new ResourceLocation("smoothlift", "audio/liftmusic");

    /**
     * 【1.44】准备向上移动时放一次的素材：{@code smoothlift:audio/up}
     * （文件 {@code assets/smoothlift/sounds/audio/up.ogg}，由 {@code sounds.json} 注册）。
     */
    private static final ResourceLocation LIFT_UP =
            new ResourceLocation("smoothlift", "audio/up");

    /**
     * 【1.44】准备向下移动时放一次的素材：{@code smoothlift:audio/down}
     * （文件 {@code assets/smoothlift/sounds/audio/down.ogg}）。
     */
    private static final ResourceLocation LIFT_DOWN =
            new ResourceLocation("smoothlift", "audio/down");

    /**
     * 【1.47】可闻范围（格）的**默认值**。1.47 起由 {@code /lifthelpround} 控制（默认 4 格，
     * 首次载入模组就是这个值；三项提示音共用一份），播放时用 {@link #cachedRound}（随同步包更新）。
     * 旧版（1.46 及以前）是写死的 16.0。
     */
    private static final double RANGE_DEFAULT = EscalatorSpeedData.DEFAULT_LIFT_HELP_ROUND;

    /** 判定「全开 / 全关」的容差（门值已归一化到 [0,1]）。 */
    private static final float EPS = 1.0e-3f;

    private static final int TICKS_PER_SECOND = 20;

    /** 每条直梯上一次看到的门开合程度：直梯ID → [0,1]。用来发现「跳变」。 */
    private static final Map<Long, Float> lastDoor = new HashMap<>();

    /**
     * 【1.44】每条直梯上一次看到的「打算往哪走」：直梯ID → {@link MtrLiftAccess.Move}。
     *
     * <p>与 {@link #lastDoor} 同样的道理：要有上一次的值才认得出跳变，
     * 且**每条直梯都记**（不只是最近那条），这样「最近的一条」换成另一条时
     * 不会因为「第一次看到它」而误响一声。
     */
    private static final Map<Long, MtrLiftAccess.Move> lastMove = new HashMap<>();

    // ------------------------------------------------------------------
    // 连播排期（同一时刻只有一条排期：新的跳变会顶掉旧的）
    // ------------------------------------------------------------------

    /** 这一轮还要放几下。 */
    private static int playsLeft;
    /** 下一该放的时刻（{@code level.getGameTime()}）。 */
    private static long nextPlayTick;
    /** 这一轮发声的位置。 */
    private static Vec3 seqPos;
    /** 这一轮的倍速（pitch）。 */
    private static float seqPitch = 1.0f;
    /** 【1.45】这一轮连播用的素材：null = 内置 {@link #LIFT_MUSIC}；否则音频库文件名。 */
    private static String seqCustomId;

    /** 【1.45】直梯提示音素材设置的镜像代数（服务端同步回来时刷新本地缓存用）。 */
    private static long cachedToneGeneration = -1L;

    // ------------------------------------------------------------------
    // 「开关 / 倍速」的查询缓存（策略同 EscalatorChimePlayer：只在代次变了时才真查）
    // ------------------------------------------------------------------

    private static long cachedGeneration = -1L;
    private static boolean cachedEnabled = true;
    private static float cachedSpeed = EscalatorSpeedData.DEFAULT_LIFT_HELP_SPEED;
    /** 【1.43】音量（1~1000，100 = 原始音量），与上面两个共用同一份代次缓存。 */
    private static int cachedVolume = EscalatorSpeedData.DEFAULT_LIFT_HELP_VOLUME;
    /** 【1.47】淡入淡出范围（格），与上面共用同一份代次缓存。 */
    private static double cachedRound = RANGE_DEFAULT;

    /** 上一次「因为开关关掉而静音」时打的日志只打一次。 */
    private static boolean lastEnabled = true;

    /** {@code sounds.json} 里没注册某个事件时只提示一次（资源包缺条目 / 装错 jar）。 */
    private static final Set<ResourceLocation> warnedMissingEvents = new HashSet<>();

    private LiftChimePlayer() {
    }

    /**
     * 每客户端刻调用。整体流程：读设置 → 取最近直梯 → 比较门值发现跳变 → 推进连播排期。
     */
    public static void onClientTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            reset();
            return;
        }
        // 1) 设置（开关 + 倍速 + 音量）逐 tick 现算（带代次缓存）：指令改完下一个 tick 就生效，不用重进世界。
        boolean enabled = liftHelpEnabled(mc);
        float speed = liftHelpSpeed(mc);
        // 【1.48】共用默认音量（单项没调过时用它）；各项实际音量在 detect/detectMove/advance 里
        //   按 which 查（单项 -1 → 回落到它）。
        int helpVolume = liftHelpVolume(mc);
        if (!enabled) {
            if (lastEnabled) {
                LOGGER.info("[SmoothLift/LiftChime] 直梯开关门提示音已关闭（/lifthelp on 可打开）");
                lastEnabled = false;
            }
            reset();
            return;
        }
        if (!lastEnabled) {
            LOGGER.info("[SmoothLift/LiftChime] 直梯开关门提示音已恢复播放");
            lastEnabled = true;
        }

        // 2) 取全部直梯快照，找离玩家最近的那条。
        List<MtrLiftAccess.LiftView> lifts = MtrLiftAccess.snapshot(mc.level);
        if (lifts.isEmpty()) {
            reset();
            return;
        }
        Vec3 player = mc.player.position();
        MtrLiftAccess.LiftView nearest = null;
        double nearestDist = Double.MAX_VALUE;
        Set<Long> seen = new HashSet<>();
        for (MtrLiftAccess.LiftView lift : lifts) {
            seen.add(lift.id());
            double dist = player.distanceTo(new Vec3(lift.x(), lift.y(), lift.z()));
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = lift;
            }
        }
        if (nearest == null) {
            reset();
            return;
        }

        // 3) 记录/更新每条直梯的状态（先全部更新，再单独看最近那条的跳变）。
        for (MtrLiftAccess.LiftView lift : lifts) {
            float prevDoor = lastDoor.containsKey(lift.id()) ? lastDoor.get(lift.id()) : lift.doorFraction();
            MtrLiftAccess.Move prevMove = lastMove.containsKey(lift.id())
                    ? lastMove.get(lift.id()) : lift.move();
            if (lift == nearest) {
                detect(mc, lift, prevDoor, nearestDist);
                detectMove(mc, lift, prevMove, nearestDist, helpVolume);
            }
            lastDoor.put(lift.id(), lift.doorFraction());
            lastMove.put(lift.id(), lift.move());
        }
        // 清掉这一帧已经不在客户端集合里的直梯（走远了 / 被删了），避免 Map 无限长大。
        lastDoor.keySet().retainAll(seen);
        lastMove.keySet().retainAll(seen);

        // 4) 推进连播排期。音量逐 tick 现算（不是起播时定死），所以 /lifthelploud 改完
        //    连播途中也会立刻跟着变 —— 与倍速（起播定死、只影响连播间隔）刻意不同。
        advance(mc, player, helpVolume);
    }

    /**
     * 看这一条直梯的门值有没有发生「开始关 / 开始开」的跳变，有就起一轮连播。
     *
     * @param prev 上一次看到的门值（首次看到时 == 当前值，因此不会误触发）
     */
    private static void detect(Minecraft mc, MtrLiftAccess.LiftView lift, float prev, double distance) {
        float now = lift.doorFraction();
        boolean closing = prev >= 1.0f - EPS && now < 1.0f - EPS;
        boolean opening = prev <= EPS && now > EPS;
        if (!closing && !opening) {
            return;
        }
        int repeats = closing ? EscalatorSpeedData.LIFT_HELP_CLOSE_REPEATS
                : EscalatorSpeedData.LIFT_HELP_OPEN_REPEATS;
        seqPos = new Vec3(lift.x(), lift.y(), lift.z());
        seqPitch = cachedSpeed;
        // 【1.45】开关门连播素材：竖井列上设了自定义 chime 就用自定义；「不播」→ 静默跳过；
        // 默认素材 = 内置 liftmusic。
        String customId = liftToneCustomId(mc, lift, "chime");
        if (STOP_SENTINEL.equals(customId)) {
            LOGGER.info("[SmoothLift/LiftChime] 直梯 #{} 开始{}，但开关门提示音设为「不播」，跳过",
                    lift.id(), closing ? "关门" : "开门");
            // 清掉可能残留的上一轮排期（避免旧 playsLeft 干等）
            playsLeft = 0;
            nextPlayTick = 0L;
            seqPos = null;
            seqCustomId = null;
            return;
        }
        seqCustomId = customId;
        if (customId != null) {
            // 自定义素材按原速播（不能按倍速变速 —— 那是给内置素材连播用的）
            seqPitch = 1.0f;
        }
        playsLeft = repeats;
        // 立刻放第一下（不等一个间隔）：门开始动的那一瞬就该听见，这与扶梯提示音「一触发就听得见」一致。
        nextPlayTick = mc.level.getGameTime();
        LOGGER.info("[SmoothLift/LiftChime] 直梯 #{} 开始{}，连播 {} 次（距玩家 {} 格，倍速 {}，素材 {}）",
                lift.id(), closing ? "关门" : "开门", repeats,
                String.format("%.1f", distance), String.format("%.2f", cachedSpeed),
                seqCustomId == null ? "内置" : seqCustomId);
    }

    /**
     * 【1.44】看这一条直梯的「打算往哪走」有没有变化；变成 UP / DOWN 就**立刻放一次**对应素材。
     *
     * <p><b>判据 {@code now != NONE && now != prev}</b> 覆盖三种真实情况：
     * 待命→上行（{@code NONE→UP}）、待命→下行（{@code NONE→DOWN}）、
     * 以及**中途反悔**（{@code UP→DOWN}，比如玩家又按了更低那层）。
     * 「方向没变」不响（那是移动全程的常态）、「变成 NONE」也不响（那是到站停下）。
     *
     * <p><b>刻意不走连播排期</b>（直接 {@link #play}）：这样它不会顶掉正在进行的
     * 「关门连播 4 下」—— 而那两句在时间上本来就挨着（关门 → 起步）。
     *
     * @param prev 上一次看到的方向（首次看到时 == 当前值，因此不会误触发）
     */
    private static void detectMove(Minecraft mc, MtrLiftAccess.LiftView lift,
                                   MtrLiftAccess.Move prev, double distance, int helpVolume) {
        MtrLiftAccess.Move now = lift.move();
        if (now == MtrLiftAccess.Move.NONE || now == prev) {
            return;
        }
        boolean up = now == MtrLiftAccess.Move.UP;
        // 【1.48】这项（up/down）自己的音量：单项调过用它，没调过回落共用默认。
        int toneVolume = liftToneVolume(mc, up ? "up" : "down");
        float volume = gain(distance) * volumeFactor(toneVolume);
        if (volume <= 0.0f) {
            return;
        }
        // 【1.45】准备移动提示音素材：竖井列上设了自定义 up/down 就用自定义；
        // 「不播」→ STOP_SENTINEL → 直接静默跳过；默认素材 → null → 内置 up.ogg/down.ogg。
        String customId = liftToneCustomId(mc, lift, up ? "up" : "down");
        if (STOP_SENTINEL.equals(customId)) {
            LOGGER.info("[SmoothLift/LiftChime] 直梯 #{} 准备{}移动，但该项提示音设为「不播」，跳过",
                    lift.id(), up ? "向上" : "向下");
            return;
        }
        play(mc, up ? LIFT_UP : LIFT_DOWN, customId, new Vec3(lift.x(), lift.y(), lift.z()),
                volume, customId == null ? cachedSpeed : 1.0f);
        LOGGER.info("[SmoothLift/LiftChime] 直梯 #{} 准备{}移动 → 播放 {}（距玩家 {} 格、倍速 {}）",
                lift.id(), up ? "向上" : "向下", customId == null ? (up ? "up.ogg" : "down.ogg") : customId,
                String.format("%.1f", distance), String.format("%.2f", cachedSpeed));
    }

    /**
     * 【1.45】这条直梯（竖井列）某一项提示音**实际要播的素材**：
     * <ul>
     *   <li>{@link EscalatorSpeedData#LIFT_TONE_OFF} → 不播：返回 {@code STOP}（调用方静默跳过）；</li>
     *   <li>{@link EscalatorSpeedData#LIFT_TONE_DEFAULT} → 内置素材：返回 {@code null}；</li>
     *   <li>其它 → 音频库文件名（返回它，调用方用 {@code injectAudio} 分支播）。</li>
     * </ul>
     *
     * <p>【1.46】在这之前先看**维度默认子开关**（{@code /lifthelp up|down|door on|off} 或石斧 UI 开关）：
     * 该维度这项整体关了 → 直接返回 {@code STOP}（连单条素材都不用查）。
     *
     * <p><b>【1.15】两层查找</b>：先看这条直梯（竖井列）的单独设置；没有这一条、或者那一项正好是
     * {@code default}（「跟上一层」），就回落到**维度默认素材**（{@code /lifthelp up|down|door <名字>}）；
     * 维度默认再是 {@code default} 才是模组内置素材。
     * 两层的 {@code default} 因此都是同一个意思（跟上一层），
     * 不设任何东西时行为与 1.14 完全一致。
     */
    private static String liftToneCustomId(Minecraft mc, MtrLiftAccess.LiftView lift, String which) {
        // 【1.45】素材镜像的代数缓存：只在有同步变化时才真查。
        long generation = EscalatorSpeedManager.clientLiftToneGeneration();
        if (generation != cachedToneGeneration) {
            cachedToneGeneration = generation;
        }
        if (mc.level == null) {
            return null;
        }
        // 【1.46】维度默认子开关（总开关 /lifthelp 在外层 onClientTick 已查）
        if (!EscalatorSpeedManager.isLiftToneEnabled(mc.level, which)) {
            return STOP_SENTINEL;
        }
        // ① 这条直梯的单独设置
        long key = EscalatorSpeedManager.liftToneKeyNear(lift.x(), lift.z());
        EscalatorSpeedData.LiftToneAudio tone = EscalatorSpeedManager.getClientLiftTone(mc.level, key);
        String value = EscalatorSpeedManager.toneField(tone, which);
        // ② default（含「没这一项」）= 跟维度默认
        if (value == null || value.isEmpty() || EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(value)) {
            value = EscalatorSpeedManager.getLiftToneAudio(mc.level, which);
        }
        if (EscalatorSpeedData.LIFT_TONE_OFF.equals(value)) {
            return STOP_SENTINEL;
        }
        if (value == null || value.isEmpty() || EscalatorSpeedData.LIFT_TONE_DEFAULT.equals(value)) {
            return null;
        }
        return value;
    }

    /** 【1.45】「这条直梯不播该项」的哨兵（与「没设置」区分开：没设置 = 内置素材）。 */
    private static final String STOP_SENTINEL = "\u0000STOP";

    /**
     * 到点就放一下，放完这一轮就停。
     *
     * @param helpVolume 【1.43】共用默认音量（1~1000）；【1.48】实际用 chime 单项（没调过回落它）
     */
    private static void advance(Minecraft mc, Vec3 player, int helpVolume) {
        if (playsLeft <= 0 || seqPos == null) {
            return;
        }
        long now = mc.level.getGameTime();
        if (now < nextPlayTick) {
            return;
        }
        // 【1.48】开关门连播用 chime 单项音量（-1 → 回落共用默认）
        int toneVolume = liftToneVolume(mc, "chime");
        float volume = gain(player == null ? null : player.distanceTo(seqPos)) * volumeFactor(toneVolume);
        if (volume > 0.0f) {
            play(mc, LIFT_MUSIC, seqCustomId, seqPos, volume, seqPitch);
        }
        playsLeft--;
        nextPlayTick = now + intervalTicks(seqPitch);
        if (playsLeft <= 0) {
            seqPos = null;
            seqCustomId = null;
        }
    }

    /**
     * 【1.43】提示音音量百分比 → 增益系数：{@code 音量 / 100}
     * （100 → 1.0 = 原始音量，1000 → 10.0 = 10×，1 → 0.01）。
     *
     * <p>这里再夹一次（而不是只靠指令参数类型）是为了挡住**存档里被外部改坏的值**：
     * 一个越界的音量乘进增益后会被原版那两道夹取压回去，表现是「填了没用」而不是报错，
     * 不如在算增益的源头就夹住。
     *
     * <p>上限 10.0 正好等于 {@link EscalatorAudioPlayer#MAX_GAIN}，与
     * {@link GainManagedSound} + {@code SoundEngineVolumeMixin} 放开的那个上限一致 ——
     * 三处必须同步（{@link EscalatorSpeedData#HELP_VOLUME_MAX} / {@code MAX_GAIN} / 本函数）。
     */
    private static float volumeFactor(int helpVolume) {
        return EscalatorSpeedData.clampLiftHelpVolume(helpVolume) / 100.0f;
    }

    /**
     * 连播的间隔（tick）= {@link EscalatorSpeedData#LIFT_HELP_INTERVAL_SECONDS} × 20 / 倍速。
     *
     * <p>倍速同时作用于**音高**和**间隔**是有意的：只改音高的话，放慢到 0.5 倍时
     * 每一下会拖到 1.7 秒长、却仍然每 0.8 秒叠进来一次，听起来是一团糊；
     * 一起缩放才保持「一下一下分得清」的节奏。下限 1 tick，避免极小倍速把间隔算成 0。
     */
    private static long intervalTicks(float speed) {
        double seconds = EscalatorSpeedData.LIFT_HELP_INTERVAL_SECONDS / speed;
        return Math.max(1L, Math.round(seconds * TICKS_PER_SECOND));
    }

    /**
     * 距离增益：{@link #cachedRound} 格内由 1 衰减到 0，平方衰减
     * （与 {@link EscalatorChimePlayer} 用同一套公式，保证两类提示音的「远近听感」一致）。
     * 【1.47】范围由 {@code /lifthelpround} 控制（默认 4 格，三项提示音共用）。
     */
    private static float gain(double distance) {
        if (!(distance < cachedRound)) {
            return 0.0f;
        }
        double f = 1.0 - distance / cachedRound;
        return (float) (f * f);
    }

    /**
     * 放一下指定的提示音（**一次性**，不循环）。
     *
     * <p>{@code play()} 之前先把位置和音量摆好：{@code SoundEngine.play} 会立刻用实例当时的
     * volume 建源开播，摆好它开播当刻就是正确音量（既不会先满音量炸一下，也没有开头空白）。
     * 这里刻意**不做淡入** —— 提示音是脉冲式的，见 {@link EscalatorChimePlayer} 里那条教训。
     *
     * @param event    内置声音事件：{@link #LIFT_MUSIC}（开关门）/ {@link #LIFT_UP} / {@link #LIFT_DOWN}。
     *                 {@code customId != null} 时它只是占位（不会被用到）。
     * @param customId 【1.45】音频库文件名（null = 用内置 event）。
     */
    private static void play(Minecraft mc, ResourceLocation event, String customId, Vec3 pos, float volume, float pitch) {
        if (customId == null) {
            if (mc.getSoundManager().getSoundEvent(event) == null) {
                if (warnedMissingEvents.add(event)) {
                    LOGGER.warn("[SmoothLift/LiftChime] 声音事件 {} 没注册（模组的 sounds.json 没被加载？），"
                            + "这个直梯提示音不会出声", event);
                }
                return;
            }
            LiftMusicInstance inst = new LiftMusicInstance(event);
            inst.setPosition(pos, volume);
            inst.setPitch(pitch);
            mc.getSoundManager().play(inst);
            // 【1.43】音量上限要**成对**放开，只做一半就还是「调到 100 以上完全没变化」：
            //   ① LiftMusicInstance 实现 GainManagedSound → SoundEngineVolumeMixin 不再把
            //      calculateVolume 夹到原版的 1.0（这一步在 play() 之前就已经靠接口成立）；
            //   ② 这里再把该 OpenAL 源的 AL_MAX_GAIN 抬到 MAX_GAIN —— 否则 OpenAL 自己会把
            //      **有效增益**压回 1.0×（它默认就是 1.0）。
            // 该属性是按源保留的（引擎每 tick 只改 AL_GAIN / pitch / position，不碰它），
            // 所以开播时设一次即可；和 EscalatorChimePlayer 那边是同一套做法。
            SoundEngine engine = mc.getSoundManager().soundEngine;
            ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
            if (handle != null) {
                handle.execute(ch -> AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN));
            }
            return;
        }
        // 【1.45】自定义（玩家导入的）素材：注入→播，逻辑复用扶梯那套（injectAudio + 自定义实例）。
        if (!EscalatorAudioPlayer.injectAudio(mc, customId)) {
            return; // 没同步到 / 解码失败 / 已被删除 → 静默跳过（日志已在 injectAudio 内部处理过）
        }
        LiftMusicInstance inst = new LiftMusicInstance(event, customId);
        inst.setPosition(pos, volume);
        inst.setPitch(pitch);
        mc.getSoundManager().play(inst);
        SoundEngine engine = mc.getSoundManager().soundEngine;
        ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
        if (handle != null) {
            handle.execute(ch -> AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, EscalatorAudioPlayer.MAX_GAIN));
        }
    }

    /**
     * 清空「连播排期」和「门值记录」。
     *
     * <p>注意**不清** {@link #lastEnabled}：那一位是「日志只打一次」的状态，与播放无关。
     */
    private static void reset() {
        playsLeft = 0;
        nextPlayTick = 0L;
        seqPos = null;
        seqCustomId = null;
        lastDoor.clear();
        lastMove.clear();
    }

    /** 断开连接：停掉一切并清掉缓存（连设置缓存一起清，避免下一个世界沿用旧倍速）。 */
    public static void onDisconnect() {
        reset();
        cachedGeneration = -1L;
        cachedEnabled = true;
        cachedSpeed = EscalatorSpeedData.DEFAULT_LIFT_HELP_SPEED;
        cachedVolume = EscalatorSpeedData.DEFAULT_LIFT_HELP_VOLUME;
        cachedRound = RANGE_DEFAULT;
        lastEnabled = true;
    }

    /** 直梯提示音开关（带代次缓存：只在同步包到达 / 断线时才真的查一次）。 */
    private static boolean liftHelpEnabled(Minecraft mc) {
        refreshSettings(mc);
        return cachedEnabled;
    }

    /** 直梯提示音倍速（同一份缓存）。 */
    private static float liftHelpSpeed(Minecraft mc) {
        refreshSettings(mc);
        return cachedSpeed;
    }

    /** 【1.43】直梯提示音音量 1~1000（同一份缓存）。 */
    private static int liftHelpVolume(Minecraft mc) {
        refreshSettings(mc);
        return cachedVolume;
    }

    /**
     * 【1.48】某项（up / down / chime）**生效**的音量：该项单独调过用它，没调过（-1）回落共用默认。
     * 用同一个 {@link #cachedGeneration} 缓存（Manager 的镜像整体同步，一次刷新三项都新鲜）。
     */
    private static int liftToneVolume(Minecraft mc, String which) {
        refreshSettings(mc);
        return EscalatorSpeedManager.getLiftToneVolume(mc.level, which);
    }

    private static void refreshSettings(Minecraft mc) {
        long generation = EscalatorSpeedManager.clientLiftChimeGeneration();
        if (generation == cachedGeneration) {
            return;
        }
        cachedGeneration = generation;
        cachedEnabled = EscalatorSpeedManager.isLiftHelpEnabled(mc.level);
        cachedSpeed = EscalatorSpeedManager.getLiftHelpSpeed(mc.level);
        cachedVolume = EscalatorSpeedManager.getLiftHelpVolume(mc.level);
        cachedRound = EscalatorSpeedManager.getLiftHelpRound(mc.level);
    }

    /**
     * 提示音（liftmusic.ogg / up.ogg / down.ogg，由 {@code event} 决定）的播放实例：
     * **一次性**（{@code looping = false}），播完由原版 {@code SoundEngine} 自己回收通道，
     * 不需要 {@code TickableSoundInstance}。
     *
     * <p>{@code attenuation = NONE} 表示「关掉 OpenAL 的距离增益，只保留声像」——
     * 音量由 {@link #gain} 自己按「到直梯的距离」算（再乘 {@link #volumeFactor}），
     * 和扶梯那两个播放器同一套做法，这样「多远开始听不见」完全由我们的 {@link #RANGE} 说了算，
     * 不受原版 16 格与声音来源滑块的组合影响。
     *
     * <p><b>【1.43】实现 {@link GainManagedSound}</b>：{@code /lifthelploud} 的音量上限是 1000
     * （= 10×），而原版 {@code SoundEngine.calculateVolume} 会把增益夹到 [0,1]，
     * 所以必须让 {@code SoundEngineVolumeMixin} 认得出本实例、放行到
     * {@link EscalatorAudioPlayer#MAX_GAIN}；配套的 {@code AL_MAX_GAIN} 在 {@link #play} 里抬。
     * 1.42 时这里刻意**没有**实现它（当时音量恒为 1.0，用不上），1.43 加音量后必须补上 ——
     * 只有接口没有 {@code AL_MAX_GAIN}（或反过来）都还是最多 1.0×。
     *
     * <p><b>【1.44】事件变成构造参数</b>（原先写死 liftmusic）：up/down 与开关门三者共用这一个
     * 实例类，音量/音高/距离的行为完全一致，不重复三份。
     */
    private static final class LiftMusicInstance extends AbstractSoundInstance implements GainManagedSound {

        private final String customId;

        LiftMusicInstance(ResourceLocation event) {
            this(event, null);
        }

        /** 【1.45】{@code customId != null} = 玩家导入的音频（走 injectAudio 分支，不读资源包）。 */
        LiftMusicInstance(ResourceLocation event, String customId) {
            super(event, SoundSource.BLOCKS, RandomSource.create());
            this.customId = customId;
            this.looping = false;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = false;
            this.volume = 1.0f;
            this.pitch = 1.0f;
        }

        /** 【1.45】自定义素材时把事件解析成「引擎缓存里那段注入的音频」（仿扶梯 ChimeInstance）。 */
        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            if (customId == null) {
                return super.resolve(soundManager);
            }
            ResourceLocation name = EscalatorAudioPlayer.soundLocation(customId);
            Sound s = new Sound(name.toString(), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F),
                    1, Sound.Type.FILE, false, false, 0);
            this.sound = s;
            WeighedSoundEvents events = new WeighedSoundEvents(name, null);
            events.addSound(s);
            return events;
        }

        void setPosition(Vec3 pos, float volume) {
            this.x = pos.x;
            this.y = pos.y;
            this.z = pos.z;
            this.volume = volume;
        }

        void setPitch(float pitch) {
            this.pitch = pitch;
        }
    }
}
