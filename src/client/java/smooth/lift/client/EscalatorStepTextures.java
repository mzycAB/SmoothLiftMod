package smooth.lift.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时把 MTR 的阶梯动画贴图整条加载成 SmoothLift 自己的贴图。
 *
 * <h2>为什么需要这个</h2>
 * MTR 的阶梯贴图在贴图集（atlas）里**只占一帧的槽位** —— {@code AnimationMetadataSection}
 * .calculateFrameSize 在动画没有声明 width/height 时取 {@code min(图宽, 图高)}，所以整张竖排
 * 帧贴图被当成「一帧」注册，MTR 靠每刻把当前帧上传进这个槽位来播放动画。
 * 结果就是：整张地图共用一套动画相位，同一时刻只能有一个速度。
 *
 * <p>SmoothLift 绕开这个限制：自己把整条竖排贴图作为一张普通贴图加载（宽 x 高 = 宽 x 16*宽），
 * 每格阶梯面用 {@code v = (帧号 + 帧内v) / 帧数} 去采样对应那一帧。这样**每个方块都可以取
 * 不同的帧**，于是「每条扶梯各自动各自的速度」才真正成立。
 *
 * <h2>两套贴图（斜段 / 平直段）</h2>
 * MTR 3.x 的阶梯面用**两张互不相干的贴图**：
 * <ul>
 *   <li><b>斜段</b>（{@code escalator_step_slope_*} 模型）：{@code mtr:block/escalator_up|down.png}，
 *       一帧里排着多种阶梯面小图，模型面 uv 是**小窗口**；</li>
 *   <li><b>平直段</b>（{@code escalator_step_flat_*} 与 {@code escalator_step_transition_bottom_*}
 *       模型）：{@code mtr:block/escalator_flat_up|down.png}，帧内是整块传送带花纹，
 *       模型面 uv 是 **0..16 全窗**。</li>
 * </ul>
 * 这两张图的画面完全不同，所以平直面必须用平直贴图采样，不能拿斜段贴图凑 —— 见
 * {@link EscalatorStepRenderer} 里按阶梯类型分组绘制的说明。
 *
 * <p>MTR 4.x 起不再有独立平直贴图（平直面改为引用斜段贴图里的小窗口），所以平直贴图是
 * **可选**的：加载不到就自动退回用斜段贴图，功能不受影响。
 *
 * <p>贴图直接从 MTR 的资源包里读（不复制、不打包 MTR 的素材），因此只需要 MTR 在场。
 */
public final class EscalatorStepTextures {

    /** 每张斜段贴图的帧数（16 帧竖排）。 */
    public static final int FRAMES = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final ResourceLocation STRIP_UP =
            new ResourceLocation("smoothlift", "dynamic/escalator_step_up");
    private static final ResourceLocation STRIP_DOWN =
            new ResourceLocation("smoothlift", "dynamic/escalator_step_down");
    private static final ResourceLocation STRIP_FLAT_UP =
            new ResourceLocation("smoothlift", "dynamic/escalator_step_flat_up");
    private static final ResourceLocation STRIP_FLAT_DOWN =
            new ResourceLocation("smoothlift", "dynamic/escalator_step_flat_down");
    private static final ResourceLocation MTR_UP =
            new ResourceLocation("mtr", "textures/block/escalator_up.png");
    private static final ResourceLocation MTR_DOWN =
            new ResourceLocation("mtr", "textures/block/escalator_down.png");
    private static final ResourceLocation MTR_FLAT_UP =
            new ResourceLocation("mtr", "textures/block/escalator_flat_up.png");
    private static final ResourceLocation MTR_FLAT_DOWN =
            new ResourceLocation("mtr", "textures/block/escalator_flat_down.png");

    /** 已经为哪个 ResourceManager 构建过（资源重载后会换实例，于是自动重建）。 */
    private static Object builtFor;
    /** 构建失败后，隔多少刻再重试一次，避免每帧都刷日志。 */
    private static int retryCooldown;

    private static boolean upReady;
    private static boolean downReady;
    private static boolean flatUpReady;
    private static boolean flatDownReady;
    private static RenderType upType;
    private static RenderType downType;
    private static RenderType flatUpType;
    private static RenderType flatDownType;
    private static int[] upOrder = identityOrder();
    private static int[] downOrder = identityOrder();
    private static int[] flatUpOrder = identityOrder();
    private static int[] flatDownOrder = identityOrder();

    private EscalatorStepTextures() {
    }

    /** 斜段贴图是否就绪（这是渲染的前提；平直贴图是可选增强）。 */
    public static boolean isReady() {
        return upReady && downReady && upType != null && downType != null;
    }

