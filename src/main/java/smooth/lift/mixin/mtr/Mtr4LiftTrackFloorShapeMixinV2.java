package smooth.lift.mixin.mtr;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 【1.49】MTR **4.1.x**（1.21.1 / 1.21.4 对应版本）的「电梯楼层轨道」碰撞箱 / 选中框收窄
 * —— 与 {@link Mtr4LiftTrackFloorShapeMixin}（4.0.x 版）做同一件事的另一份实现。
 *
 * <h2>为什么还要第二份（MTR 4.1 把类挪了包，旧 mixin 静默失效）</h2>
 *
 * <p>4.0.x 的楼层轨道方块在 {@code org.mtr.mod.block.BlockLiftTrackFloor}，4.1 起挪到了
 * {@code org.mtr.block.BlockLiftTrackFloor}（4.1 同时弃用了 org.mtr.mapping 那层封装，
 * 形状方法签名里的方向参数从 {@code org.mtr.mapping.holder.Direction} 换成原版
 * {@code net.minecraft.core.Direction}）。而本模组插件 {@link Mtr3LiftMixinPlugin}
 * 的门禁判据是「目标类在不在」（用 {@code getResource} 探测，铁律见该类的类注释）——
 * 4.1 环境下 {@code org.mtr.mod.block.BlockLiftTrackFloor} **不存在** ⇒ 旧 mixin 被
 * **安静地跳过**，碰撞箱回到 4.1 的全宽 {@code (0,0,0,16,16,1)} 薄板。
 *
 * <p>后果正是用户再次报的 bug（用户原话：「为什么电梯楼层轨道没有贴图的地方变成类似于
 * x光方块那样 透明的了」）：模型已被覆盖成 MTR3 的 4px 窄柱，但碰撞箱还是 16px 全宽
 * 隐形墙 ——「看得见的」和「挡得住的」不一致，玩家看着缝隙能过去、走过去却被空气挡住，
 * 感觉像开了 X 光透视。4.0.x 上旧 mixin 生效没有此问题；1.21.1 / 1.21.4（MTR 4.1）
 * 上旧 mixin 失效，问题复发。本 mixin 就是把这份一致性补回 MTR 4.1。
 *
 * <h2>与 4.0.x 版的差异（逐条有 4.1.0-beta.2 字节码背书）</h2>
 * <ul>
 *   <li>目标类：{@code org.mtr.block.BlockLiftTrackFloor}（4.1 新包）；</li>
 *   <li>形状方法：4.1 覆写的是原版 {@code Block.getShape}（intermediary
 *       {@code method_9530}，mojmap {@code getShape}），不再是 4.0.x 自创的
 *       {@code getOutlineShape2}；</li>
 *   <li>{@code IBlock.getVoxelShapeByDirection} 的方向参数是原版
 *       {@code net.minecraft.core.Direction}（4.1 字节码里是 {@code class_2350}，
 *       运行期被 loom 重映射成 mojmap），不是 4.0.x 的
 *       {@code org.mtr.mapping.holder.Direction}；</li>
 *   <li>六个 double 常量不变：{@code (0,0,0,16,16,1)} ⇒ 改法与 4.0.x 完全相同
 *       （minX→6、maxX→10，得到 MTR3 的 {@code (6,0,0,10,16,1)}）。</li>
 * </ul>
 *
 * <p><b>门禁</b>：{@link Mtr3LiftMixinPlugin#shouldApplyMixin} 按类名分派 —— 本 mixin
 * 只在 {@code org.mtr.block.BlockLiftTrackFloor} 存在（装了 MTR 4.1）时应用；
 * 4.0.x / MTR3 / 没装 MTR 时安静跳过。旧 mixin 仍管 4.0.x，两者互不干扰。
 *
 * <h2>★ 为什么这里**不能**写 {@code remap = false}（这次「还是透」的根因，2026-09-20）</h2>
 *
 * <p>Fabric 发布 jar 运行在 **intermediary** 命名下：MTR 4.1 的形状方法运行期叫
 * {@code method_9530}（不是 {@code getShape}）、方向参数类型是 {@code class_2350}
 * （不是 {@code net.minecraft.core.Direction}）。loom 在 remapJar 时会把 mixin 注解里的
 * 字符串（{@code method} / {@code @At target} 描述符）从开发期的 mojmap 名**内联重映射**
 * 成 intermediary 名 —— 前提是注解默认 {@code remap = true}。
 *
 * <p>{@code Mtr4LiftTrackFloorShapeMixin}（4.0.x 版）写 {@code remap = false} 没事，
 * 是因为它的字符串**全是 MTR 自己的名字**（{@code getOutlineShape2}、
 * {@code org.mtr.mapping.holder.Direction}），named / intermediary 下都一样；
 * 而本 mixin 引用了**原版类名**（{@code getShape}、{@code Direction}、{@code VoxelShape}），
 * 一旦 {@code remap = false}，发布 jar 里就还是 mojmap 字符串 ⇒ 运行期匹配不上
 * （{@code getShape} ≠ {@code method_9530}）⇒ 整个 handler 被**安静地跳过** ⇒
 * 碰撞箱没被收窄，用户看到的就是「还是透」。
 *
 * <p>校验：{@code _tools/check-lift-track-look-v41.py} 会读**发布 jar** 里的注解字符串，
 * 断言它们已被重映射成 intermediary（{@code method_9530} / {@code class_2350} /
 * {@code class_265}）—— 防止再静默失效。
 */
@Pseudo
@Mixin(targets = "org.mtr.block.BlockLiftTrackFloor")
public abstract class Mtr4LiftTrackFloorShapeMixinV2 {

    /**
     * 目标调用的完整描述符（与 MTR 4.1.0-beta.2 的字节码逐字一致，intermediary 形式：
     * {@code class_2350}=Direction、{@code class_265}=VoxelShape；1.21.1 与 1.21.4 两个
     * 4.1 jar 均确认）。配 {@code remap = false} 原样进发布 jar，运行期直接命中。
     *
     * <p>写成常量是为了让 {@code _tools/check-lift-track-look-v41.py} 能同时校验
     * 「本文件里的字符串」与「真实 4.1 jar 里的那一行」是同一串 —— 改错一个字母就查得出来
     * （这次 bug 的教训正是「描述符/目标类对不上 = mixin 静默跳过，编译和 jar 校验都发现不了」）。
     */
    private static final String SHAPE_CALL =
            "Lorg/mtr/block/IBlock;getVoxelShapeByDirection(DDDDDDLnet/minecraft/class_2350;)"
                    + "Lnet/minecraft/class_265;";

    /**
     * 目标调用所在的方法：MTR 4.1 覆写的原版 {@code Block.getShape}，运行期（intermediary）
     * 叫 {@code method_9530}。
     *
     * <p>★ 必须写成 **intermediary 名字 + intermediary 描述符**、并配 {@code remap = false}，
     * 不能写 mojmap（{@code getShape} / {@code Direction}），原因（2026-09-20 实测三轮）：
     * <ol>
     *   <li>写 mojmap + {@code remap = false}：发布 jar 里字符串保持 mojmap，运行期
     *       （intermediary）匹配不上 ⇒ mixin 静默跳过（用户看到的「还是透」）；</li>
     *   <li>写 mojmap + 默认 remap（refmap 机制）：注解处理器对**目标类不在编译类路径**
     *       的 mixin（MTR 不是编译依赖），只给 {@code @At target} 描述符生成整串映射、
     *       给 {@code method} 完整签名**只映射描述符类名、不映射方法名**
     *       （{@code getShape} 映射不成 {@code method_9530}）⇒ 运行期名字还是对不上；</li>
     *   <li>⇒ 唯一可靠做法：**intermediary 直写 + {@code remap = false}**（字符串原样进发布
     *       jar，运行期逐字匹配）。代价是 dev 环境（named）不生效 —— 可接受，玩家跑的是
     *       发布 jar，且不匹配只会静默跳过、不会崩。</li>
     * </ol>
     *
     * <p>1.21.1 与 1.21.4 的 MTR 4.1.0-beta.2 字节码里这套名字完全相同（已对两个 jar 分别
     * javap 确认）：{@code method_9530(class_2680,class_1922,class_2338,class_3726)class_265}。
     * 校验：{@code _tools/check-lift-track-look-v41.py} 会读**发布 jar** 断言字符串
     * 仍是这套 intermediary 名（防 remap 配置被改坏）。
     */
    private static final String SHAPE_METHOD =
            "method_9530(Lnet/minecraft/class_2680;"
                    + "Lnet/minecraft/class_1922;"
                    + "Lnet/minecraft/class_2338;"
                    + "Lnet/minecraft/class_3726;)"
                    + "Lnet/minecraft/class_265;";

    /** 把 minX（第 0 个实参）由 0 改成 6 —— MTR3 窄柱的左边界。 */
    @ModifyArg(
            method = SHAPE_METHOD,
            at = @At(value = "INVOKE", target = SHAPE_CALL),
            index = 0,
            remap = false)
    private double smoothlift$narrowMinX(double original) {
        return 6.0;
    }

    /** 把 maxX（第 3 个实参）由 16 改成 10 —— MTR3 窄柱的右边界。 */
    @ModifyArg(
            method = SHAPE_METHOD,
            at = @At(value = "INVOKE", target = SHAPE_CALL),
            index = 3,
            remap = false)
    private double smoothlift$narrowMaxX(double original) {
        return 10.0;
    }
}
