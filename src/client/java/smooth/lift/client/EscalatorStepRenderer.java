package smooth.lift.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import smooth.lift.EscalatorSpeedData;
import smooth.lift.EscalatorSpeedManager;
import smooth.lift.EscalatorUtil;

import java.util.List;

/**
 * 逐条扶梯独立的阶梯动画渲染。
 *
 * <h2>它解决什么问题</h2>
 * MTR 的阶梯贴图是全地图共享的单相位 flipbook，所以「每条扶梯各自动各自的速度」在原版
 * 渲染路径下不可能实现。SmoothLift 的做法是：
 * <ol>
 *   <li>用资源覆盖把 12 个 MTR 阶梯模型（*_up / *_down）的 {@code #step} 贴图换成 SmoothLift
 *       自己的**全透明**底图 —— MTR 的 ESCALATOR_STEP 在 cutout 层，全透明像素会被 alpha 裁剪整片
 *       丢弃，等于把 MTR 原版那份**静止的台阶面彻底隐藏**（不会再和我们画的这份重叠）；</li>
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

    private static long tickCounter;
    private static boolean loggedFailure;
    private static boolean loggedFirstSuccess;

    private EscalatorStepRenderer() {
    }

    public static void register() {
        EscalatorStepIndex.register();
        WorldRenderEvents.AFTER_ENTITIES.register(EscalatorStepRenderer::onAfterEntities);
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

    private static void onAfterEntities(WorldRenderContext context) {
        try {
            render(context);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                LOGGER.error("[SmoothLift] 逐条扶梯阶梯动画渲染出错，已回退为静态阶梯；"
                        + "请把这段堆栈发给作者。", t);
            }
        }
    }

    private static void render(WorldRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = context.world();
        if (level == null || minecraft.player == null || minecraft.level != level) {
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

        MultiBufferSource consumers = context.consumers();
        if (!(consumers instanceof MultiBufferSource.BufferSource buffers)) {
            return;
        }

        RenderType upType = EscalatorStepTextures.upType();
        RenderType downType = EscalatorStepTextures.downType();
        if (upType == null || downType == null) {
            return;
        }

        Camera camera = context.camera();
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
        Frustum frustum = context.frustum();
        double maxDistance = drawDistance(minecraft);
        double maxDistanceSq = maxDistance * maxDistance;

        PoseStack poseStack = context.matrixStack();
        ModelBlockRenderer modelRenderer = minecraft.getBlockRenderer().getModelRenderer();
        VertexConsumer upBuffer = buffers.getBuffer(upType);
        VertexConsumer downBuffer = buffers.getBuffer(downType);
        float partialTick = context.tickDelta();
        int drawn = 0;

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

            checkOverrideOnce(model);
            double speed = EscalatorSpeedManager.getAnimationSpeed(level, pos);
            boolean up = model.up();
            // 刷子刷停（status=false）的扶梯阶梯是静止的：固定显示第 0 帧，不再按速度推进。
            // 否则扶梯虽然停了，单独设置过的速度仍会让阶梯继续滚动。
            boolean stopped = !EscalatorUtil.getBooleanProperty(state, "status", true);
            int band = EscalatorStepTextures.bandFor(up, stopped ? 0 : frameOf(speed, partialTick));
            model.setBand(band, EscalatorStepTextures.bandCount(up));

            poseStack.pushPose();
            poseStack.translate(pos.getX() - cameraX, pos.getY() - cameraY, pos.getZ() - cameraZ);
            modelRenderer.tesselateBlock(level, model, state, pos, poseStack,
                    up ? upBuffer : downBuffer, false, level.random, state.getSeed(pos),
                    OverlayTexture.NO_OVERLAY);
            poseStack.popPose();
            drawn++;
        }

        if (drawn > 0) {
            buffers.endBatch(upType);
            buffers.endBatch(downType);
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
    private static void checkOverrideOnce(EscalatorStepModels.StepModel model) {
        if (loggedFirstSuccess) {
            return;
        }
        loggedFirstSuccess = true;
        LOGGER.info("[SmoothLift] MTR 阶梯模型覆盖已生效，逐条扶梯独立阶梯动画开始工作。");
    }
}
