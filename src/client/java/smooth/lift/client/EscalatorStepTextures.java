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

/**
 * 运行时把 MTR 的阶梯动画贴图整条（320x5120，16 帧竖排）加载成 SmoothLift 自己的贴图。
 *
 * <h2>为什么需要这个</h2>
 * MTR 的 {@code mtr:block/escalator_up} / {@code escalator_down} 在贴图集（atlas）里
 * **只占一帧 320x320 的槽位** —— {@code AnimationMetadataSection.calculateFrameSize}
 * 在动画没有声明 width/height 时取 {@code min(图宽, 图高)}，所以整张 320x5120 被当成
 * “一帧 320x320”注册，MTR 靠每刻把当前帧上传进这个槽位来播放动画。
 * 结果就是：整张地图共用一套动画相位，同一时刻只能有一个速度。
 *
 * <p>SmoothLift 绕开这个限制：自己把整条 16 帧的贴图作为一张普通贴图加载（320x5120），
 * 每格阶梯面用 {@code v = (帧号 + 帧内v) / 16} 去采样对应那一帧。这样**每个方块都可以
 * 取不同的帧**，于是「每条扶梯各自动各自的速度」才真正成立。
 *
 * <p>贴图直接从 MTR 的资源包里读（不复制、不打包 MTR 的素材），因此只需要 MTR 在场。
 */
public final class EscalatorStepTextures {

    /** MTR 阶梯贴图的帧数（16 帧竖排）。 */
    public static final int FRAMES = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    private static final ResourceLocation STRIP_UP =
            new ResourceLocation("smoothlift", "dynamic/escalator_step_up");
    private static final ResourceLocation STRIP_DOWN =
            new ResourceLocation("smoothlift", "dynamic/escalator_step_down");
    private static final ResourceLocation MTR_UP =
            new ResourceLocation("mtr", "textures/block/escalator_up.png");
    private static final ResourceLocation MTR_DOWN =
            new ResourceLocation("mtr", "textures/block/escalator_down.png");

    /** 已经为哪个 ResourceManager 构建过（资源重载后会换实例，于是自动重建）。 */
    private static Object builtFor;
    /** 构建失败后，隔多少刻再重试一次，避免每帧都刷日志。 */
    private static int retryCooldown;

    private static boolean upReady;
    private static boolean downReady;
    private static RenderType upType;
    private static RenderType downType;
    private static int[] upOrder = identityOrder();
    private static int[] downOrder = identityOrder();

    private EscalatorStepTextures() {
    }

    public static boolean isReady() {
        return upReady && downReady && upType != null && downType != null;
    }

    public static RenderType upType() {
        return upType;
    }

    public static RenderType downType() {
        return downType;
    }

    /**
     * 把动画帧号换算成贴图里的条带序号。
     *
     * <p>MTR 的两个 mcmeta 用帧序表达方向：up 是 0..15，down 是 15..0，而两张图的像素
     * 内容其实是同一份。所以「向上」和「向下」的差别只在帧序，这里按各自的 mcmeta 重排。
     */
    public static int bandFor(boolean up, int frame) {
        int[] order = up ? upOrder : downOrder;
        int index = Math.floorMod(frame, order.length);
        return order[index];
    }

    /** 该方向上贴图一共有多少条帧（正常情况下等于 {@link #FRAMES}）。 */
    public static int bandCount(boolean up) {
        return (up ? upOrder : downOrder).length;
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

        boolean up = load(minecraft, resources, true);
        boolean down = load(minecraft, resources, false);
        if (up && down) {
            upType = createRenderType(STRIP_UP);
            downType = createRenderType(STRIP_DOWN);
            builtFor = resources;
            LOGGER.info("[SmoothLift] 逐条扶梯阶梯动画已启用（贴图 {} 帧，来自 {}）", FRAMES, MTR_UP);
        } else {
            retryCooldown = 100;
            LOGGER.error("[SmoothLift] 读取 MTR 扶梯阶梯贴图失败，暂时回退为静态阶梯（每 5 秒重试一次）");
        }
    }

    private static boolean load(Minecraft minecraft, ResourceManager resources, boolean up) {
        ResourceLocation png = up ? MTR_UP : MTR_DOWN;
        ResourceLocation textureId = up ? STRIP_UP : STRIP_DOWN;
        try {
            java.util.Optional<Resource> optional = resources.getResource(png);
            if (optional.isEmpty()) {
                LOGGER.error("[SmoothLift] 找不到贴图资源 {}", png);
                return false;
            }
            Resource resource = optional.get();
            NativeImage image;
            try (InputStream stream = resource.open()) {
                image = NativeImage.read(stream);
            }
            if (image == null || image.getWidth() <= 0 || image.getHeight() < image.getWidth()) {
                LOGGER.error("[SmoothLift] {} 尺寸异常，无法作为竖排帧贴图使用", png);
                return false;
            }

            int[] order = readFrameOrder(resource, image.getHeight() / image.getWidth());
            if (up) {
                upOrder = order;
                upReady = true;
            } else {
                downOrder = order;
                downReady = true;
            }

            DynamicTexture texture = new DynamicTexture(image);
            texture.setFilter(false, false);
            TextureManager textureManager = minecraft.getTextureManager();
            textureManager.register(textureId, texture);
            LOGGER.info("[SmoothLift] 阶梯贴图 {} -> {} ({}x{}, {} 帧)",
                    png, textureId, image.getWidth(), image.getHeight(), image.getHeight() / image.getWidth());
            return true;
        } catch (Throwable t) {
            LOGGER.error("[SmoothLift] 构建阶梯贴图失败: {}", png, t);
            return false;
        }
    }

    /** 从 png 同名 .mcmeta 里读出动画帧序；读不到就按顺序 0..n-1。 */
    private static int[] readFrameOrder(Resource resource, int frames) {
        int[] order = identityOrder(frames);
        try {
            java.util.Optional<AnimationMetadataSection> section =
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
