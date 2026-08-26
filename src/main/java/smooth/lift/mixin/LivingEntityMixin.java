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

    private static final Map<UUID, Boolean> PLAYER_DIRECTION = new HashMap<>();
    private static final Map<UUID, Long> PLAYER_RELEASE_TIME = new HashMap<>();

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void smoothEscalator(Vec3 movementInput, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;

        if (!(self instanceof Player)) return;
        if (self.isPassenger() || self.isFallFlying() || self.isShiftKeyDown()
                || movementInput.lengthSqr() > 0.01) {
            PLAYER_DIRECTION.remove(self.getUUID());
            return;
        }

        Level level = self.level();

        Long releaseTime = PLAYER_RELEASE_TIME.get(self.getUUID());
        if (releaseTime != null) {
            if (level.getGameTime() - releaseTime < RELEASE_COOLDOWN_TICKS) return;
            PLAYER_RELEASE_TIME.remove(self.getUUID());
        }

        BlockPos escalatorPos = findEscalatorBelow(level, self);
        if (escalatorPos == null) {
            PLAYER_DIRECTION.remove(self.getUUID());
            return;
        }

        BlockState state = level.getBlockState(escalatorPos);
        UUID uuid = self.getUUID();
        Direction facing = getFacingProperty(state);

        BlockPos targetPos;
        Vec3 rideDir;

        if (facing != null) {
            Boolean running = escalatorProp(level, escalatorPos, "status");
            if (running != null && !running) {
                PLAYER_DIRECTION.remove(uuid);
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

            Direction horizontal = up ? facing : facing.getOpposite();
            targetPos = up ? endpoints[1] : endpoints[0];

            // 只有 SLOPE 和 TRANSITION_TOP 两种朝向的方块才有阶梯碰撞箱（表面沿 facing 抬升），
            // 其余（FLAT / LANDING / TRANSITION_BOTTOM）都是平的。
            // 平段上若仍给 y=±1，每 tick 会把玩家抬升约 0.07 格，1 秒抬升 ~1.4 格，
            // 表现为玩家被抛起 2-3 格后自由落体再被抛起——不停循环
            String orientation = getOrientation(state).toUpperCase();
            boolean sloped = orientation.equals("SLOPE") || orientation.equals("TRANSITION_TOP");
            double yComponent = sloped ? (up ? 1 : -1) : 0;
            rideDir = new Vec3(horizontal.getStepX(), yComponent, horizontal.getStepZ()).normalize();
        } else {
            BlockPos[] endpoints = findEscalatorEndpoints(level, escalatorPos);
            if (endpoints == null || endpoints.length < 2) return;
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
            rideDir = up
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

        if (rideDir.lengthSqr() < 1.0E-4) return;

        double dx = targetPos.getX() + 0.5 - self.getX();
        double dz = targetPos.getZ() + 0.5 - self.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        double speed = EscalatorSpeedManager.getSpeed(level, escalatorPos) / 20.0;
        speed *= Math.min(1.0, horizontalDist / SLOWDOWN_DISTANCE);

        if (horizontalDist < ARRIVE_DISTANCE) {
            PLAYER_DIRECTION.remove(uuid);
            PLAYER_RELEASE_TIME.put(uuid, level.getGameTime());
            Vec3 exit = new Vec3(rideDir.x, 0, rideDir.z);
            exit = exit.lengthSqr() > 1.0E-4 ? exit.normalize().scale(speed) : Vec3.ZERO;
            self.setDeltaMovement(exit);
            self.setOnGround(true);
            self.fallDistance = 0;
            return;
        }

        self.move(MoverType.SELF, rideDir.scale(speed));
        self.setDeltaMovement(Vec3.ZERO);
        self.setOnGround(true);
        self.fallDistance = 0;
        ci.cancel();
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

        for (int dy = -1; dy <= 0; dy++) {
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