package smooth.lift;

import java.util.UUID;

/**
 * 1.21.4 专用：视角步幅摆动（view bob）补偿的跨侧交接。
 *
 * <p>1.21.2 起 Mojang 把 {@code walkDist / walkDistO} 从 {@code Entity} 挪到了客户端的
 * {@code AbstractClientPlayer}（公共侧代码够不到它），并且整只游戏里**没有任何地方
 * 再给它累加**：{@code GameRenderer.bobView} 照读这两个字段、
 * {@code AbstractClientPlayer.tick} 照抄 {@code walkDistO = walkDist}，但写
 * {@code walkDist} 的那段代码消失了（1.21.2 的已知回归，表现为玩家走动时视角完全没有
 * 步幅摆动）。已对 1.21.4 客户端 jar 做全量常量池扫描确认：除 bobView 的读取外，
 * 不存在任何 putfield 写入点。
 *
 * <p>本模组在 1.20.4 / 1.21.1 里是公共侧 {@code LivingEntity} 混入直接
 * {@code walkDist += 位移*0.6} 补偿扶梯上的视角摆动；1.21.4 公共侧够不到该字段，
 * 所以公共侧混入把「玩家自己这 tick 的输入位移」通过本类交到客户端，由客户端混入
 * {@code AbstractClientPlayerBobMixin} 在 tick 末尾把它加进 {@code walkDist}。
 *
 * <p>只在**被扶梯接管的 tick** 才写入（公共侧混入的 ride 分支）；正常行走不写
 * （那是原版自己的事，1.21.4 原版已不再累加，属 Mojang 回归，不在本模组职责内）。
 * 单槽 + UUID 校验：真正消费方只有本地玩家，自己的 put 发生在自己 tick 的
 * travel 里、take 发生在同一 tick 的 TAIL，中间不会有别的实体插进来覆盖。
 */
public final class RideBobState {

    private static UUID pendingId = null;
    private static float pendingLen = 0.0F;

    /** 公共侧（travel 混入的 ride 分支，仅被扶梯接管的 tick）写入。 */
    public static void put(UUID entityId, float selfMoveLen) {
        pendingId = entityId;
        pendingLen = selfMoveLen;
    }

    /** 客户端侧（AbstractClientPlayer.tick 末尾）读取；只认自己的 UUID，取走即清。 */
    public static Float take(UUID entityId) {
        if (entityId.equals(pendingId)) {
            pendingId = null;
            return pendingLen;
        }
        return null;
    }

    private RideBobState() {
    }
}
