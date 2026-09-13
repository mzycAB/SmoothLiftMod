package smooth.lift.client;

import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
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
 */
public final class EscalatorStepModels {

    /**
     * 由 {@code gen_assets.py} 生成的底图标记。它现在是**全透明**的 —— 内容不重要，
     * 存在的意义是「占住一个 sprite 槽位 + 提供有效 uv」，让这里能靠 sprite **名字**认出
     * MTR 阶梯模型里哪些面是台阶面；同时 MTR 原版渲染它时会被 cutout alpha 裁剪整片丢弃，
     * 于是原版那份静止台阶面被彻底隐藏，只剩我们重绘的这份。
     */
    private static final ResourceLocation MARKER_UP =
            new ResourceLocation("smoothlift", "block/step_static_up");
    private static final ResourceLocation MARKER_DOWN =
            new ResourceLocation("smoothlift", "block/step_static_down");

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
        return new StepModel(source, byDirection, stepQuads, up);
    }

    /** 一个会动的阶梯面：自己的顶点数组 + 帧内归一化 uv，帧号变化时原地改写 uv。 */
    private static final class StepQuad {
        private final BakedQuad quad;
        /** 4 个顶点的 (帧内u, 帧内v)，都是 0..1。 */
        private final float[] normalized = new float[8];
        private int writtenBand = Integer.MIN_VALUE;

        private StepQuad(BakedQuad quad) {
            this.quad = quad;
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

            int[] copy = vertices.clone();
            StepQuad result = new StepQuad(new BakedQuad(copy, source.getTintIndex(),
                    source.getDirection(), sprite, source.isShade()));
            for (int i = 0; i < 4; i++) {
                float u = Float.intBitsToFloat(vertices[i * 8 + 4]);
                float v = Float.intBitsToFloat(vertices[i * 8 + 5]);
                result.normalized[i * 2] = (u - u0) / du;
                result.normalized[i * 2 + 1] = (v - v0) / dv;
            }
            return result;
        }

        /** 把这一面指向贴图的第 band 条（共 bandCount 条）。 */
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
    public static final class StepModel extends ForwardingBakedModel {
        private final List<BakedQuad>[] byDirection;
        private final StepQuad[] quads;
        private final boolean up;
        private int band = Integer.MIN_VALUE;

        @SuppressWarnings("unchecked")
        private StepModel(net.minecraft.client.resources.model.BakedModel wrapped,
                          List<BakedQuad>[] byDirection, List<StepQuad> quads, boolean up) {
            this.wrapped = wrapped;
            this.byDirection = byDirection;
            this.quads = quads.toArray(new StepQuad[0]);
            this.up = up;
        }

        /** 这条扶梯用的是上行贴图还是下行贴图（决定用哪个渲染类型）。 */
        public boolean up() {
            return up;
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
    }
}