    /**
     * 平直段（平放的传送带、坡段两端的过渡块）的独立贴图是否可用。
     * MTR 4.x 没有这张图，此时返回 false，平直面退回按斜段贴图采样。
     */
    public static boolean flatAvailable() {
        return flatUpReady && flatDownReady && flatUpType != null && flatDownType != null;
    }

    public static RenderType upType() {
        return upType;
    }

    public static RenderType downType() {
        return downType;
    }

    public static RenderType flatUpType() {
        return flatUpType;
    }

    public static RenderType flatDownType() {
        return flatDownType;
    }

    /**
     * 某一类阶梯面该用哪个渲染类型。
     *
     * @param flat 该面是否属于平直段（用 MTR 的平直贴图）；平直贴图缺失时自动退回斜段贴图
     * @param up   该面用的是上行还是下行贴图
     */
    public static RenderType type(boolean flat, boolean up) {
        if (flat && flatAvailable()) {
            return up ? flatUpType : flatDownType;
        }
        return up ? upType : downType;
    }

    /**
     * 把动画帧号换算成贴图里的条带序号。
     *
     * <p>MTR 的两个 mcmeta 用帧序表达方向：up 是 0..15，down 是 15..0，而两张图的像素
     * 内容其实是同一份。所以「向上」和「向下」的差别只在帧序，这里按各自的 mcmeta 重排。
     */
    public static int bandFor(boolean flat, boolean up, int frame) {
        int[] order = order(flat, up);
        int index = Math.floorMod(frame, order.length);
        return order[index];
    }

    /** 该方向上贴图一共有多少条帧（正常情况下等于 {@link #FRAMES}）。 */
    public static int bandCount(boolean flat, boolean up) {
        return order(flat, up).length;
    }

    /** 取对应贴图的帧序表；平直贴图缺失时退回斜段贴图，和 {@link #type} 的退化保持一致。 */
    private static int[] order(boolean flat, boolean up) {
        if (flat && flatAvailable()) {
            return up ? flatUpOrder : flatDownOrder;
        }
        return up ? upOrder : downOrder;
    }

    /**
     * 确保贴图已经构建。资源重载后会换 ResourceManager 实例，这里会自动重建。
     * 在渲染线程调用即可，构建只做一次。
     */
    public static void ensureLoaded() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        ResourceManager resources = minecraft.getResourceManager();
        if (resources == null) {
            return;
        }
        if (resources == builtFor) {
            return;
        }
        if (retryCooldown > 0) {
            retryCooldown--;
            return;
        }

        // 资源重载后贴图会换新的 DynamicTexture 实例，先把平直段的旧状态清掉，
        // 免得这次读不到平直贴图却还留着上一轮那个已经失效的 RenderType。
        clearFlat();

        Strip slopeUp = load(minecraft, resources, MTR_UP, STRIP_UP);
        Strip slopeDown = load(minecraft, resources, MTR_DOWN, STRIP_DOWN);
        if (slopeUp == null || slopeDown == null) {
            retryCooldown = 100;
            LOGGER.error("[SmoothLift] 读取 MTR 斜段阶梯贴图失败，暂时回退为静态阶梯（每 5 秒重试一次）");
            return;
        }
        upOrder = slopeUp.order;
        downOrder = slopeDown.order;
        upReady = true;
        downReady = true;
        upType = createRenderType(STRIP_UP);
        downType = createRenderType(STRIP_DOWN);

        // 平直段（行人传送带 / 坡段两端过渡块）用的是 MTR 另一张独立贴图，是可选增强：
        // MTR 4.x 起没有这张图，读不到就安静地退回斜段贴图。
        Strip flatUp = load(minecraft, resources, MTR_FLAT_UP, STRIP_FLAT_UP);
        Strip flatDown = load(minecraft, resources, MTR_FLAT_DOWN, STRIP_FLAT_DOWN);
        if (flatUp != null && flatDown != null) {
            flatUpOrder = flatUp.order;
            flatDownOrder = flatDown.order;
            flatUpReady = true;
            flatDownReady = true;
            flatUpType = createRenderType(STRIP_FLAT_UP);
            flatDownType = createRenderType(STRIP_FLAT_DOWN);
        } else {
            LOGGER.info("[SmoothLift] 没有找到 MTR 平直段阶梯贴图 {}，平直阶梯面将复用斜段贴图",
                    MTR_FLAT_UP);
        }

