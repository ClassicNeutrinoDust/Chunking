package com.chunking.engine.scheduler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-player kinematic state (position, true tick displacement velocity vector, speed, and look direction)
 * per dimension so both server chunk generation and packet sending steer ahead of fast creative flight.
 */
public final class MoveTracker {
    public static final class PlayerMotion {
        public final double x;
        public final double y;
        public final double z;
        public final double vx;
        public final double vy;
        public final double vz;
        public final double speedXZ;
        public final double dirX;
        public final double dirZ;
        public final double lookX;
        public final double lookZ;
        public final long tick;

        public PlayerMotion(double x, double y, double z, double vx, double vy, double vz,
                            double lookX, double lookZ, long tick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.speedXZ = Math.hypot(vx, vz);
            if (speedXZ > 1e-5D) {
                this.dirX = vx / speedXZ;
                this.dirZ = vz / speedXZ;
            } else {
                double lookLen = Math.hypot(lookX, lookZ);
                this.dirX = lookLen > 1e-5D ? lookX / lookLen : 0.0D;
                this.dirZ = lookLen > 1e-5D ? lookZ / lookLen : 1.0D;
            }
            double lookLen = Math.hypot(lookX, lookZ);
            this.lookX = lookLen > 1e-5D ? lookX / lookLen : this.dirX;
            this.lookZ = lookLen > 1e-5D ? lookZ / lookLen : this.dirZ;
            this.tick = tick;
        }
    }

    public static final PlayerMotion[] EMPTY_MOTIONS = new PlayerMotion[0];

    private final Map<String, PlayerMotion[]> motionsByDimension = new ConcurrentHashMap<>();
    private final Map<Integer, double[]> lastPlayerStateById = new ConcurrentHashMap<>();
    private volatile PlayerMotion[] cachedActiveMotions = EMPTY_MOTIONS;

    /**
     * Records a player's current position and derives real horizontal velocity from position delta
     * (essential for client-controlled creative flight / fly mods where ServerPlayer deltaMovement is zero).
     */
    public PlayerMotion recordPlayerTick(
            int entityId,
            double x,
            double y,
            double z,
            double deltaMovX,
            double deltaMovZ,
            double lookX,
            double lookZ,
            long tick
    ) {
        return recordPlayerTick(entityId, 0, x, y, z, deltaMovX, deltaMovZ, lookX, lookZ, tick);
    }

    public PlayerMotion recordPlayerTick(
            int entityId,
            int dimHash,
            double x,
            double y,
            double z,
            double deltaMovX,
            double deltaMovZ,
            double lookX,
            double lookZ,
            long tick
    ) {
        double[] state = lastPlayerStateById.get(entityId);
        double vx;
        double vz;
        if (state == null || (int) state[6] != dimHash) {
            state = new double[]{x, y, z, deltaMovX, deltaMovZ, (double) tick, (double) dimHash};
            lastPlayerStateById.put(entityId, state);
            vx = deltaMovX;
            vz = deltaMovZ;
        } else {
            double dt = Math.max(1.0D, (double) (tick - (long) state[5]));
            double rawVx = (x - state[0]) / dt;
            double rawVz = (z - state[2]) / dt;
            double disp = Math.hypot(rawVx, rawVz);
            if (disp > 160.0D) {
                vx = deltaMovX;
                vz = deltaMovZ;
            } else {
                double smoothVx = rawVx * 0.65D + state[3] * 0.35D;
                double smoothVz = rawVz * 0.65D + state[4] * 0.35D;
                if (Math.hypot(deltaMovX, deltaMovZ) > Math.hypot(smoothVx, smoothVz)) {
                    vx = deltaMovX;
                    vz = deltaMovZ;
                } else {
                    vx = smoothVx;
                    vz = smoothVz;
                }
            }
            state[0] = x;
            state[1] = y;
            state[2] = z;
            state[3] = vx;
            state[4] = vz;
            state[5] = (double) tick;
            state[6] = (double) dimHash;
        }
        return new PlayerMotion(x, y, z, vx, 0.0D, vz, lookX, lookZ, tick);
    }

    public void updateDimension(String dimensionKey, PlayerMotion[] motions) {
        if (dimensionKey == null) return;
        PlayerMotion[] safe = (motions == null || motions.length == 0) ? EMPTY_MOTIONS : motions;
        if (safe.length > 0) {
            motionsByDimension.put(dimensionKey, safe);
            cachedActiveMotions = safe;
        } else {
            PlayerMotion[] prev = motionsByDimension.remove(dimensionKey);
            if (prev != null) {
                refreshCachedActiveMotions();
            }
        }
        if (lastPlayerStateById.size() > 64) {
            long newestTick = 0L;
            for (double[] s : lastPlayerStateById.values()) {
                if ((long) s[5] > newestTick) newestTick = (long) s[5];
            }
            long cutoff = newestTick - 200L;
            lastPlayerStateById.values().removeIf(s -> (long) s[5] < cutoff);
        }
    }

    private void refreshCachedActiveMotions() {
        for (PlayerMotion[] arr : motionsByDimension.values()) {
            if (arr != null && arr.length > 0) {
                cachedActiveMotions = arr;
                return;
            }
        }
        cachedActiveMotions = EMPTY_MOTIONS;
    }

    public PlayerMotion[] getMotions(String dimensionKey) {
        if (dimensionKey == null) return EMPTY_MOTIONS;
        return motionsByDimension.getOrDefault(dimensionKey, EMPTY_MOTIONS);
    }

    /**
     * Returns active motions in O(1) without allocating a map iterator or empty array.
     */
    public PlayerMotion[] getAnyActiveMotions() {
        return cachedActiveMotions;
    }

    public void clear() {
        motionsByDimension.clear();
        lastPlayerStateById.clear();
        cachedActiveMotions = EMPTY_MOTIONS;
    }
}
