package smooth.lift.client;

import com.mojang.blaze3d.audio.OggAudioStream;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
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
import smooth.lift.EscalatorUtil;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 【1.7】自定义扶梯声音播放器。
 *
 * <p>思路：玩家选择的 OGG 音频字节保存在存档（服务端），通过网络同步到客户端镜像。
 * 这里把字节解码成 {@link SoundBuffer} 直接注入 {@code SoundBufferLibrary.cache}，
 * 再构造一个自定义 {@link SoundInstance}（重写 {@code resolve} 绕过原版的事件查找），
 * 用引擎原生的 getCompleteBuffer(computeIfAbsent) 命中缓存播放。
 *
 * <p><b>【1.9】距离按「整条扶梯」算，不是按某个方块算。</b>
 * 绑定信息里只存了玩家用石斧点的那**一个**方块，但一条扶梯往往远长于 16 格：
 * 如果只算到那个方块的距离，玩家站在扶梯另一端（离那个方块十几格外）就会莫名其妙静音。
 * 所以这里用 {@link EscalatorUtil#collectChain} 把整条扶梯（含左右两列与侧板）展开成方块集合，
 * 取「玩家到链上**最近**方块」的距离 —— 也就是**离开整条扶梯 16 格才静音**。
 *
 * <p>音量 = 距离衰减（16 格内 1.0 → 0.0）× 这条扶梯自己的音量设定（界面输入 1~1000）。
 * 【1.12】100 = 原始音量，&gt;100 = 放大（最大 1000 = 10×）。
 * 要让 &gt;1 真正响，必须同时拆掉**两层**夹取：
 * <ol>
 *   <li>Minecraft 侧：{@code SoundEngine.calculateVolume} 把增益夹到 [0,1]
 *       → 由 {@code SoundEngineVolumeMixin} 放开到我方声音专用上限；</li>
 *   <li>OpenAL 侧：有效增益被夹到源的 {@code AL_MAX_GAIN}，而它默认 = 1.0
 *       → 开播时把该源的 AL_MAX_GAIN 抬到 {@link #MAX_GAIN}（见 {@code onClientTick}）。</li>
 * </ol>
 * 只做第 1 步不够（有效增益仍会被压回 1.0），只做第 2 步也不够（值根本传不出来）。
 * 没有绑定自定义声音的扶梯不发声（没有实例就不播放，天然静音）；
 * 同一时刻只播放离玩家最近的那条扶梯的声音，避免多音源混杂。
 *
 * <p><b>【1.11】默认扶梯音频（/futimusic）</b>：服务端可以设一个「默认音频」，
 * 于是**没有单独绑定音频**的扶梯也会发声（所有扶梯统一放内置音频那种玩法）。
 * 候选集合因此变成两部分：
 * <ol>
 *   <li>所有单独绑定过音频的扶梯（原有逻辑，链距离）；</li>
 *   <li>玩家附近最近的扶梯方块（只有设了默认音频时才扫描，见 {@link #nearestEscalatorNear}）。</li>
 * </ol>
 * 两条候选按「离玩家最近」取胜。因为「同一条链」的绑定一定会在第 1 步算出
 * ≤ 第 2 步的链距离，所以「单独绑定优先、其余用默认」是天然成立的。
 *
 * <p><b>【1.14】起始淡入</b>：{@code play()} 是「立刻用实例当时的 volume 建源并开播」，
 * 而实例刚从构造函数出来时 volume = 1.0，一 tick 之后才被纠正成「距离衰减 × 用户音量」——
 * 于是声音出现的那一瞬间会先满音量炸一下，听感就是很突兀的一声“咔”。
 * 现在 play() 之前先把音量摆到 {@link #FADE_IN_FLOOR}（≈ -80 dB，听不见但不为 0），
 * 再用 {@link #FADE_IN_TICKS} tick 指数淡入到目标值。
 * 起点**不能取 0**：MC 见音量 ≤ 0 会直接 {@code channel.stop()} 把通道掐掉。
 *
 * <p>三个静态入口由 {@link SmoothLiftClient} 注册调用：
 * {@link #onClientTick(Minecraft)}（每 tick）、{@link #onDisconnect()}、{@link #onAudioReloaded()}。
 */
public final class EscalatorAudioPlayer {

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 超过该距离（格）不播放声音。注意是「离开整条扶梯」的距离，不是到某个方块的距离。 */
    public static final double MAX_DISTANCE = 16.0;

    /**
     * 【1.12】最大音量增益 = {@link EscalatorSpeedData#AUDIO_VOLUME_MAX}/{@link EscalatorSpeedData#DEFAULT_AUDIO_VOLUME}
     * = 1000/100 = 10×。原版引擎把音量夹到 [0,1]，超过 1 的部分靠
     * {@code SoundEngineVolumeMixin} 放开到这个上限。
     */
    public static final float MAX_GAIN =
            EscalatorSpeedData.AUDIO_VOLUME_MAX / (float) EscalatorSpeedData.DEFAULT_AUDIO_VOLUME;

    /** {@link #rawEscalatorGain} 的「不是我方声音」哨兵值。 */
    public static final float NOT_ESCALATOR_SOUND = -1.0f;

    /**
     * 【1.14】声音刚出现时的淡入 tick 数（{@value} tick = 0.3 秒）。
     *
     * <p>为什么需要它：{@code SoundManager.play(inst)} 会**立刻**用实例当时的 {@code volume}
     * 建立 OpenAL 源并开播（{@code SoundEngine.play}：{@code getVolume()} → {@code calculateVolume}
     * → {@code Channel.setVolume}），而实例刚从构造函数出来时 {@code volume == 1.0}；
     * 一 tick 之后引擎才把音量更新成「距离衰减 × 用户音量」。于是「声音出现的那一瞬间」
     * 会先以满音量响一下再掉下去 —— 听感就是很突兀的一声“咔”。
     * 现在在 {@code play()} **之前**就把位置/起声音量摆好，再让音量淡入到目标值。
     */
    public static final int FADE_IN_TICKS = 6;

    /**
     * 淡入的起点增益 ≈ -80 dB：听不见，但**必须 &gt; 0**。
     *
     * <p>为什么要有个非零下限：MC 的 {@code SoundEngine} 在音量 ≤ 0 时不是把增益设成 0，
     * 而是直接 {@code channel.stop()} 把整条通道**停掉**（{@code SoundEngine.method_19750}：
     * {@code if (volume <= 0) channel.stop(); else channel.setVolume(volume);}）。
     * 起点取 0 会让声音在出现的第一 tick 就被掐断（然后下一 tick 重建、再被掐断），
     * 结果是彻底没声音 —— 比爆音更糟。
     */
    public static final float FADE_IN_FLOOR = 1.0e-4f;

    /**
     * 【1.12】给音量 Mixin 用：返回这条声音**未夹取**的增益（可 &gt;1）；
     * 不是本模组的扶梯声音时返回 {@link #NOT_ESCALATOR_SOUND}，让 Mixin 交回原版处理。
     */
    public static float rawEscalatorGain(SoundInstance instance) {
        return instance instanceof EscalatorSoundInstance ? instance.getVolume() : NOT_ESCALATOR_SOUND;
    }

    /** 正在播放的实例：音频ID → 实例（一个音频同一时刻只播一个实例，防止重复 play 泄漏）。 */
    private static final Map<String, EscalatorSoundInstance> ACTIVE = new HashMap<>();

    /**
     * 【1.9】扶梯链缓存：绑定方块 → 该扶梯链上全部方块。
     *
     * <p>每 tick 都对「所有绑定」做一次链遍历太浪费（扶梯链可能上百格），
     * 所以缓存起来；仅在「绑定集合变化 / 收到新同步 / 断开 / 定期到期」时重建。
     */
    private static final Map<BlockPos, List<BlockPos>> CHAIN_CACHE = new HashMap<>();

    /** 当前缓存对应的绑定集合指纹，用来发现「绑定变了」（如新绑了一条扶梯）。 */
    private static int chainCacheFingerprint = Integer.MIN_VALUE;

    /** 上次重建链缓存的 tick；每 {@link #CHAIN_CACHE_TTL_TICKS} tick 重建一次，兜住世界被改动的情况。 */
    private static long chainCacheBuiltTick = Long.MIN_VALUE;

    /** 链缓存有效期（tick）= 30 秒。 */
    private static final int CHAIN_CACHE_TTL_TICKS = 600;

    /**
     * 【1.11】设了默认音频时用的「附近最近的扶梯方块」缓存。
     *
     * <p>只在 {@code defaultAudio != null} 时才会去扫描，所以没设默认音频的世界
     * （也就是旧存档）**一点额外开销都没有**。扫描半径 = {@link #MAX_DISTANCE}，
     * 结果缓存 {@link #NEARBY_TTL_TICKS} tick；期间即使玩家移动，
     * 每 tick 仍然用「玩家当前坐标 → 缓存方块」重算距离，只是候选选择最多滞后 0.5 秒。
     */
    private static BlockPos nearbyEscalator;
    private static long nearbyBuiltTick = Long.MIN_VALUE;
    /** 附近扶梯缓存有效期（tick）= 0.5 秒。 */
    private static final int NEARBY_TTL_TICKS = 10;

    /** 解码失败的音频：本次会话内不再反复尝试（避免每 tick 重复解码 + 刷屏）。 */
    private static final Set<String> DECODE_FAILED = new HashSet<>();

    /** 上一条「为什么没有声音」的说明；只在状态变化时打印，避免每 tick 刷日志。 */
    private static String lastNote;

    private EscalatorAudioPlayer() {
    }

    /**
     * 每 tick 调用：找到当前维度里离玩家最近的、绑定了自定义声音的扶梯，
     * 注入音频（如未注入）并保证在播，然后按距离更新音量与位置；
     * 没有候选或玩家不在范围内时停掉正在播的实例。
     *
     * <p>每一处「不出声」的分支都会在状态变化时打一条 {@code [SmoothLift/Audio]} 日志，
     * 这样「绑定了却没声音」可以直接从游戏日志里定位到底卡在哪一步。
     */
    public static void onClientTick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            stopAll(mc);
            return;
        }
        Map<BlockPos, String> bindings = EscalatorSpeedManager.getClientAudioBindings(mc.level.dimension());
        String defaultAudio = EscalatorSpeedManager.getClientDefaultAudio(mc.level.dimension());
        if (bindings.isEmpty() && defaultAudio == null) {
            stopAll(mc);
            note("当前维度没有任何扶梯绑定了自定义声音（要先在石斧界面里绑定），也没有默认音频"
                    + "（可用 /futimusic default 让所有扶梯播放内置音频）");
            return;
        }
        refreshChainCacheIfNeeded(mc, bindings);

        Vec3 playerPos = mc.player.position();
        BlockPos bestPos = null;
        BlockPos bestNear = null;
        String bestId = null;
        double bestDist = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, String> entry : bindings.entrySet()) {
            BlockPos bound = entry.getKey();
            // 【1.9】先在这条扶梯的整条链上找到离玩家最近的那个方块，
            // 再算玩家到它的距离 —— 也就是「离开整条扶梯」的距离，而不是到某个方块的距离。
            BlockPos near = nearestChainPos(bound, playerPos);
            double dist = distanceToBlock(playerPos, near);
            if (dist < bestDist) {
                bestDist = dist;
                bestPos = bound;
                bestNear = near;
                bestId = entry.getValue();
            }
        }
        // 【1.11】默认音频：把「玩家附近最近的扶梯」也当成一个候选。
        // 如果那条扶梯其实单独绑过音频，上面的循环已经用同一条链算出更近的距离并选中它了，
        // 所以这里直接用默认音频不会覆盖掉单独绑定。
        if (defaultAudio != null) {
            BlockPos near = nearestEscalatorNear(mc);
            if (near != null) {
                double dist = distanceToBlock(playerPos, near);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestPos = near;
                    bestNear = near;
                    bestId = defaultAudio;
                }
            }
        }
        if (bestPos == null || bestId == null || bestNear == null) {
            stopAll(mc);
            return;
        }
        if (bestDist > MAX_DISTANCE) {
            stopAll(mc);
            note("最近的扶梯整条都在 " + String.format("%.1f", bestDist)
                    + " 格外（离开整条扶梯 " + (int) MAX_DISTANCE + " 格内才发声）");
            return;
        }
        // 【1.8】内置音频走原版资源包加载（sounds.json 已注册 smoothlift:audio/<key>），
        // 不需要字节同步、不需要注入、也没有解码失败这回事。
        boolean builtin = EscalatorSpeedManager.isBuiltinAudio(bestId);
        if (builtin) {
            ResourceLocation event = builtinEvent(bestId);
            if (mc.getSoundManager().getSoundEvent(event) == null) {
                stopAll(mc);
                note("内置音频事件 " + event + " 没注册（模组的 sounds.json 没被加载？）");
                return;
            }
        } else {
            byte[] bytes = EscalatorSpeedManager.getAudioBytes(mc.level, bestId);
            if (bytes == null || bytes.length == 0) {
                stopAll(mc);
                note("绑定的音频 " + bestId + " 还没同步到本客户端（音频数据为空，可点界面里的【刷新】）");
                return;
            }
            if (DECODE_FAILED.contains(bestId)) {
                stopAll(mc);
                note("音频 " + bestId + " 解码失败（MC 只认 Ogg Vorbis），这条扶梯只能静音；详见之前的日志");
                return;
            }
            if (!inject(mc, bestId, bytes)) {
                stopAll(mc);
                note("音频 " + bestId + " 解码失败（MC 只认 Ogg Vorbis：MP3/Opus/FLAC 都会失败）");
                return;
            }
        }

        SoundEngine engine = mc.getSoundManager().soundEngine;
        EscalatorSoundInstance inst = ACTIVE.get(bestId);
        if (inst != null && !engine.instanceToChannel.containsKey(inst)) {
            // 引擎侧已经把这条通道丢了（资源重载 F3+T、换音频输出设备、被 stopAll 等），
            // 实例却还留在 ACTIVE 里 -> 以后永远不会再 play，表现就是「明明绑着却永久静音」。
            ACTIVE.remove(bestId);
            inst = null;
        }
        // 音量 = 距离衰减（离开整条扶梯 16 格内 1.0 -> 0.0）× 这条扶梯自己的音量设定（1~1000）。
        // 【1.12】100 = 原始音量（增益 1.0），1000 = 10× 放大。这里存的是「未夹取」的增益，
        // 引擎每 tick 用实例的 volume/x/y/z 同步到 Channel；>1 的部分靠 SoundEngineVolumeMixin 放行。
        float distanceFactor = (float) Math.max(0.0, 1.0 - bestDist / MAX_DISTANCE);
        int userVolume = EscalatorSpeedManager.getClientBindingVolume(mc.level.dimension(), bestPos);
        float target = distanceFactor * (userVolume / (float) EscalatorSpeedData.DEFAULT_AUDIO_VOLUME);

        if (inst == null) {
            inst = new EscalatorSoundInstance(bestId);
            inst.startTick = mc.level.getGameTime();
            // 【1.14】play() 之前必须先把音量摆成「淡入起点」：
            // SoundEngine.play 会**立刻**用实例当时的 volume 建源开播，而实例刚从构造函数
            // 出来时 volume == 1.0 —— 于是声音出现的那一瞬会先满音量炸一下再掉到目标音量，
            // 听感就是那声很突兀的“咔”。起点用 FADE_IN_FLOOR（≈ -80 dB，听不见但不是 0）。
            inst.setPosition(bestNear.getCenter(), FADE_IN_FLOOR);
            mc.getSoundManager().play(inst);
            if (!engine.instanceToChannel.containsKey(inst)) {
                // play() 没建立通道（例如声音引擎还没加载完），下一 tick 再试。
                note("声音引擎没有接受这条声音（通道未建立），稍后重试：" + bestId);
                return;
            }
            // 静态 Sound 播完一遍就停，这里对底层 Channel 开启循环。
            //
            // 【1.12】顺便把该 OpenAL 源的 AL_MAX_GAIN 抬到 MAX_GAIN：
            // OpenAL 会把「源增益」夹到 AL_MAX_GAIN，而它默认 = 1.0，所以光去掉
            // Minecraft 侧的夹取（见 SoundEngineVolumeMixin）还不够 —— 有效增益依旧被
            // 压回 1.0，表现就是「音量调到 100 以上没变化」。抬到 10 才真正能放大。
            // 该属性是「按源」的、会一直保留，所以开播时设一次即可；引擎每 tick 只改
            // AL_GAIN / pitch / position，不会碰 AL_MAX_GAIN。
            ChannelAccess.ChannelHandle handle = engine.instanceToChannel.get(inst);
            if (handle != null) {
                handle.execute(ch -> {
                    ch.setLooping(true);
                    AL10.alSourcef(ch.source, AL10.AL_MAX_GAIN, MAX_GAIN);
                });
            }
            ACTIVE.put(bestId, inst);
            LOGGER.info("[SmoothLift/Audio] 开始循环播放 {}{}（到最近扶梯的整条链 {} 格）",
                    bestId, builtin ? "（内置）" : "", String.format("%.1f", bestDist));
        }
        // 【1.14】淡入：声音出现后的头 FADE_IN_TICKS tick 内，增益从 FADE_IN_FLOOR 指数升到目标值。
        // 声音挂在「离玩家最近的链方块」上，而不是绑定的那个方块上 ——
        // 否则长扶梯上声音会从十几格外的某个点传过来，听起来方向是错的。
        inst.setPosition(bestNear.getCenter(), fadeInGain(mc, inst, target));
        lastNote = null;
    }

    /**
     * 淡入增益：从 {@link #FADE_IN_FLOOR} 升到 {@code target}，共 {@link #FADE_IN_TICKS} tick。
     *
     * <p>用**指数**（等 dB 步进）而不是线性：响度是对数感知的，线性爬升的头几步在听感上
     * 仍是一跳一跳的。下限不能取 0，原因见 {@link #FADE_IN_FLOOR}。
     */
    private static float fadeInGain(Minecraft mc, EscalatorSoundInstance inst, float target) {
        if (target <= FADE_IN_FLOOR) {
            // 目标本来就极轻（例如距离已衰减到接近 0），没有淡入的余地，直接用它。
            return target;
        }
        long elapsed = mc.level.getGameTime() - inst.startTick;
        if (elapsed <= 0) {
            return FADE_IN_FLOOR;
        }
        if (elapsed >= FADE_IN_TICKS) {
            return target;
        }
        double step = Math.pow(target / (double) FADE_IN_FLOOR, 1.0 / FADE_IN_TICKS);
        return (float) (FADE_IN_FLOOR * Math.pow(step, elapsed));
    }

    /** 只在「没有声音的原因」发生变化时打一条日志。 */
    private static void note(String reason) {
        if (reason.equals(lastNote)) {
            return;
        }
        lastNote = reason;
        LOGGER.info("[SmoothLift/Audio] 未播放：{}", reason);
    }

    // ------------------------------------------------------------------
    // 【1.9】「整条扶梯」的链缓存与距离计算
    // ------------------------------------------------------------------

    /**
     * 需要时重建链缓存：绑定集合变了 / 从没建过 / 缓存过期（{@link #CHAIN_CACHE_TTL_TICKS}）。
     * 绑定集变化用「位置集合的哈希」判断，够用且不用比整个 Map。
     */
    private static void refreshChainCacheIfNeeded(Minecraft mc, Map<BlockPos, String> bindings) {
        int fingerprint = bindings.keySet().hashCode();
        long now = mc.level.getGameTime();
        boolean expired = chainCacheBuiltTick == Long.MIN_VALUE
                || now - chainCacheBuiltTick >= CHAIN_CACHE_TTL_TICKS;
        if (fingerprint == chainCacheFingerprint && !expired && CHAIN_CACHE.size() == bindings.size()) {
            return;
        }
        CHAIN_CACHE.clear();
        for (BlockPos bound : bindings.keySet()) {
            CHAIN_CACHE.put(bound, chainOf(mc, bound));
        }
        chainCacheFingerprint = fingerprint;
        chainCacheBuiltTick = now;
    }

    /**
     * 展开某条扶梯的方块集合（缓存未命中时退回「只有绑定方块自己」）。
     * 客户端区块未加载时 {@code collectChain} 会返回空集，这时退化成单方块行为，不会崩。
     */
    private static List<BlockPos> chainOf(Minecraft mc, BlockPos bound) {
        Set<BlockPos> chain = EscalatorUtil.collectChain(mc.level, bound);
        List<BlockPos> list = new ArrayList<>(chain.size() + 1);
        list.addAll(chain);
        if (!chain.contains(bound)) {
            list.add(bound);
        }
        return list;
    }

    /**
     * 这条扶梯整条链上、离玩家**最近**的那个方块。
     *
     * <p>用「点到方块实心体的距离」而不是「到方块中心的距离」：站在扶梯上时距离是 0，
     * 走开一格才从 1 开始涨 —— 这才是「离开扶梯 N 格」的直觉。
     * 拿不到链（区块没加载 / 那个方块已被拆）时退回绑定方块自己。
     */
    private static BlockPos nearestChainPos(BlockPos bound, Vec3 playerPos) {
        List<BlockPos> chain = CHAIN_CACHE.get(bound);
        if (chain == null || chain.isEmpty()) {
            return bound;
        }
        BlockPos best = bound;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : chain) {
            double d = distanceToBlock(playerPos, p);
            if (d < bestDist) {
                bestDist = d;
                best = p;
            }
        }
        return best;
    }

    /** 点到方块实心体（1×1×1）的最短距离；玩家在方块内部时返回 0。 */
    private static double distanceToBlock(Vec3 p, BlockPos pos) {
        double dx = Math.max(0.0, Math.max(pos.getX() - p.x, p.x - (pos.getX() + 1.0)));
        double dy = Math.max(0.0, Math.max(pos.getY() - p.y, p.y - (pos.getY() + 1.0)));
        double dz = Math.max(0.0, Math.max(pos.getZ() - p.z, p.z - (pos.getZ() + 1.0)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ------------------------------------------------------------------
    // 【1.11】默认音频：玩家附近最近的扶梯（带缓存）
    // ------------------------------------------------------------------

    /**
     * 附近最近的扶梯方块（半径 {@link #MAX_DISTANCE} 格），结果缓存 {@link #NEARBY_TTL_TICKS} tick。
     * 只有设了默认音频时才会被调用，所以旧存档/没设默认音频的世界完全不受影响。
     */
    private static BlockPos nearestEscalatorNear(Minecraft mc) {
        long now = mc.level.getGameTime();
        if (nearbyBuiltTick != Long.MIN_VALUE && now - nearbyBuiltTick < NEARBY_TTL_TICKS) {
            return nearbyEscalator;
        }
        nearbyBuiltTick = now;
        nearbyEscalator = scanNearestEscalator(mc);
        return nearbyEscalator;
    }

    /**
     * 按球壳由近到远扫描，返回半径内最近的扶梯方块；范围内没有则返回 null。
     *
     * <p>球壳扫描能在「身边就有扶梯」时立刻返回（玩家站在扶梯上通常只查几个方块），
     * 只有附近完全没有扶梯时才会扫满整个立方体（33³ ≈ 3.6 万次 getBlockState）——
     * 客户端区块未加载时 getBlockState 走的是空区块，很快。
     */
    private static BlockPos scanNearestEscalator(Minecraft mc) {
        int radius = (int) MAX_DISTANCE;
        BlockPos center = mc.player.blockPosition();
        for (int r = 0; r <= radius; r++) {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // 只看当前半径这一层壳
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
                            continue;
                        }
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (!EscalatorUtil.isEscalator(mc.level.getBlockState(pos))) {
                            continue;
                        }
                        double dist = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /** 断开连接：停掉所有扶梯声音。 */
    public static void onDisconnect() {
        stopAll(Minecraft.getInstance());
        DECODE_FAILED.clear();
        clearChainCache();
        lastNote = null;
    }

    /**
     * 服务端音频数据同步完成后：停掉旧实例，下一 tick 用新数据重新播放。
     * 顺带清掉「解码失败」记录 —— 玩家可能是重新导入了一个修好的文件，同名也要再试一次。
     */
    public static void onAudioReloaded() {
        stopAll(Minecraft.getInstance());
        DECODE_FAILED.clear();
        // 绑定/音量都可能刚变过，顺手让链缓存失效，下一 tick 重建。
        clearChainCache();
        lastNote = null;
    }

    private static void clearChainCache() {
        CHAIN_CACHE.clear();
        chainCacheFingerprint = Integer.MIN_VALUE;
        chainCacheBuiltTick = Long.MIN_VALUE;
        nearbyEscalator = null;
        nearbyBuiltTick = Long.MIN_VALUE;
    }

    /**
     * 把 OGG 字节解码成 {@link SoundBuffer} 并注入声音引擎缓存。
     * 缓存 key 必须等于 {@code Sound.getPath()} 的值
     * （{@code Sound.SOUND_LISTER} = FileToIdConverter("sounds", ".ogg")：
     * location -&gt; sounds/xxx.ogg），play() 才会命中 computeIfAbsent，
     * 否则会去资源包加载并失败（静音）。
     *
     * @return 是否已注入（缓存里已有同样算成功）
     */
    private static boolean inject(Minecraft mc, String audioId, byte[] bytes) {
        SoundEngine engine = mc.getSoundManager().soundEngine;
        ResourceLocation key = soundCacheKey(audioId);
        if (engine.soundBuffers.cache.containsKey(key)) {
            return true;
        }
        try (OggAudioStream stream = new OggAudioStream(new ByteArrayInputStream(bytes))) {
            ByteBuffer pcm = stream.readAll();
            AudioFormat format = stream.getFormat();
            SoundBuffer buffer = new SoundBuffer(pcm, format);
            engine.soundBuffers.cache.put(key, CompletableFuture.completedFuture(buffer));
            LOGGER.info("[SmoothLift/Audio] {} 解码成功：{} 字节 -> {}Hz {} 声道，{}ms",
                    audioId, bytes.length, (int) format.getSampleRate(), format.getChannels(),
                    (int) (1000.0 * pcm.limit() / (format.getFrameSize() * format.getSampleRate())));
            return true;
        } catch (IOException e) {
            // 解码失败：不注入，该音频不播放（保持静音）。
            // MC 用 stb_vorbis 解码，只认 Ogg 容器 + Vorbis 编码：
            // MP3 改扩展名（"Failed to find Ogg header"）、Ogg Opus/FLAC 都会在这里失败。
            DECODE_FAILED.add(audioId);
            LOGGER.warn("[SmoothLift/Audio] 音频 {} 解码失败，这条扶梯将一直静音。原因：{}"
                    + "（MC 只支持 Ogg Vorbis；把 MP3 直接改名成 .ogg 或转成 Ogg Opus 都不行）",
                    audioId, e.getMessage());
            return false;
        }
    }

    /**
     * 音频 ID（= 存档音频文件夹里的文件名）可能包含大写字母、空格或中文，
     * 这种字符串直接塞进 {@link ResourceLocation} 会抛 {@code ResourceLocationException}
     * 并让客户端在渲染线程崩掉。这里把 ID 映射成合法且互不相同的路径：
     * 保留 {@code [a-z0-9._-]}，其余字符转义成 {@code _十六进制_}，末尾拼上 ID 的哈希做区分。
     * 同一个 ID 每次算出的结果都一样（inject 与 resolve 必须用同一个），所以缓存能命中。
     *
     * @return 声音事件的 location（不含前缀与扩展名，如 {@code smoothlift:audio/a_b_1a2b3c4d}）
     */
    private static ResourceLocation soundLocation(String audioId) {
        StringBuilder path = new StringBuilder("audio/a");
        int limit = Math.min(audioId.length(), 48);
        for (int i = 0; i < limit; i++) {
            char c = audioId.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                path.append(c);
            } else if (c >= 'A' && c <= 'Z') {
                path.append('_').append((char) ('a' + (c - 'A')));
            } else {
                path.append('_').append(Integer.toHexString(c)).append('_');
            }
        }
        path.append('_').append(Integer.toHexString(audioId.hashCode()));
        return new ResourceLocation("smoothlift", path.toString());
    }

    /**
     * 声音引擎缓存里 {@link SoundBuffer} 的 key：
     * 必须等于 {@code Sound.getPath()}，即 {@code SOUND_LISTER.idToFile(location)}
     * = {@code location.withPath("sounds/" + path + ".ogg")}。
     */
    private static ResourceLocation soundCacheKey(String audioId) {
        ResourceLocation location = soundLocation(audioId);
        return location.withPath("sounds/" + location.getPath() + ".ogg");
    }

    private static void stopAll(Minecraft mc) {
        if (mc != null && mc.getSoundManager() != null) {
            for (EscalatorSoundInstance inst : ACTIVE.values()) {
                mc.getSoundManager().stop(inst);
            }
        }
        ACTIVE.clear();
    }

    /**
     * 内置音频对应的原版声音事件：{@code smoothlift:audio/<key>}，由模组的
     * {@code assets/smoothlift/sounds.json} 注册，文件在 {@code assets/smoothlift/sounds/audio/<key>.ogg}。
     */
    private static ResourceLocation builtinEvent(String audioId) {
        return new ResourceLocation("smoothlift", "audio/" + EscalatorSpeedManager.builtinKey(audioId));
    }

    /**
     * 自定义声音实例：绑定到一个固定的音频ID。
     *
     * <p>内置音频直接走原版：重写 {@code resolve} 时调用 {@code super} 从注册表
     * （sounds.json）里找事件，引擎自己会从模组资源包读 ogg。
     *
     * <p>玩家放进存档的音频必须重写 {@code resolve} 绕过注册表查找，否则原版默认实现
     * 会因为注册表里没有 smoothlift:audio/&lt;id&gt; 而把 sound 置为 EMPTY_SOUND 并返回 null，
     * play() 会直接放弃。
     */
    private static final class EscalatorSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {
        private final String audioId;
        private final boolean builtin;

        /** 【1.14】本实例首次播放时的游戏时刻，用于淡入（见 {@link #fadeIn}）。 */
        private long startTick = Long.MIN_VALUE;

        EscalatorSoundInstance(String audioId) {
            super(initialLocation(audioId), SoundSource.BLOCKS, RandomSource.create());
            this.audioId = audioId;
            this.builtin = EscalatorSpeedManager.isBuiltinAudio(audioId);
            this.looping = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.relative = false;
            this.volume = 1.0f;
            this.pitch = 1.0f;
        }

        /** 内置音频用原版事件 ID；存档音频用 {@link #soundLocation} 映射出的安全路径。 */
        private static ResourceLocation initialLocation(String audioId) {
            return EscalatorSpeedManager.isBuiltinAudio(audioId) ? builtinEvent(audioId) : soundLocation(audioId);
        }

        @Override
        public WeighedSoundEvents resolve(SoundManager soundManager) {
            if (builtin) {
                return super.resolve(soundManager);
            }
            // location 必须带 "smoothlift:" 前缀，否则默认 namespace 是 minecraft:。
            ResourceLocation name = soundLocation(audioId);
            Sound s = new Sound(name.toString(), ConstantFloat.of(1.0F), ConstantFloat.of(1.0F),
                    1, Sound.Type.FILE, false, false, 0);
            this.sound = s;
            // 引擎会从 WeighedSoundEvents.getSound(random) 里挑一个声源；列表为空则无声，
            // 所以必须把 Sound 也加进去（Sound 实现了 Weighted<Sound>）。
            WeighedSoundEvents events = new WeighedSoundEvents(name, null);
            events.addSound(s);
            return events;
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isStopped() {
            return false;
        }

        @Override
        public boolean canPlaySound() {
            return true;
        }

        void setPosition(Vec3 center, float volume) {
            this.x = center.x;
            this.y = center.y;
            this.z = center.z;
            this.volume = volume;
        }
    }
}