        builtFor = resources;
        LOGGER.info("[SmoothLift] 逐条扶梯阶梯动画已启用（斜段 {} 帧，平直段贴图{}）",
                FRAMES, flatAvailable() ? "已加载" : "缺失（复用斜段）");
    }

    private static void clearFlat() {
        flatUpReady = false;
        flatDownReady = false;
        flatUpType = null;
        flatDownType = null;
        flatUpOrder = identityOrder();
        flatDownOrder = identityOrder();
    }

    /** 读取一张竖排帧贴图并注册成运行时贴图；读不到返回 null（不刷错误日志）。 */
    private static Strip load(Minecraft minecraft, ResourceManager resources,
                              ResourceLocation png, ResourceLocation textureId) {
        try {
            Optional<Resource> optional = resources.getResource(png);
            if (optional.isEmpty()) {
                return null;
            }
            Resource resource = optional.get();
            NativeImage image;
            try (InputStream stream = resource.open()) {
                image = NativeImage.read(stream);
            }
            if (image == null || image.getWidth() <= 0 || image.getHeight() < image.getWidth()) {
                LOGGER.error("[SmoothLift] {} 尺寸异常，无法作为竖排帧贴图使用", png);
                return null;
            }

            int frames = image.getHeight() / image.getWidth();
            int[] order = readFrameOrder(resource, frames);

            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(false, false);
            TextureManager textureManager = minecraft.getTextureManager();
            textureManager.register(textureId, texture);
            LOGGER.info("[SmoothLift] 阶梯贴图 {} -> {} ({}x{}, {} 帧)",
                    png, textureId, image.getWidth(), image.getHeight(), frames);
            return new Strip(order);
        } catch (Throwable t) {
            LOGGER.error("[SmoothLift] 构建阶梯贴图失败: {}", png, t);
            return null;
        }
    }

    /** 一张竖排帧贴图的帧序（来自同名 .mcmeta；读不到就是 0..n-1）。 */
    private static final class Strip {
        private final int[] order;

        private Strip(int[] order) {
            this.order = order;
        }
    }

    /** 从 png 同名 .mcmeta 里读出动画帧序；读不到就按顺序 0..n-1。 */
    private static int[] readFrameOrder(Resource resource, int frames) {
        int[] order = identityOrder(frames);
        try {
            Optional<AnimationMetadataSection> section =
                    resource.metadata().getSection(AnimationMetadataSection.SERIALIZER);
            if (section.isEmpty()) {
                return order;
            }
            int[] cursor = {0};
            Map<Integer, Integer> temporary = new HashMap<>();
            section.get().forEachFrame((index, time) -> {
                if (cursor[0] < order.length) {
                    temporary.put(cursor[0], index);
                    cursor[0]++;
                }
            });
            for (int i = 0; i < order.length; i++) {
                Integer index = temporary.get(i);
                if (index == null || index < 0 || index >= frames) {
                    return identityOrder(frames);
                }
                order[i] = index;
            }
        } catch (Throwable t) {
            LOGGER.warn("[SmoothLift] 解析 {} 的动画帧序失败，按顺序播放", resource.sourcePackId(), t);
        }
        return order;
    }

    private static int[] identityOrder() {
        return identityOrder(FRAMES);
    }

    private static int[] identityOrder(int frames) {
        int[] order = new int[frames];
        for (int i = 0; i < frames; i++) {
            order[i] = i;
        }
        return order;
    }

    /**
     * 造一个和原版 {@code cutout} 一样的渲染类型，只是换成我们自己的贴图。
     * 顶点格式必须是 BLOCK（位置/颜色/uv/光照/法线），因为阶梯模型是方块模型。
     *
     * <p>这里**不再需要多边形偏移**：MTR 原版那份静止台阶面已经被彻底隐藏了。
     * 原理：资源覆盖把 18 个阶梯模型的 {@code #step} 指向 SmoothLift 的底图，而这张底图是
     * **全透明**的；MTR 的 ESCALATOR_STEP 注册在 cutout 层（{@code RenderLayer.getCutout()}），
     * cutout 会做 alpha 裁剪，全透明像素被直接丢弃 —— 于是 MTR/Sodium 渲染时既不留颜色也不写
     * 深度，玩家只会看到我们重绘的这份。
     * （之前用「静态第 0 帧 + 多边形偏移」是为了压过一份仍然可见的静态台阶；
     * 改用全透明底图后，重叠的根源没了，偏移反而可能把台阶面顶穿相邻方块，故去掉。）
     */
    private static RenderType createRenderType(ResourceLocation texture) {
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true);
        return RenderType.create("smoothlift_escalator_step", DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 1536, true, false, state);
    }
}
