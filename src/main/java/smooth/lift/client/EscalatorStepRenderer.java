package smooth.lift.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐条扶梯独立的阶梯动画渲染。
 *
 * <h2>它解决什么问题</h2>
 * MTR 的阶梯贴图是全地图共享的单相位 flipbook，所以「每条扶梯各自动各自的速度」在原版
 * 渲染路径下不可能实现。SmoothLift 的做法是：
 * <ol>
 *   <li>用资源覆盖把 18 个 MTR 阶梯模型（*_up / *_down / *_stop）的 {@code #step} 贴图换成
 *       SmoothLift 自己的**全透明**底图 —— MTR 的 ESCALATOR_STEP 在 cutout 层，全透明像素会被
 *       alpha 裁剪整片丢弃，等于把 MTR 原版那份**静止的台阶面彻底隐藏**（不会再和我们画的这份重叠）；</li>
 *   <li>自己在这一帧里，对视野内每个阶梯方块按**它自己的速度**算出该显示第几帧，
 *       把这个方块的阶梯面单独画一遍（见 {@link EscalatorStepModels}）。</li>
 * </ol>
 *
 * <h2>渲染范围</h2>
 * 因为静止的那份已经被隐藏，**没画到的地方就是空的（露空）**，所以绘制范围必须覆盖玩家的
 * 整个可视距离，而不是一个固定值：{@link #drawDistance} 直接取客户端的有效渲染距离
 * （{@code Options.getEffectiveRenderDistance()}，会取服务端视距的较小值）再留一点余量。
 * 这样「能看见的区块」和「能看见的台阶」永远是同一批，不会出现「走远了台阶就凭空消失」。
 *
 * <p>为了让放大范围不至于白烧性能，这里用渲染上下文给的 {@code Frustum} 做真正的视锥剔除：
 * 每个阶梯方块的盒子随索引一起缓存（见 {@link EscalatorStepIndex#boxes()}），每帧只做一次
 * {@code isVisible} 判定，没有额外分配。旧版那个「点积 + 距离」的粗糙剔除退化为没有视锥时的兜底。
 *
 * <h2>为什么必须「一组 RenderType 一趟画完」</h2>
 * 阶梯面其实有**四组**（斜坡上/下、平层上/下），而且平层与斜坡在 MTR 3.x 里是**两张不同的贴图**
 * （见 {@link EscalatorStepTextures}），所以每组必须有自己的 RenderType。
 *
 * <p>但 {@code MultiBufferSource.BufferSource} 对**没有登记进 {@code fixedBuffers} 的自定义
 * RenderType** 只养**一个共享 BufferBuilder**（{@code getBuilderRaw} 都返回同一个 builder）。
 * 于是「先把四个 consumer 都取出来、再混着写顶点」的写法会坏在两点：
 * <ul>
 *   <li>四个引用其实是**同一个对象** —— 所有顶点都进了同一批；</li>
 *   <li>{@code getBuffer(type)} 会先把上一个类型刷掉（刷的是**空**批），而
 *       {@code endBatch(type)} 在「{@code lastState != type} 且用的是共享 builder」时
 *       **直接 return 什么都不画**。结果只有最后一次 {@code endBatch} 真的落笔，
 *       而且用的是**它那一张**贴图 —— 所有阶梯面都被贴上同一张图
 *       （表现为：所有扶梯的台阶都朝同一个方向滚，因为两族图只是 mcmeta 帧序相反，
 *       肉眼极难发现）。</li>
 * </ul>
 * 所以这里改成：**一趟只 {@code getBuffer} 一种类型 → 画完它那一组 → 立刻 {@code endBatch(该类型)}**。
 * 此刻 {@code lastState} 正是该类型，{@code endBatch} 才会真的按它自己的贴图把这一批刷出去。
 *
 * <p>为了让「一趟一趟画」不至于把视锥剔除做四遍，先做**一趟**剔除把方块按组塞进四个桶
 * （{@link Group#bucket}，渲染线程单线程复用，不清空重建就不产生额外分配），再逐组取 buffer 绘制。
 *
 * <p>阶梯动画速度取自 {@link EscalatorSpeedManager#getAnimationSpeed}：单独设置过就用单独值，
 * 否则跟随这条扶梯自己的运行速度。
 */
public final class EscalatorStepRenderer {

    /**
     * 绘制距离下限（格）。即使玩家把渲染距离调到最小，也至少画这么远，
     * 免得近处就出现空洞。
     */
    private static final double MIN_DRAW_DISTANCE = 64.0;
    /**
     * 在渲染距离之外多画一段（格）。渲染距离边缘本来就有雾，多画一点可以避免
     * 「区块边界 / 雾区边界」上恰好露空的观感问题。
     */
    private static final double DRAW_DISTANCE_MARGIN = 32.0;
    /** 相机背后多远以内仍然渲染（格）。只在拿不到视锥时用作兜底剔除。 */
    private static final double BEHIND_THRESHOLD = -8.0;
    /** 速度上限对应的帧倍率保护，避免极端数值算出无意义的大数。 */
    private static final double MAX_FRAME_FACTOR = 400.0;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 一个绘制组：一种 RenderType（对应一对 贴图族×上下行）+ 属于它的方块桶。 */
    private static final class Group {
        final boolean up;
        final boolean flat;
        /** 本组这一帧要画的方块（复用的，每帧先 clear）。 */
        final List<BlockPos> bucket = new ArrayList<>();

        Group(boolean up, boolean flat) {
            this.up = up;
            this.flat = flat;
        }
    }

    private static final Group GROUP_SLOPE_UP = new Group(true, false);
    private static final Group GROUP_SLOPE_DOWN = new Group(false, false);
    private static final Group GROUP_FLAT_UP = new Group(true, true);
    private static final Group GROUP_FLAT_DOWN = new Group(false, true);
    /** 绘制顺序固定为：斜坡上、斜坡下、平层上、平层下。 */
    private static final Group[] GROUPS = {
            GROUP_SLOPE_UP, GROUP_SLOPE_DOWN, GROUP_FLAT_UP, GROUP_FLAT_DOWN};

    private static long tickCounter;
    private static boolean loggedFailure;
    private static boolean loggedFirstSuccess;

    private EscalatorStepRenderer() {
    }

    /** 每客户端刻：推进动画时钟、维护索引。 */
    public static void onClientTick(Minecraft minecraft) {
        tickCounter++;
        EscalatorStepModels.tickReloadCheck();
        EscalatorStepIndex.tick(minecraft == null ? null : minecraft.level);
    }

    public static void onDisconnect() {
        tickCounter = 0L;
        loggedFirstSuccess = false;
        EscalatorStepIndex.reset();
        EscalatorStepModels.clear();
    }

    /**
     * 这一帧最远要画到多少格。
     *
     * <p>用「有效渲染距离」（客户端设置与服务端视距取小）而不是固定的 128：静态阶梯面已被隐藏，
     * 画不到就等于没有，所以必须和玩家真正能看到地形的范围对齐。格外再补一个区块加一点余量。
     */
    private static double drawDistance(Minecraft minecraft) {
        int chunks = minecraft.options.getEffectiveRenderDistance();
        if (chunks <= 0) {
            chunks = 8;
        }
        return Math.max(MIN_DRAW_DISTANCE, (chunks + 1) * 16.0 + DRAW_DISTANCE_MARGIN);
    }

    /** FORGE 总线回调：只在 AFTER_ENTITIES 阶段绘制。 */
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        try {
            render(event);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.error("[SmoothLift] 逐条扶梯阶梯动画渲染出错，已回退为静态阶梯；"
                        + "请把这段堆栈发给作者。", t);
            }
        }
    }

    /** 一个阶梯模型该进哪一组（平层贴图缺失时退回斜坡组，这样才和它实际用的贴图一致）。 */
    private static Group groupFor(EscalatorStepModels.StepModel model) {
        boolean flat = model.flat() && EscalatorStepTextures.flatAvailable();
        if (flat) {
            return model.up() ? GROUP_FLAT_UP : GROUP_FLAT_DOWN;
        }
        return model.up() ? GROUP_SLOPE_UP : GROUP_SLOPE_DOWN;
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null || minecraft.player == null) {
            return;
        }

        if (!EscalatorStepTextures.isReady()) {
            EscalatorStepTextures.ensureLoaded();
            if (!EscalatorStepTextures.isReady()) {
                return;
            }
        }

        List<BlockPos> positions = EscalatorStepIndex.positions();
        if (positions.isEmpty()) {
            return;
        }
        AABB[] boxes = EscalatorStepIndex.boxes();

        MultiBufferSource consumers = minecraft.renderBuffers().bufferSource();
        if (!(consumers instanceof MultiBufferSource.BufferSource buffers)) {
            return;
        }

        // 斜坡条带是动画的必需条件；平层条带（MTR 3.x 独有）由 EscalatorStepTextures 内部兜底，
        // type(up, true) 在平层图缺失时会自动返回斜坡类型。
        RenderType slopeUpType = EscalatorStepTextures.type(true, false);
        RenderType slopeDownType = EscalatorStepTextures.type(false, false);
        if (slopeUpType == null || slopeDownType == null) {
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        double cameraX = cameraPos.x;
        double cameraY = cameraPos.y;
        double cameraZ = cameraPos.z;
        float pitch = (float) Math.toRadians(camera.getXRot());
        float yaw = (float) Math.toRadians(camera.getYRot());
        double lookX = -Math.sin(yaw) * Math.cos(pitch);
        double lookY = -Math.sin(pitch);
        double lookZ = Math.cos(yaw) * Math.cos(pitch);

        // 视锥是 WorldRenderer 每帧准备好的（和剔除区块、实体用的是同一个），
        // 坐标系是绝对世界坐标，所以能直接拿方块盒子去测。拿不到时退回「点积 + 距离」。
        Frustum frustum = event.getFrustum();
        double maxDistance = drawDistance(minecraft);
        double maxDistanceSq = maxDistance * maxDistance;

        PoseStack poseStack = event.getPoseStack();
        ModelBlockRenderer modelRenderer = minecraft.getBlockRenderer().getModelRenderer();
        float partialTick = event.getPartialTick();

        // 1) 一趟剔除 + 分桶：把视野内的阶梯方块按「贴图族 × 上下行」塞进四个桶。
        //    分桶是必须的，原因见类注释：自定义 RenderType 共用一个 BufferBuilder，
        //    混着写会把所有面都用最后那张贴图画出去。
        for (Group group : GROUPS) {
            group.bucket.clear();
        }
        boolean sawAny = false;
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            double dx = pos.getX() + 0.5 - cameraX;
            double dy = pos.getY() + 0.5 - cameraY;
            double dz = pos.getZ() + 0.5 - cameraZ;
            if (dx * dx + dy * dy + dz * dz > maxDistanceSq) {
                continue;
            }
            if (frustum != null) {
                AABB box = i < boxes.length ? boxes[i] : null;
                if (box != null && !frustum.isVisible(box)) {
                    continue;
                }
            } else if (dx * lookX + dy * lookY + dz * lookZ < BEHIND_THRESHOLD) {
                continue;
            }

            BlockState state = level.getBlockState(pos);
            EscalatorStepModels.StepModel model = EscalatorStepModels.get(state);
            if (model == null) {
                continue;
            }
            sawAny = true;
            groupFor(model).bucket.add(pos);
        }
        if (!sawAny) {
            return;
        }
        checkOverrideOnce();

        // 2) 一组一趟：取本组的 buffer → 画完本组 → 立刻 endBatch（一旦混进第二种类型，
        //    这一批就会被按错误的贴图刷出去，甚至完全不画）。
        for (Group group : GROUPS) {
            List<BlockPos> bucket = group.bucket;
            if (bucket.isEmpty()) {
                continue;
            }
            RenderType type = EscalatorStepTextures.type(group.up, group.flat);
            VertexConsumer buffer = buffers.getBuffer(type);
            for (int i = 0; i < bucket.size(); i++) {
                BlockPos pos = bucket.get(i);
                BlockState state = level.getBlockState(pos);
                EscalatorStepModels.StepModel model = EscalatorStepModels.get(state);
                if (model == null) {
                    continue;
                }

                double speed = EscalatorSpeedManager.getAnimationSpeed(level, pos);
                // 刷子刷停（status=false）的扶梯阶梯是静止的：固定显示第 0 帧，不再按速度推进。
                // 否则扶梯虽然停了，单独设置过的速度仍会让阶梯继续滚动。
                boolean stopped = !EscalatorUtil.getBooleanProperty(state, "status", true);
                int band = EscalatorStepTextures.bandFor(group.up, group.flat,
                        stopped ? 0 : frameOf(speed, partialTick));
                model.setBand(band, EscalatorStepTextures.bandCount(group.up, group.flat));

                poseStack.pushPose();
                try {
                    poseStack.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
                    modelRenderer.tesselateBlock(level, model, state, pos, poseStack,
                            buffer, false, level.random, state.getSeed(pos),
                            OverlayTexture.NO_OVERLAY);
                } finally {
                    // 无论绘制是否抛异常都要把矩阵弹回来，否则原版 renderLevel 末尾会因为
                    // 「Pose stack not empty」直接崩游戏。
                    poseStack.popPose();
                }
            }
            buffers.endBatch(type);
        }
    }

    /**
     * 这条扶梯当前应该播到第几帧。
     *
     * <p>帧号 = floor((刻计数 + 部分刻) × 倍率) mod 总帧数，倍率 = 速度 / 原版标定速度。
     * 用刻计数直接算而不是累加，是为了让「同速度的扶梯永远同步、不同速度的扶梯相位不同」，
     * 而且是无状态的、任何时刻都不会漂移。
     */
    private static int frameOf(double speed, float partialTick) {
        double factor = speed / EscalatorSpeedData.VANILLA_STEP;
        if (Double.isNaN(factor) || factor <= 0.0) {
            return 0;
        }
        if (factor > MAX_FRAME_FACTOR) {
            factor = MAX_FRAME_FACTOR;
        }
        long total = (long) Math.floor((tickCounter + partialTick) * factor);
        return (int) Math.floorMod(total, EscalatorStepTextures.FRAMES);
    }

    /**
     * 第一次真的画到阶梯面时打一条日志。能走到这里就说明资源覆盖生效了
     * （MTR 的阶梯模型里出现了我们的底图 sprite —— 它现在是「全透明标记」，
     * 只用来让 {@link EscalatorStepModels#get} 认出哪些面是台阶面），
     * 否则 {@code get} 会返回 null、这个分支永远不会到达。
     */
    private static void checkOverrideOnce() {
        if (loggedFirstSuccess) {
            return;
        }
        loggedFirstSuccess = true;
        LOGGER.info("[SmoothLift] MTR 阶梯模型覆盖已生效，逐条扶梯独立阶梯动画开始工作。");
    }
}
