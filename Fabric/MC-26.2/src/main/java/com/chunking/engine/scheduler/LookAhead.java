package com.chunking.engine.scheduler;

/**
 * Computes smooth radial + camera-biased distance scores for chunk generation and packet dispatch.
 * Guarantees the inner 5x5 circle around the player loads first, smoothly leads ahead of the camera
 * during both fast flight and stationary horizon loading, and never drops rear or side chunks.
 */
public final class LookAhead {
    public static final double HIGH_SPEED_THRESHOLD = 0.22D; // blocks/tick (~4.4 blocks/sec)
    private static final double HIGH_SPEED_THRESHOLD_SQ = HIGH_SPEED_THRESHOLD * HIGH_SPEED_THRESHOLD;
    private static final double EPSILON_SQ = 1e-10D;

    private LookAhead() {}

    public static double computeEffectiveDistanceWithHeading(
            int playerChunkX,
            int playerChunkZ,
            double hx,
            double hz,
            double bias,
            int chunkX,
            int chunkZ
    ) {
        double dx = chunkX - playerChunkX;
        double dz = chunkZ - playerChunkZ;
        double distSq = dx * dx + dz * dz;
        if (distSq <= 6.25D) {
            return Math.sqrt(distSq) * 0.5D;
        }
        double dist = Math.sqrt(distSq);
        return dist - bias * (dx * hx + dz * hz);
    }

    public static int computeSendSortScoreWithHeading(
            int playerChunkX,
            int playerChunkZ,
            double hx,
            double hz,
            double bias,
            int chunkX,
            int chunkZ
    ) {
        double eff = computeEffectiveDistanceWithHeading(playerChunkX, playerChunkZ, hx, hz, bias, chunkX, chunkZ);
        return (int) Math.min((long) Integer.MAX_VALUE, Math.round(eff * eff * 16.0D));
    }

    public static double computeEffectiveDistance(
            int playerChunkX,
            int playerChunkZ,
            double vx,
            double vz,
            double lookX,
            double lookZ,
            int chunkX,
            int chunkZ
    ) {
        double dx = chunkX - playerChunkX;
        double dz = chunkZ - playerChunkZ;
        double distSq = dx * dx + dz * dz;
        if (distSq <= 6.25D) {
            return Math.sqrt(distSq) * 0.5D;
        }

        double lookLenSq = lookX * lookX + lookZ * lookZ;
        double lx;
        double lz;
        if (lookLenSq > EPSILON_SQ) {
            double invLook = 1.0D / Math.sqrt(lookLenSq);
            lx = lookX * invLook;
            lz = lookZ * invLook;
        } else {
            lx = 0.0D;
            lz = 1.0D;
        }

        double speedSq = vx * vx + vz * vz;
        double hx;
        double hz;
        double bias;
        if (speedSq >= HIGH_SPEED_THRESHOLD_SQ) {
            double invSpeed = 1.0D / Math.sqrt(speedSq);
            double vdx = vx * invSpeed;
            double vdz = vz * invSpeed;
            double bx = 0.70D * lx + 0.30D * vdx;
            double bz = 0.70D * lz + 0.30D * vdz;
            double bLenSq = bx * bx + bz * bz;
            if (bLenSq > EPSILON_SQ) {
                double invB = 1.0D / Math.sqrt(bLenSq);
                hx = bx * invB;
                hz = bz * invB;
            } else {
                hx = lx;
                hz = lz;
            }
            bias = 0.32D;
        } else {
            hx = lx;
            hz = lz;
            bias = 0.22D;
        }

        double dist = Math.sqrt(distSq);
        return dist - bias * (dx * hx + dz * hz);
    }

    public static int computeSendSortScore(
            int playerChunkX,
            int playerChunkZ,
            double vx,
            double vz,
            double lookX,
            double lookZ,
            int chunkX,
            int chunkZ
    ) {
        double eff = computeEffectiveDistance(playerChunkX, playerChunkZ, vx, vz, lookX, lookZ, chunkX, chunkZ);
        return (int) Math.min((long) Integer.MAX_VALUE, Math.round(eff * eff * 16.0D));
    }
}
