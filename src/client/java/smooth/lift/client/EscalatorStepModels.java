package smooth.lift.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每个扶梯阶梯 BlockState 的「可动画模型」缓存。
 *
 * <p>做法：拿到 MTR 那个 state 的 baked model，把其中用我们静态底图（
 * {@code smoothlift:block/step_static_up|down}）的那些面挑出来 —— 也就是会动的阶梯面 ——
 * 复制一份自己的顶点数组出来，并记下每个顶点的「帧内归一化 uv」。
 * 之后每次渲染只要把帧号写进 uv 的 v 分量（{@code v = (条带号 + 帧内v) / 总条带数}），
 * 就等价于「这个方块正在播放第几帧」，而且**零分配**（顶点数组原地改写）。
 *
 * <p>用 {@code #particle}（静态侧板/外壳）的面不归我们管，留给 MTR 自己按原版渲染。
 *
 * <p>注意 MTR 3.x 的阶梯面其实是**两族贴图**：斜坡（{@code slope_*}）用
 * {@code mtr:block/escalator_up|down}（面 uv 是小窗口），平层（{@code flat_*} /
 * {@code transition_bottom_*}）用的是**另一张** {@code mtr:block/escalator_flat_up|down}
 * （面 uv 铺满整张 sprite）。资源覆盖把两族模型的 {@code #step} 都换成了同一个标记 sprite，
 * 靠 sprite 名字分不出来，所以这里额外记下「这个模型的面是不是铺满整张 sprite」
 * （{@code fullWindow}），由渲染方据此去挑对应的条带贴图（见 {@link EscalatorStepTextures}）。
 * uv 的帧内换算两族完全一致，不需要任何特殊处理。
 */
public final class EscalatorStepModels {

    /**
     * 由 {@code gen_assets.py} 生成的底图标记。它现在是**全透明**的 —— 内容不重要，
     * 存在的意义是「占住一个 sprite 槽位 + 提供有效 uv」，让这里能靠 sprite **名字**认出
     * MTR 阶梯模型里哪些面是台阶面；同时 MTR 原版渲染它时会被 cutout alpha 裁剪整片丢弃，
     * 于是原版那份静止台阶面被彻底隐藏，只剩我们重绘的这份。
     */
    private static final ResourceLocation MARKER_UP =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "block/step_static_up");
    private static final ResourceLocation MARKER_DOWN =
            ResourceLocation.fromNamespaceAndPath("smoothlift", "block/step_static_down");

    private static final Logger LOGGER = LoggerFactory.getLogger("smoothlift");

    /** 方块模型里的方向槽位：0..5 是六个方向，6 是「无方向」那组（没有 cullface 的面）。 */
    private static final int NO_DIRECTION = 6;

    private static final Map<BlockState, StepModel> CACHE = new HashMap<>();
    private static final Set<BlockState> NOT_ANIMATED = new HashSet<>();
    private static final RandomSource BAKE_RANDOM = RandomSource.create(0L);

    private static Object builtFor;

    private EscalatorStepModels() {
    }

    /** 返回这个 state 的阶梯面动画模型；不是「会动的阶梯」则返回 null。 */
    public static StepModel get(BlockState state) {
        if (state == null) {
            return null;
        }
        StepModel cached = CACHE.get(state);
        if (cached != null) {
            return cached;
        }
        if (NOT_ANIMATED.contains(state)) {
            return null;
        }
        StepModel built = build(state);
        if (built == null) {
            NOT_ANIMATED.add(state);
            return null;
        }
        CACHE.put(state, built);
        return built;
    }

    /** 资源重载后贴图集里的 sprite 会换新，已缓存的 uv 必须丢掉重建。 */
    public static void tickReloadCheck() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        Object current = minecraft.getResourceManager();
        if (current == builtFor) {
            return;
        }
        builtFor = current;
        clear();
    }

    public static void clear() {
        CACHE.clear();
        NOT_ANIMATED.clear();
    }

    private static StepModel build(BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return null;
        }
        net.minecraft.client.resources.model.BakedModel source;
        try {
            source = minecraft.getModelManager().getBlockModelShaper().getBlockModel(state);
        } catch (Throwable t) {
            LOGGER.error("[SmoothLift] 取方块模型失败: {}", state, t);
            return null;
        }
        if (source == null) {
            return null;
        }

        List<StepQuad> stepQuads = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<BakedQuad>[] byDirection = new List[NO_DIRECTION + 1];
        boolean up = false;
        boolean down = false;

        for (int slot = 0; slot <= NO_DIRECTION; slot++) {
            Direction direction = slot < NO_DIRECTION ? Direction.from3DDataValue(slot) : null;
            List<BakedQuad> sourceQuads = source.getQuads(state, direction, BAKE_RANDOM);
            List<BakedQuad> mine = new ArrayList<>();
            if (sourceQuads != null) {
                for (BakedQuad quad : sourceQuads) {
                    TextureAtlasSprite sprite = quad.getSprite();
                    if (sprite == null || sprite.contents() == null) {
                        continue;
                    }
                    ResourceLocation id = sprite.contents().name();
                    boolean isUp = MARKER_UP.equals(id);
                    boolean isDown = MARKER_DOWN.equals(id);
                    if (!isUp && !isDown) {
                        continue;
                    }
                    StepQuad converted = StepQuad.of(quad, sprite);
                    if (converted == null) {
                        continue;
                    }
                    if (isUp) {
                        up = true;
                    } else {
                        down = true;
                    }
                    mine.add(converted.quad);
                    stepQuads.add(converted);
                }
            }
            byDirection[slot] = mine;
        }

        if (stepQuads.isEmpty()) {
            return null;
        }
        if (up && down) {
            LOGGER.warn("[SmoothLift] 同一个 state 里同时出现了上行和下行阶梯贴图: {}", state);
        }
        // 整个模型属于哪一族贴图：所有阶梯面都「铺满整张 sprite」才是平层族
        // （MTR 3.x 的 flat_* / transition_bottom_*，它们的 #step 面 uv 是 [0,0,16,16]）。
        // 斜坡模型里几十个阶梯面全是小窗口，所以一定不是平层族。
        boolean flat = true;
        for (StepQuad quad : stepQuads) {
            if (!quad.fullWindow) {
                flat = false;
                break;
            }
        }
        return new StepModel(source, byDirection, stepQuads, up, flat);
    }

    /** 一个会动的阶梯面：自己的顶点数组 + 帧内归一化 uv，帧号变化时原地改写 uv。 */
    private static final class StepQuad {
        private final BakedQuad quad;
        /** 4 个顶点的 (帧内u, 帧内v)，都是 0..1。 */
        private final float[] normalized = new float[8];
        /**
         * 这个面是不是「铺满整张 sprite」的平层阶梯面。
         *
         * <p>MTR 3.x 里平层阶梯（{@code flat_*} / {@code transition_bottom_*}）的顶面 uv 是
         * {@code [0,0,16,16]}，也就是把整张 sprite 铺满面（右侧模型是镜像的 {@code [16,0,0,16]}）；
         * 斜坡阶梯的 uv 都是小窗口（例如 {@code [0,0,4,2]}）。资源覆盖把这两族模型的
         * {@code #step} 都换成了同一个标记 sprite，靠 sprite 名字已经分不出族了，
         * 只能靠这个「是否铺满」来区分，进而决定该采 {@code escalator_flat_up|down} 还是
         * {@code escalator_up|down} 那条条带（见 {@link EscalatorStepTextures}）。
         *
         * <p>这一条**必须**分对：斜坡那张竖排图的每一帧里只有约 6% 的像素是不透明的
         * （其余是空隙），平层面如果被拿去那里采，整面会在 cutout 层被裁掉 94%，看起来就是
         * 「平层/过渡块的台阶面渲染不出来」。
         *
         * <p>注意：这只是**选哪条贴图**的判据，uv 的换算规则两族完全一样（都是「整条竖排帧 +
         * 一帧内的帧内坐标」），所以帧内 uv 一律照原样保留、只把帧号加进 v 里。
         */
        private final boolean fullWindow;
        private int writtenBand = Integer.MIN_VALUE;

        private StepQuad(BakedQuad quad, boolean fullWindow) {
            this.quad = quad;
            this.fullWindow = fullWindow;
        }

        static StepQuad of(BakedQuad source, TextureAtlasSprite sprite) {
            int[] vertices = source.getVertices();
            if (vertices.length != 32) {
                return null;
            }
            float u0 = sprite.getU0();
            float u1 = sprite.getU1();
            float v0 = sprite.getV0();
            float v1 = sprite.getV1();
            float du = u1 - u0;
            float dv = v1 - v0;
            if (du == 0.0F || dv == 0.0F) {
                return null;
            }

            float minU = 1.0F, maxU = 0.0F, minV = 1.0F, maxV = 0.0F;
            float[] normalized = new float[8];
            for (int i = 0; i < 4; i++) {
                float u = Float.intBitsToFloat(vertices[i * 8 + 4]);
                float v = Float.intBitsToFloat(vertices[i * 8 + 5]);
                float nu = (u - u0) / du;
                float nv = (v - v0) / dv;
                normalized[i * 2] = nu;
                normalized[i * 2 + 1] = nv;
                minU = Math.min(minU, nu);
                maxU = Math.max(maxU, nu);
                minV = Math.min(minV, nv);
                maxV = Math.max(maxV, nv);
            }
            // 平层顶面是唯一铺满整张 sprite 的面（斜坡都是小窗口），借此区分两族贴图。
            boolean fullWindow = (maxU - minU > 0.9F) && (maxV - minV > 0.9F);
            int[] copy = vertices.clone();
            StepQuad result = new StepQuad(new BakedQuad(copy, source.getTintIndex(),
                    source.getDirection(), sprite, source.isShade(), 0), fullWindow);
            System.arraycopy(normalized, 0, result.normalized, 0, 8);
            return result;
        }

        /**
         * 把这一面指向贴图的第 band 条（共 bandCount 条）。
         *
         * <p>两族阶梯面用的是同一条换算：条带是 {@code bandCount} 帧**竖排**、每帧占满整幅宽，
         * 所以横向帧内坐标 u 原样保留，只把帧号折进纵向坐标 —— {@code v = (band + 帧内v) / bandCount}。
         * 平层那张 64x1024 也是同样的 16 帧竖排，所以无需任何特殊处理。
         */
        void write(int band, int bandCount) {
            if (band == writtenBand) {
                return;
            }
            writtenBand = band;
            int[] vertices = quad.getVertices();
            for (int i = 0; i < 4; i++) {
                vertices[i * 8 + 4] = Float.floatToRawIntBits(normalized[i * 2]);
                vertices[i * 8 + 5] = Float.floatToRawIntBits(
                        (band + normalized[i * 2 + 1]) / bandCount);
            }
        }

        void invalidate() {
            writtenBand = Integer.MIN_VALUE;
        }
    }

    /** 每个 state 一份；只暴露阶梯面，静态面不参与本次绘制。 */
    public static final class StepModel implements net.minecraft.client.resources.model.BakedModel {
        /**
         * 被包装的原模型（原版 BlockModel）。
         *
         * <p>1.21.1 里这一步是 {@code extends ForwardingBakedModel}（Fabric Renderer API 提供的
         * 转发基类，由它持有 {@code wrapped} 字段并转发 5 个非几何查询方法）。
         * 1.21.4 的 FRAPI 模块不再默认带上这个类（而且在 1.21.4 里继续用它还要多引一个实验性
         * 模块），所以这里改成**直接 implements BakedModel + 手写那 5 个委托方法**。
         * 效果完全等价：这 5 个查询一律转给被包装的原模型，本类只重写 {@link #getQuads}。
         */
        private final net.minecraft.client.resources.model.BakedModel wrapped;
        private final List<BakedQuad>[] byDirection;
        private final StepQuad[] quads;
        private final boolean up;
        private final boolean flat;
        private int band = Integer.MIN_VALUE;

        @SuppressWarnings("unchecked")
        private StepModel(net.minecraft.client.resources.model.BakedModel wrapped,
                          List<BakedQuad>[] byDirection, List<StepQuad> quads, boolean up, boolean flat) {
            this.wrapped = wrapped;
            this.byDirection = byDirection;
            this.quads = quads.toArray(new StepQuad[0]);
            this.up = up;
            this.flat = flat;
        }

        /** 这条扶梯用的是上行贴图还是下行贴图（决定用哪个渲染类型）。 */
        public boolean up() {
            return up;
        }

        /**
         * 这个模型属于平层贴图族（MTR 3.x 的 {@code escalator_flat_up|down}）还是斜坡贴图族。
         * 渲染时要按它去挑纹理不同的渲染类型。
         */
        public boolean flat() {
            return flat;
        }

        /** 设定当前要显示的条带（已按方向重排过），相同则不做任何事。 */
        public void setBand(int newBand, int bandCount) {
            if (newBand == band) {
                return;
            }
            band = newBand;
            for (StepQuad quad : quads) {
                quad.write(newBand, bandCount);
            }
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource random) {
            int slot = direction == null ? NO_DIRECTION : direction.get3DDataValue();
            List<BakedQuad> list = byDirection[slot];
            return list == null ? List.of() : list;
        }

        // 下面 5 个是「不重写、直接转发给被包装模型」的查询方法（替代 1.21.1 的
        // ForwardingBakedModel 基类）。少一个都会因为 BakedModel 是接口而编译不过。

        @Override
        public boolean useAmbientOcclusion() {
            return wrapped.useAmbientOcclusion();
        }

        @Override
        public boolean isGui3d() {
            return wrapped.isGui3d();
        }

        @Override
        public boolean usesBlockLight() {
            return wrapped.usesBlockLight();
        }

        @Override
        public TextureAtlasSprite getParticleIcon() {
            return wrapped.getParticleIcon();
        }

        @Override
        public net.minecraft.client.renderer.block.model.ItemTransforms getTransforms() {
            return wrapped.getTransforms();
        }
    }
}
