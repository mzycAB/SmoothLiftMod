package smooth.lift.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import smooth.lift.EscalatorSpeedManager;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    private static final double ARRIVE_DISTANCE = 0.75;
    private static final double SLOWDOWN_DISTANCE = 1.5;
    private static final int RELEASE_COOLDOWN_TICKS = 40;
    private static final int MAX_WALK_LENGTH = 512;

    /** 脚底相对视觉表面的悬浮量：1/2 格 + 防贴面渲染抖动余量。 */
    private static final double HOVER = 0.50;
    /** 平直传送带/水平站台只需贴碰撞箱顶，仅加微小余量避免贴面抖动。 */
    private static final double FLAT_EPSILON = 0.001;
    /** 每 tick 允许的最小下降量，把下坡落差摊成平滑下降。 */
    private static final double MIN_DESCENT_PER_TICK = 0.15;
    /** 异常恢复时每 tick 允许的最大抬升量。 */
    private static final double ENTRY_RISE = 0.6;

    private static final Map<UUID, Boolean> PLAYER_DIRECTION = new HashMap<>();
    private static final Map<UUID, Long> PLAYER_RELEASE_TIME = new HashMap<>();

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void smoothEscalator(Vec3 movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(self instanceof Player)) return;
        if (self.isPassenger() || self.isFallFlying() || self.isShiftKeyDown()
                || movementInput.lengthSqr() > 0.01
                || self.getDeltaMovement().y > 0.01) {
            PLAYER_DIRECTION.remove(self.getUUID());
            setPhysicsExempt(self, false);
            return;
        }

        Level level = self.level();

        Long releaseTime = PLAYER_RELEASE_TIME.get(self.getUUID());
        if (releaseTime != null) {
            if (level.getGameTime() - releaseTime < RELEASE_COOLDOWN_TICKS) {
                setPhysicsExempt(self, false);
                return;
            }
            PLAYER_RELEASE_TIME.remove(self.getUUID());
        }

        BlockPos escalatorPos = findEscalatorBelow(level, self);
        if (escalatorPos == null) {
            PLAYER_DIRECTION.remove(self.getUUID());
            setPhysicsExempt(self, false);
            return;
        }

        BlockState state = level.getBlockState(escalatorPos);
        UUID uuid = self.getUUID();
        Direction facing = getFacingProperty(state);

        BlockPos targetPos;
        Direction horizontal = null;
        Vec3 fallbackDir = null;

        if (facing != null) {
            Boolean running = escalatorProp(level, escalatorPos, "status");
            if (running != null && !running) {
                PLAYER_DIRECTION.remove(uuid);
                setPhysicsExempt(self, false);
                return;
            }

            BlockPos[] endpoints = findEndpointsAlongAxis(level, escalatorPos, facing);

            Boolean directionProp = escalatorProp(level, escalatorPos, "direction");
            boolean up;
            if (directionProp != null) {
                up = directionProp;
                PLAYER_DIRECTION.put(uuid, up);
            } else {
                Boolean cached = PLAYER_DIRECTION.get(uuid);
                if (cached != null) {
                    up = cached;
                } else {
                    up = distanceSq(self, endpoints[0]) <= distanceSq(self, endpoints[1]);
                    PLAYER_DIRECTION.put(uuid, up);
                }
            }

            horizontal = up ? facing : facing.getOpposite();
            targetPos = up ? endpoints[1] : endpoints[0];
        } else {
            BlockPos[] endpoints = findEscalatorEndpoints(level, escalatorPos);
            if (endpoints == null || endpoints.length < 2) {
                setPhysicsExempt(self, false);
                return;
            }
            BlockPos lowestPos = endpoints[0];
            BlockPos highestPos = endpoints[1];

            boolean up;
            Boolean directionProp = getBooleanProperty(state, "direction");
            if (directionProp != null) {
                up = directionProp;
                PLAYER_DIRECTION.put(uuid, up);
            } else {
                Boolean cached = PLAYER_DIRECTION.get(uuid);
                if (cached != null) {
                    up = cached;
                } else {
                    up = distanceSq(self, lowestPos) <= distanceSq(self, highestPos);
                    PLAYER_DIRECTION.put(uuid, up);
                }
            }

            targetPos = up ? highestPos : lowestPos;
            fallbackDir = up
                    ? new Vec3(
                    highestPos.getX() + 0.5 - (lowestPos.getX() + 0.5),
                    highestPos.getY() + 0.5 - (lowestPos.getY() + 0.5),
                    highestPos.getZ() + 0.5 - (lowestPos.getZ() + 0.5)
            ).normalize()
                    : new Vec3(
                    lowestPos.getX() + 0.5 - (highestPos.getX() + 0.5),
                    lowestPos.getY() + 0.5 - (highestPos.getY() + 0.5),
                    lowestPos.getZ() + 0.5 - (highestPos.getZ() + 0.5)
            ).normalize();
        }

        if (fallbackDir != null && fallbackDir.lengthSqr() < 1.0E-4) {
            setPhysicsExempt(self, false);
            return;
        }

        double dx = targetPos.getX() + 0.5 - self.getX();
        double dz = targetPos.getZ() + 0.5 - self.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        double speed = EscalatorSpeedManager.getSpeed(level, escalatorPos) / 20.0;
        speed *= Math.min(1.0, horizontalDist / SLOWDOWN_DISTANCE);

        if (horizontalDist < ARRIVE_DISTANCE) {
            PLAYER_DIRECTION.remove(uuid);
            PLAYER_RELEASE_TIME.put(uuid, level.getGameTime());
            Vec3 exit = horizontal != null
                    ? new Vec3(horizontal.getStepX(), 0, horizontal.getStepZ()).scale(speed)
                    : flatten(fallbackDir).scale(speed);
            self.setDeltaMovement(exit);
            self.setOnGround(true);
            self.fallDistance = 0;
            setPhysicsExempt(self, false);
            return;
        }

        // 接管移动：服务端关闭方块碰撞校验（noPhysics），
        // 使贴视觉面的位置（AABB 与台阶碰撞箱重叠）不会被 handleMovePlayer 拉回。
        setPhysicsExempt(self, horizontal != null);

        if (horizontal != null) {
            rideOnSurface(self, level, horizontal, speed);
        } else {
            self.move(MoverType.SELF, fallbackDir.scale(speed));
            self.setDeltaMovement(Vec3.ZERO);
            self.setOnGround(true);
            self.fallDistance = 0;
        }
        ci.cancel();
    }

    /**
     * 服务端玩家豁免开关。玩家贴视觉斜面移动时 AABB 会与 MTR 台阶的
     * 碰撞箱重叠（碰撞箱比视觉面高约半格），服务端 handleMovePlayer 的
     * isPlayerCollidingWithAnythingNew 会把这种位置当作"moved wrongly"拉回。
     * noPhysics=true 让服务端跳过该校验并让 move() 无碰撞执行（位移与
     * 申报一致，bl3 偏差归零），从而既贴面又不被拉回。客户端无需豁免：
     * 接管期间不调用 move()，无碰撞副作用。
     */
    private static void setPhysicsExempt(LivingEntity self, boolean exempt) {
        if (!self.level().isClientSide()) {
            self.noPhysics = exempt;
        }
    }

    /**
     * 贴视觉表面移动。MTR 扶梯视觉模型是 32 级/格的细密齿 45° 斜面
     * （escalator_step_slope 模型 + 移动纹理），等效表面高度：
     * - 平台段（LANDING / FLAT / TRANSITION_BOTTOM）：方块顶 +1；
     * - 坡段（SLOPE / TRANSITION_TOP）：方块底 + 块内上坡进度 t。
     * 入口处 SLOPE 底 = 下方平台底 +1，t=0 时与平台顶无缝衔接，
     * 全程零跳变。台阶碰撞箱比该面高约半格，由服务端 noPhysics 豁免。
     */
    private static void rideOnSurface(LivingEntity self, Level level, Direction horizontal, double speed) {
        double stepX = horizontal.getStepX() * speed;
        double stepZ = horizontal.getStepZ() * speed;
        double newX = self.getX() + stepX;
        double newZ = self.getZ() + stepZ;

        double targetY = surfaceLineNear(level, self.getY(), newX, newZ);

        self.setDeltaMovement(Vec3.ZERO);
        self.setOnGround(true);
        self.fallDistance = 0;

        if (Double.isNaN(targetY)) {
            // 目标位置附近找不到扶梯（例如速度极快直接越过终点），退化为纯水平移动
            self.move(MoverType.SELF, new Vec3(stepX, 0, stepZ));
            return;
        }

        double maxDrop = Math.max(MIN_DESCENT_PER_TICK, speed * 1.6);
        double maxRise = ENTRY_RISE + speed;
        double newY = Math.max(Math.min(targetY, self.getY() + maxRise), self.getY() - maxDrop);

        self.setPos(newX, newY, newZ);
    }

    /** 在 (x,z) 竖直 ±3 格内找视觉表面线最接近 refY 的扶梯块；找不到返回 NaN。 */
    private static double surfaceLineNear(Level level, double refY, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int baseY = (int) Math.floor(refY);

        double bestSurface = Double.NaN;
        double bestDiff = Double.MAX_VALUE;
        for (int dy = -3; dy <= 3; dy++) {
            BlockPos pos = new BlockPos(bx, baseY + dy, bz);
            BlockState state = level.getBlockState(pos);
            if (!isEscalator(state)) continue;
            double surface = surfaceLineOfBlock(state, pos, x, z);
            double diff = Math.abs(surface - refY);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestSurface = surface;
            }
        }
        return bestSurface;
    }

    /**
     * MTR 扶梯视觉表面高度（MTR 4.0.5 模型实测）：
     * - FLAT（平直传送带）：梯级带顶 16/16，碰撞箱与视觉面齐平，不能加 HOVER；
     * - LANDING / TRANSITION_BOTTOM：作为斜坡出入口平台，仍用 HOVER 与坡段衔接；
     * - SLOPE / TRANSITION_TOP：45° 细密齿斜面，碰撞箱比视觉面高约半格，用 HOVER。
     */
    private static double surfaceLineOfBlock(BlockState state, BlockPos pos, double x, double z) {
        String orientation = getOrientation(state).toUpperCase();
        switch (orientation) {
            case "SLOPE":
            case "TRANSITION_TOP":
                return pos.getY() + progressAlongFacing(state, pos, x, z) + HOVER;
            case "FLAT":
                return pos.getY() + 1.0 + FLAT_EPSILON;
            default:
                return pos.getY() + 1.0 + HOVER;
        }
    }

    /** 玩家位置沿块 facing（上坡方向）的块内进度，夹到 [0,1]。 */
    private static double progressAlongFacing(BlockState state, BlockPos pos, double x, double z) {
        Direction facing = getFacingProperty(state);
        if (facing == null) return 0;
        double t;
        switch (facing) {
            case NORTH:
                t = pos.getZ() + 1 - z;
                break;
            case SOUTH:
                t = z - pos.getZ();
                break;
            case EAST:
                t = x - pos.getX();
                break;
            case WEST:
                t = pos.getX() + 1 - x;
                break;
            default:
                return 0;
        }
        return Math.max(0.0, Math.min(1.0, t));
    }

    private static Vec3 flatten(Vec3 dir) {
        Vec3 flat = new Vec3(dir.x, 0, dir.z);
        return flat.lengthSqr() > 1.0E-4 ? flat.normalize() : Vec3.ZERO;
    }

    private static Direction getFacingProperty(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("facing".equals(prop.getName()) && prop.getValueClass() == Direction.class) {
                return (Direction) state.getValue(prop);
            }
        }
        return null;
    }

    private static Boolean escalatorProp(Level level, BlockPos pos, String name) {
        Boolean value = getBooleanProperty(level.getBlockState(pos), name);
        if (value == null) {
            value = getBooleanProperty(level.getBlockState(pos.below()), name);
        }
        return value;
    }

    private static BlockPos[] findEndpointsAlongAxis(Level level, BlockPos start, Direction facing) {
        BlockPos bottom = walkToEndpoint(level, start, facing.getOpposite(), "LANDING_BOTTOM");
        BlockPos top = walkToEndpoint(level, start, facing, "LANDING_TOP");
        return new BlockPos[]{bottom, top};
    }

    private static BlockPos walkToEndpoint(Level level, BlockPos start, Direction dir, String landingName) {
        BlockPos current = start;
        for (int i = 0; i < MAX_WALK_LENGTH; i++) {
            BlockPos ahead = current.relative(dir);
            BlockPos next;
            if (isEscalator(level.getBlockState(ahead))) {
                next = ahead;
            } else if (isEscalator(level.getBlockState(ahead.above()))) {
                next = ahead.above();
            } else if (isEscalator(level.getBlockState(ahead.below()))) {
                next = ahead.below();
            } else {
                break;
            }
            current = next;
            if (landingName.equals(getOrientation(level.getBlockState(current)))) {
                return current;
            }
        }
        return current;
    }

    private static Boolean getBooleanProperty(BlockState state, String name) {
        for (Property<?> prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase(name) && prop.getValueClass() == Boolean.class) {
                return (Boolean) state.getValue(prop);
            }
        }
        return null;
    }

    private static double distanceSq(LivingEntity entity, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - entity.getX();
        double dy = (pos.getY() + 0.5) - entity.getY();
        double dz = (pos.getZ() + 0.5) - entity.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isEscalator(BlockState state) {
        return state.getBlock().getClass().getName().toLowerCase().contains("escalator");
    }

    private static BlockPos findEscalatorBelow(Level level, LivingEntity entity) {
        int floorX = (int) Math.floor(entity.getX());
        int floorY = (int) Math.floor(entity.getY() - 0.2);
        int floorZ = (int) Math.floor(entity.getZ());

        for (int dy = -2; dy <= 1; dy++) {
            BlockPos check = new BlockPos(floorX, floorY + dy, floorZ);
            if (isEscalator(level.getBlockState(check))) {
                return check;
            }
        }
        return null;
    }

    private static BlockPos[] findEscalatorEndpoints(Level level, BlockPos start) {
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);

        BlockPos bottomLanding = null;
        BlockPos topLanding = null;
        BlockPos lowest = start;
        BlockPos highest = start;

        while (!queue.isEmpty()) {
            BlockPos pos = queue.poll();
            BlockState state = level.getBlockState(pos);
            String orientation = getOrientation(state);

            if ("LANDING_BOTTOM".equals(orientation)) {
                bottomLanding = pos;
            } else if ("LANDING_TOP".equals(orientation)) {
                topLanding = pos;
            }

            if (pos.getY() < lowest.getY()) lowest = pos;
            if (pos.getY() > highest.getY()) highest = pos;

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.relative(dir);
                if (!visited.contains(neighbor) && isEscalator(level.getBlockState(neighbor))) {
                    visited.add(neighbor);
                    queue.add(neighbor);
                }
            }
        }

        if (bottomLanding != null) lowest = bottomLanding;
        if (topLanding != null) highest = topLanding;

        return new BlockPos[]{lowest, highest};
    }

    private static String getOrientation(BlockState state) {
        for (Property<?> prop : state.getProperties()) {
            if ("orientation".equals(prop.getName())) {
                Object value = state.getValue(prop);
                if (value != null) {
                    return value.toString().toUpperCase();
                }
            }
        }
        return "";
    }
}
