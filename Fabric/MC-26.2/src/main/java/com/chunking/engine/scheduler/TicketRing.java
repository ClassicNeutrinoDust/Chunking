package com.chunking.engine.scheduler;

import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMaps;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.world.level.ChunkPos;

import java.util.Arrays;

/**
 * Analytical Chebyshev distance tracker replacing {@code PlayerTicketTracker}'s 2-pass
 * {@code DynamicGraphMinFixedPoint} BFS flood-fill. Single-player boundary crossings update only
 * the entering/leaving perimeter strips and changed overlap cells in O(R^2) arithmetic with zero
 * graph allocations or neighbor probes.
 */
public final class TicketRing {
    public interface ActiveCheck {
        boolean isActive(long pos);
    }

    public interface LevelNotify {
        void notifyActive(long pos, int newLevel);
    }

    public interface TicketNotify {
        void onTicketChange(long pos, int newLevel, boolean hadTicket, boolean hasTicket);
    }

    private long[] lastSources = new long[4];
    private int lastSourceCount;
    private int lastRadius = -1;
    private int[] srcX = new int[4];
    private int[] srcZ = new int[4];
    private long[] removedBuf = new long[256];

    public int lastSourceCount() {
        return this.lastSourceCount;
    }

    public void sync(
            long[] sources,
            int sourceCount,
            int viewDistance,
            int maxDistance,
            Long2ByteMap chunks,
            Long2IntMap queueLevels,
            ActiveCheck activeCheck,
            LevelNotify activeNotify,
            TicketNotify ticketNotify
    ) {
        int defaultLevel = maxDistance + 2;
        int radius = Math.min(maxDistance, viewDistance);

        if (this.lastSourceCount == 1 && sourceCount == 1 && this.lastRadius == radius && radius >= 0) {
            long prev = this.lastSources[0];
            long next = sources[0];
            if (prev == next) {
                return;
            }
            int ox = ChunkPos.getX(prev);
            int oz = ChunkPos.getZ(prev);
            int nx = ChunkPos.getX(next);
            int nz = ChunkPos.getZ(next);
            if (Math.abs(nx - ox) <= radius && Math.abs(nz - oz) <= radius) {
                syncSinglePlayerOverlap(
                        ox, oz, nx, nz, radius, defaultLevel,
                        chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
                this.lastSources[0] = next;
                return;
            }
        }

        if (sourceCount > this.srcX.length) {
            this.srcX = new int[sourceCount];
            this.srcZ = new int[sourceCount];
        }
        for (int i = 0; i < sourceCount; i++) {
            this.srcX[i] = ChunkPos.getX(sources[i]);
            this.srcZ[i] = ChunkPos.getZ(sources[i]);
        }

        // Remove chunks outside all player squares; defer callbacks until after fastIterator finishes.
        int removedCount = 0;
        ObjectIterator<Long2ByteMap.Entry> it = Long2ByteMaps.fastIterator(chunks);
        while (it.hasNext()) {
            Long2ByteMap.Entry e = it.next();
            long pos = e.getLongKey();
            boolean keep = false;
            if (radius >= 0 && sourceCount > 0) {
                int cx = ChunkPos.getX(pos);
                int cz = ChunkPos.getZ(pos);
                for (int i = 0; i < sourceCount; i++) {
                    int dx = Math.abs(cx - this.srcX[i]);
                    int dz = Math.abs(cz - this.srcZ[i]);
                    if ((dx >= dz ? dx : dz) <= radius) {
                        keep = true;
                        break;
                    }
                }
            }
            if (!keep) {
                it.remove();
                if (removedCount == this.removedBuf.length) {
                    this.removedBuf = Arrays.copyOf(this.removedBuf, this.removedBuf.length << 1);
                }
                this.removedBuf[removedCount++] = pos;
            }
        }
        for (int i = 0; i < removedCount; i++) {
            long pos = this.removedBuf[i];
            if (activeCheck.isActive(pos)) {
                activeNotify.notifyActive(pos, defaultLevel);
            } else {
                queueLevels.remove(pos);
            }
            ticketNotify.onTicketChange(pos, defaultLevel, true, false);
        }

        if (sourceCount > 0 && radius >= 0) {
            for (int i = 0; i < sourceCount; i++) {
                int sx = this.srcX[i];
                int sz = this.srcZ[i];
                for (int cx = sx - radius; cx <= sx + radius; cx++) {
                    for (int cz = sz - radius; cz <= sz + radius; cz++) {
                        if (sourceCount > 1) {
                            boolean coveredEarlier = false;
                            for (int j = 0; j < i; j++) {
                                int dx = Math.abs(cx - this.srcX[j]);
                                int dz = Math.abs(cz - this.srcZ[j]);
                                if ((dx >= dz ? dx : dz) <= radius) {
                                    coveredEarlier = true;
                                    break;
                                }
                            }
                            if (coveredEarlier) {
                                continue;
                            }
                        }
                        int newDist = Math.max(Math.abs(cx - sx), Math.abs(cz - sz));
                        for (int j = i + 1; j < sourceCount; j++) {
                            int d = Math.max(Math.abs(cx - this.srcX[j]), Math.abs(cz - this.srcZ[j]));
                            if (d < newDist) {
                                newDist = d;
                            }
                        }
                        long pos = ChunkPos.pack(cx, cz);
                        int oldByte = chunks.get(pos);
                        boolean hadTicket = oldByte != defaultLevel;
                        if (!hadTicket) {
                            chunks.put(pos, (byte) newDist);
                            if (activeCheck.isActive(pos)) {
                                activeNotify.notifyActive(pos, newDist);
                            } else {
                                queueLevels.put(pos, newDist);
                            }
                            ticketNotify.onTicketChange(pos, newDist, false, true);
                        } else if (oldByte != newDist) {
                            chunks.put(pos, (byte) newDist);
                            if (activeCheck.isActive(pos)) {
                                activeNotify.notifyActive(pos, newDist);
                            } else {
                                queueLevels.put(pos, newDist);
                            }
                        }
                    }
                }
            }
        }

        if (sourceCount > this.lastSources.length) {
            this.lastSources = new long[sourceCount];
        }
        System.arraycopy(sources, 0, this.lastSources, 0, sourceCount);
        this.lastSourceCount = sourceCount;
        this.lastRadius = radius;
    }

    private static void syncSinglePlayerOverlap(
            int ox, int oz, int nx, int nz, int radius, int defaultLevel,
            Long2ByteMap chunks, Long2IntMap queueLevels,
            ActiveCheck activeCheck, LevelNotify activeNotify, TicketNotify ticketNotify
    ) {
        int oMinX = Math.max(ox - radius, nx - radius);
        int oMaxX = Math.min(ox + radius, nx + radius);
        int oMinZ = Math.max(oz - radius, nz - radius);
        int oMaxZ = Math.min(oz + radius, nz + radius);

        // Leaving X strip (full old Z range)
        if (nx > ox) {
            removeStrip(ox - radius, nx - radius - 1, oz - radius, oz + radius,
                    defaultLevel, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        } else if (nx < ox) {
            removeStrip(nx + radius + 1, ox + radius, oz - radius, oz + radius,
                    defaultLevel, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        }
        // Leaving Z strip (overlap X range only, so corners are not visited twice)
        if (nz > oz) {
            removeStrip(oMinX, oMaxX, oz - radius, nz - radius - 1,
                    defaultLevel, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        } else if (nz < oz) {
            removeStrip(oMinX, oMaxX, nz + radius + 1, oz + radius,
                    defaultLevel, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        }

        // Entering X strip (full new Z range)
        if (nx > ox) {
            addStrip(ox + radius + 1, nx + radius, nz - radius, nz + radius,
                    nx, nz, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        } else if (nx < ox) {
            addStrip(nx - radius, ox - radius - 1, nz - radius, nz + radius,
                    nx, nz, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        }
        // Entering Z strip (overlap X range only)
        if (nz > oz) {
            addStrip(oMinX, oMaxX, oz + radius + 1, nz + radius,
                    nx, nz, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        } else if (nz < oz) {
            addStrip(oMinX, oMaxX, nz - radius, oz - radius - 1,
                    nx, nz, chunks, queueLevels, activeCheck, activeNotify, ticketNotify);
        }

        // Overlap interior: only update cells whose Chebyshev distance actually changed
        for (int cx = oMinX; cx <= oMaxX; cx++) {
            int odx = Math.abs(cx - ox);
            int ndx = Math.abs(cx - nx);
            for (int cz = oMinZ; cz <= oMaxZ; cz++) {
                int odz = Math.abs(cz - oz);
                int ndz = Math.abs(cz - nz);
                int oldD = odx >= odz ? odx : odz;
                int newD = ndx >= ndz ? ndx : ndz;
                if (oldD == newD) {
                    continue;
                }
                long pos = ChunkPos.pack(cx, cz);
                chunks.put(pos, (byte) newD);
                if (activeCheck.isActive(pos)) {
                    activeNotify.notifyActive(pos, newD);
                } else {
                    queueLevels.put(pos, newD);
                }
            }
        }
    }

    private static void removeStrip(
            int minX, int maxX, int minZ, int maxZ, int defaultLevel,
            Long2ByteMap chunks, Long2IntMap queueLevels,
            ActiveCheck activeCheck, LevelNotify activeNotify, TicketNotify ticketNotify
    ) {
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                long pos = ChunkPos.pack(cx, cz);
                chunks.remove(pos);
                if (activeCheck.isActive(pos)) {
                    activeNotify.notifyActive(pos, defaultLevel);
                } else {
                    queueLevels.remove(pos);
                }
                ticketNotify.onTicketChange(pos, defaultLevel, true, false);
            }
        }
    }

    private static void addStrip(
            int minX, int maxX, int minZ, int maxZ, int nx, int nz,
            Long2ByteMap chunks, Long2IntMap queueLevels,
            ActiveCheck activeCheck, LevelNotify activeNotify, TicketNotify ticketNotify
    ) {
        for (int cx = minX; cx <= maxX; cx++) {
            int dx = Math.abs(cx - nx);
            for (int cz = minZ; cz <= maxZ; cz++) {
                int dz = Math.abs(cz - nz);
                int d = dx >= dz ? dx : dz;
                long pos = ChunkPos.pack(cx, cz);
                chunks.put(pos, (byte) d);
                if (activeCheck.isActive(pos)) {
                    activeNotify.notifyActive(pos, d);
                } else {
                    queueLevels.put(pos, d);
                }
                ticketNotify.onTicketChange(pos, d, false, true);
            }
        }
    }
}
