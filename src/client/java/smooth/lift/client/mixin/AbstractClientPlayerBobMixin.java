package smooth.lift.client.mixin;

import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.RideBobState;

/**
 * 1.21.4 专用：把公共侧 LivingEntity 混入交过来的「玩家自己这 tick 的输入位移」
 * 累加进 {@code AbstractClientPlayer.walkDist}，补偿扶梯贴面运送时的视角步幅摆动。
 *
 * <p>为什么需要它（1.21.2 起的 API 变化）：
 * <ol>
 *   <li>{@code walkDist / walkDistO} 从 {@code Entity} 挪到了 {@code AbstractClientPlayer}
 *       （客户端专属类），公共侧 {@code LivingEntity} 混入在编译期就够不到它；</li>
 *   <li>原版 1.21.4 里这两个字段**没有任何写入点**（已对客户端 jar 做全量常量池扫描：
 *       除 {@code GameRenderer.bobView} 的读取外不存在 putfield）——原版的步幅摆动
 *       在 1.21.2 起是坏的（Mojang 回归）；</li>
 *   <li>本模组 1.20.4 / 1.21.1 的对应逻辑在公共侧直接
 *       {@code walkDist += 位移*0.6}，这里改为在客户端 tick 末尾执行同样的事。</li>
 * </ol>
 *
 * <p>时序：公共侧混入在 ride 分支里 {@code RideBobState.put(uuid, selfLen)}，
 * 发生在本 tick 的 travel 中；本混入在 tick 的 TAIL 取走 —— 同一次 tick 内完成，
 * 且只认自己的 UUID（本地玩家），其余实体（远处玩家 / 生物）的写入不会被消费。
 * 站着被搬（无输入位移）时 put 的是 0，视觉上就是 bob 归零，与 1.20.4 / 1.21.1 一致。
 */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerBobMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void smoothlift$carriedViewBob(CallbackInfo ci) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;
        Float len = RideBobState.take(self.getUUID());
        if (len != null) {
            // 与原版 Entity#move 的系数一致：实际位移 ×0.6 累进 walkDist。
            self.walkDist += len * 0.6F;
        }
    }
}
