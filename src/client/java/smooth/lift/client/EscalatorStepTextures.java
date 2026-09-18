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
 * 运行时把 MTR 的阶梯动画贴图整条（16 帧竖排）加载成 SmoothLift 自己的贴图。
 *
 * <h2>为什么需要这个</h2>
 * MTR 的阶梯贴图在贴图集（atlas）里**只占一帧**的槽位 ——
 * {@code AnimationMetadataSection.calculateFrameSize} 在动画没有声明 width/height 时取
 * {@code min(图宽, 图高)}，所以整张竖排图被当成“一帧”注册，MTR 靠每刻把当前帧上传进这个槽位
 * 来播放动画。结果就是：整张地图共用一套动画相位，同一时刻只能有一个速度。
 *
 * <p>SmoothLift 绕开这个限制：自己把整条 16 帧的贴图作为一张普通贴图加载，
 * 每格阶梯面用 {@code v = (帧号 + 帧内v) / 16} 去采样对应那一帧。这样**每个方块都可以
 * 取不同的帧**，于是「每条扶梯各自动各自的速度」才真正成立。
 *
 * <h2>MTR 3.x 与 4.x 的阶梯贴图**不一样**（关键，这里踩过一次大坑）</h2>
 *
 * <table border="1">
 *   <tr><th>阶梯形状</th><th>MTR 3.x</th><th>MTR 4.x</th></tr>
 *   <tr><td>斜坡 slope_*</td>
 *       <td>{@code escalator_up|down.png} 256x4096（一帧 256x256），面 uv 是小窗口</td>
 *       <td>{@code escalator_up|down.png} 320x5120（一帧 320x320），面 uv 是小窗口</td></tr>
 *   <tr><td>平层 flat_* / transition_bottom_*</td>
 *       <td><b>另有一张</b> {@code escalator_flat_up|down.png} 64x1024，
 *           而且面 uv 是<b>整张贴图</b> {@code [0,0,16,16]}</td>
 *       <td>没有独立贴图，与斜坡共用 {@code escalator_up|down.png}，
 *           面 uv 变成小窗口（如 {@code [0,12.8,3.2,16]}）</td></tr>
 * </table>
 *
 * <p><b>为什么必须分开</b>：斜坡那张竖排图的每一帧里只稀疏地排着许多小台阶贴图块，
 * 实测每帧**只有约 6% 的像素是不透明的**（其余全是空隙）；而平层那张 64x1024 每帧
 * **100% 不透明**（整块传送带花纹）。所以一旦把「铺满整张 sprite」的平层面拿去斜坡图里采，
 * 它采到的就是整个 256x256 帧 —— 其中 94% 是透明空隙，在 cutout 层会被 alpha 裁剪直接丢弃，
 * 表现就是「**平层/过渡块的台阶面几乎整片不见**」。这不是"贴图画错"，是"面被裁没了"。
 *
 * <p>判断该用哪一族，只看**面在模型里的 uv 是不是铺满整张 sprite**
 * （{@link EscalatorStepModels} 里的 {@code fullWindow}）：资源覆盖把两族模型的 {@code #step}
 * 都换成了同一个标记 sprite 名字，靠 sprite 名字已经分不出来了。
 *
 * <p>MTR 4.x 没有 {@code escalator_flat_*.png}，所以平层条带按**可选**处理：读不到就自动退回
 * 斜坡条带（4.x 的平层 uv 本来就是小窗口，退回才是对的）。
 *
 * <p>贴图直接从 MTR 的资源包里读（不复制、不打包 MTR 的素材），因此只需要 MTR 在场。
 */
public final class EscalatorStepTextures {

    /** 一条竖排帧贴图的帧数（MTR 3.x / 4.x 都是 16 帧）。 */
    public static final int FRAMES = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    // ---- 我们注册的动态贴图名（每条条带一张） ----
    private static final ResourceLocation STRIP_SLOPE_UP =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "dynamic/escalator_step_up");
    private static final ResourceLocation STRIP_SLOPE_DOWN =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "dynamic/escalator_step_down");
    private static final ResourceLocation STRIP_FLAT_UP =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "dynamic/escalator_step_flat_up");
    private static final ResourceLocation STRIP_FLAT_DOWN =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "dynamic/escalator_step_flat_down");

    // ---- MTR 源贴图 ----
    /** 斜坡阶梯（slope_*）。3.x 与 4.x 都有，只是尺寸不同。 */
    private static final ResourceLocation MTR_SLOPE_UP =
            ResourceLocation.fromNamespaceAndPath("mtr", "textures/block/escalator_up.png");
    private static final ResourceLocation MTR_SLOPE_DOWN =
            ResourceLocation.fromNamespaceAndPath("mtr", "textures/block/escalator_down.png");
    /** 平层阶梯（flat_* / transition_bottom_*），MTR 3.x 独有；4.x 里不存在。 */
    private static final ResourceLocation MTR_FLAT_UP =
            ResourceLocation.fromNamespaceAndPath("mtr", "textures/block/escalator_flat_up.png");
    private static final ResourceLocation MTR_FLAT_DOWN =
            ResourceLocation.fromNamespaceAndPath("mtr", "textures/block/escalator_flat_down.png");

    /** 已经为哪个 ResourceManager 构建过（资源重载后会换实例，于是自动重建）。 */
    private static Object builtFor;
    /** 构建失败后，隔多少刻再重试一次，避免每帧都刷日志。 */
    private static int retryCooldown;

    /** 一条方向的阶梯条带：源图 + 注册名 + 帧序 + 渲染类型。 */
    private static final class Strip {
        /** 日志里用的中文名。 */
        final String label;
        /** 渲染类型名用的 ASCII 键（{@code RenderType.create} 的名字保持纯 ASCII）。 */
        final String key;
        final ResourceLocation png;
        final ResourceLocation id;
        int[] order = identityOrder();
        boolean ready;
        RenderType type;

        Strip(String label, String key, ResourceLocation png, ResourceLocation id) {
            this.label = label;
            this.key = key;
            this.png = png;
            this.id = id;
        }

        private static int[] identityOrder() {
            int[] order = new int[FRAMES];
            for (int i = 0; i < FRAMES; i++) {
                order[i] = i;
            }
            return order;
        }
    }

    private static final Strip SLOPE_UP = new Strip("斜坡上行", "slope_up", MTR_SLOPE_UP, STRIP_SLOPE_UP);
    private static final Strip SLOPE_DOWN =
            new Strip("斜坡下行", "slope_down", MTR_SLOPE_DOWN, STRIP_SLOPE_DOWN);
    private static final Strip FLAT_UP = new Strip("平层上行", "flat_up", MTR_FLAT_UP, STRIP_FLAT_UP);
    private static final Strip FLAT_DOWN = new Strip("平层下行", "flat_down", MTR_FLAT_DOWN, STRIP_FLAT_DOWN);

    private EscalatorStepTextures() {
    }

    /** 斜坡条带（动画的**必需**条件）是否就绪。 */
    public static boolean isReady() {
        return SLOPE_UP.ready && SLOPE_DOWN.ready && SLOPE_UP.type != null && SLOPE_DOWN.type != null;
    }

    /**
     * 取某个阶梯面该用的渲染类型。
     *
     * @param up   上行贴图还是下行贴图
     * @param flat 是否是「铺满整张 sprite」的平层阶梯面（{@link EscalatorStepModels} 的 fullWindow）
     */
    public static RenderType type(boolean up, boolean flat) {
        Strip strip = strip(up, flat);
        return strip.type != null ? strip.type : strip(up, false).type;
    }

    /** 平层条带是否可用（MTR 4.x 没有这张图，此时为 false，会自动退回斜坡条带）。 */
    public static boolean flatAvailable() {
        return FLAT_UP.ready && FLAT_DOWN.ready;
    }

    /**
     * 该阶梯面实际使用的条带。
     *
     * <p>平层贴图缺失（MTR 4.x）时退回斜坡条带：4.x 的平层 uv 本来就是小窗口，
     * 会走非 fullWindow 分支，用斜坡条带才是对的。
     */
    private static Strip strip(boolean up, boolean flat) {
        if (flat && flatAvailable()) {
            return up ? FLAT_UP : FLAT_DOWN;
        }
        return up ? SLOPE_UP : SLOPE_DOWN;
    }

    /**
     * 把动画帧号换算成贴图里的条带序号。
     *
     * <p>MTR 的 mcmeta 用帧序表达方向：up 是 0..15，down 是 15..0，而两张图的像素
     * 内容其实是同一份。所以「向上」和「向下」的差别只在帧序，这里按各自的 mcmeta 重排。
     */
    public static int bandFor(boolean up, boolean flat, int frame) {
        int[] order = strip(up, flat).order;
        int index = Math.floorMod(frame, order.length);
        return order[index];
    }

    /** 该条带一共有多少帧（正常情况下等于 {@link #FRAMES}）。 */
    public static int bandCount(boolean up, boolean flat) {
        return strip(up, flat).order.length;
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

        // 斜坡条带是动画的必需条件；平层条带可选（MTR 4.x 根本没有那两张图）。
        boolean slopeOk = load(minecraft, resources, SLOPE_UP);
        slopeOk &= load(minecraft, resources, SLOPE_DOWN);
        boolean flatOk = load(minecraft, resources, FLAT_UP);
        flatOk &= load(minecraft, resources, FLAT_DOWN);

        if (slopeOk) {
            createRenderType(SLOPE_UP);
            createRenderType(SLOPE_DOWN);
            if (flatOk) {
                createRenderType(FLAT_UP);
                createRenderType(FLAT_DOWN);
            }
            builtFor = resources;
            LOGGER.info("[SmoothLift] 逐条扶梯阶梯动画已启用（{} 帧；斜坡 {}，平层 {}）",
                    FRAMES, MTR_SLOPE_UP,
                    flatOk ? MTR_FLAT_UP.toString() : "无（MTR 4.x 与斜坡共用，自动退回）");
        } else {
            retryCooldown = 100;
            LOGGER.error("[SmoothLift] 读取 MTR 扶梯阶梯贴图失败，暂时回退为静态阶梯（每 5 秒重试一次）");
        }
    }

    /** 读一条条带的源图、帧序，并注册成动态贴图。读不到（4.x 的平层图）返回 false 即可。 */
    private static boolean load(Minecraft minecraft, ResourceManager resources, Strip strip) {
        // 每次重载都重新读一遍，所以成功/失败状态都要重置。
        strip.ready = false;
        try {
            Optional<Resource> optional = resources.getResource(strip.png);
            if (optional.isEmpty()) {
                LOGGER.warn("[SmoothLift] 找不到贴图资源 {}（{}），该族阶梯将退回斜坡贴图", strip.png, strip.label);
                return false;
            }
            Resource resource = optional.get();
            NativeImage image;
            try (InputStream stream = resource.open()) {
                image = NativeImage.read(stream);
            }
            if (image == null || image.getWidth() <= 0 || image.getHeight() < image.getWidth()) {
                LOGGER.error("[SmoothLift] {} 尺寸异常，无法作为竖排帧贴图使用", strip.png);
                return false;
            }

            strip.order = readFrameOrder(resource, image.getHeight() / image.getWidth());
            strip.ready = true;

            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(false, false);
            TextureManager textureManager = minecraft.getTextureManager();
            textureManager.register(strip.id, texture);
            LOGGER.info("[SmoothLift] 阶梯贴图 {}（{}）-> {} ({}x{}, {} 帧)",
                    strip.label, strip.png, strip.id,
                    image.getWidth(), image.getHeight(), image.getHeight() / image.getWidth());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[SmoothLift] 构建阶梯贴图失败: {}", strip.png, t);
            return false;
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
     * **全透明**的（见 {@code _tools/gen_assets.py}）；MTR 的 ESCALATOR_STEP 注册在 cutout 层
     * （{@code RenderLayer.getCutout()}），cutout 会做 alpha 裁剪，全透明像素被直接丢弃 ——
     * 于是 MTR/Sodium 渲染时既不留颜色也不写深度，玩家只会看到我们重绘的这份。
     * （之前用「静态第 0 帧 + 多边形偏移」是为了压过一份仍然可见的静态台阶；
     * 改用全透明底图后，重叠的根源没了，偏移反而可能把台阶面顶穿相邻方块，故去掉。）
     */
    private static void createRenderType(Strip strip) {
        if (strip.type != null) {
            return;
        }
        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                .setTextureState(new RenderStateShard.TextureStateShard(strip.id, false, false))
                .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                .setCullState(RenderStateShard.CULL)
                .setLightmapState(RenderStateShard.LIGHTMAP)
                .setOverlayState(RenderStateShard.OVERLAY)
                .createCompositeState(true);
        strip.type = RenderType.create("smoothlift_escalator_step_" + strip.key,
                DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 1536, true, false, state);
    }
}
