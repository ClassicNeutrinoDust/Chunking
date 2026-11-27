package com.chunking.engine.world;

import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.Arrays;

/**
 * Primitive 3D AABB octree for O(log N) jigsaw piece collision checks without VoxelShape BitSet merges.
 */
public final class BoxTree {
    private static final int SPLIT_THRESHOLD = 16;
    private static final int MAX_DEPTH = 6;
    private static final double MIN_SPLIT_SPAN = 4.0D;

    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;
    private final Node root;
    private int count;

    public BoxTree(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.root = new Node(minX, minY, minZ, maxX, maxY, maxZ, 0);
    }

    public BoxTree(AABB bounds) {
        this(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ);
    }

    public BoxTree(BoundingBox box) {
        this(box.minX(), box.minY(), box.minZ(), box.maxX() + 1.0D, box.maxY() + 1.0D, box.maxZ() + 1.0D);
    }

    public int size() {
        return this.count;
    }

    public boolean withinBounds(double qMinX, double qMinY, double qMinZ, double qMaxX, double qMaxY, double qMaxZ) {
        return qMinX >= this.minX && qMaxX <= this.maxX
                && qMinY >= this.minY && qMaxY <= this.maxY
                && qMinZ >= this.minZ && qMaxZ <= this.maxZ;
    }

    public boolean withinBounds(AABB box) {
        return withinBounds(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public boolean withinBounds(BoundingBox box) {
        return withinBounds(
                box.minX(), box.minY(), box.minZ(),
                box.maxX() + 1.0D, box.maxY() + 1.0D, box.maxZ() + 1.0D
        );
    }

    public boolean withinBoundsXZ(double qMinX, double qMaxX, double qMinZ, double qMaxZ) {
        return qMinX >= this.minX && qMaxX <= this.maxX
                && qMinZ >= this.minZ && qMaxZ <= this.maxZ;
    }

    public void add(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
        if (bMaxX <= bMinX || bMaxY <= bMinY || bMaxZ <= bMinZ) {
            return;
        }
        this.count++;
        this.root.insert(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
    }

    public void add(AABB box) {
        add(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public void add(BoundingBox box) {
        add(box.minX(), box.minY(), box.minZ(), box.maxX() + 1.0D, box.maxY() + 1.0D, box.maxZ() + 1.0D);
    }

    public boolean intersects(double qMinX, double qMinY, double qMinZ, double qMaxX, double qMaxY, double qMaxZ) {
        if (this.count == 0
                || qMaxX <= qMinX || qMaxY <= qMinY || qMaxZ <= qMinZ
                || qMaxX <= this.minX || qMinX >= this.maxX
                || qMaxY <= this.minY || qMinY >= this.maxY
                || qMaxZ <= this.minZ || qMinZ >= this.maxZ) {
            return false;
        }
        return this.root.intersects(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ);
    }

    public boolean intersects(AABB box) {
        return intersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }

    public boolean intersects(BoundingBox box) {
        return intersects(
                box.minX() + 0.25D, box.minY() + 0.25D, box.minZ() + 0.25D,
                box.maxX() + 0.75D, box.maxY() + 0.75D, box.maxZ() + 0.75D
        );
    }

    public boolean canPlace(AABB deflatedBox) {
        return withinBounds(deflatedBox) && !intersects(deflatedBox);
    }

    public boolean canPlace(BoundingBox box) {
        double qMinX = box.minX() + 0.25D;
        double qMinY = box.minY() + 0.25D;
        double qMinZ = box.minZ() + 0.25D;
        double qMaxX = box.maxX() + 0.75D;
        double qMaxY = box.maxY() + 0.75D;
        double qMaxZ = box.maxZ() + 0.75D;
        return withinBounds(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)
                && !intersects(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ);
    }

    private static final class Node {
        final double minX, minY, minZ, maxX, maxY, maxZ;
        final double midX, midY, midZ;
        final int depth;
        double[] boxes;
        int boxLen;
        Node[] children;

        Node(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int depth) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
            this.midX = minX + (maxX - minX) * 0.5D;
            this.midY = minY + (maxY - minY) * 0.5D;
            this.midZ = minZ + (maxZ - minZ) * 0.5D;
            this.depth = depth;
        }

        void insert(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
            if (this.children == null) {
                if (this.boxLen >= SPLIT_THRESHOLD * 6
                        && this.depth < MAX_DEPTH
                        && ((this.maxX - this.minX) > MIN_SPLIT_SPAN
                        || (this.maxY - this.minY) > MIN_SPLIT_SPAN
                        || (this.maxZ - this.minZ) > MIN_SPLIT_SPAN)) {
                    split();
                } else {
                    appendLocal(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
                    return;
                }
            }
            insertIntoSplit(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
        }

        private void appendLocal(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
            double[] arr = this.boxes;
            int len = this.boxLen;
            if (arr == null) {
                arr = new double[8 * 6];
                this.boxes = arr;
            } else if (len + 6 > arr.length) {
                arr = Arrays.copyOf(arr, arr.length << 1);
                this.boxes = arr;
            }
            arr[len] = bMinX;
            arr[len + 1] = bMinY;
            arr[len + 2] = bMinZ;
            arr[len + 3] = bMaxX;
            arr[len + 4] = bMaxY;
            arr[len + 5] = bMaxZ;
            this.boxLen = len + 6;
        }

        private void split() {
            this.children = new Node[8];
            double[] old = this.boxes;
            int oldLen = this.boxLen;
            this.boxes = null;
            this.boxLen = 0;
            for (int i = 0; i < oldLen; i += 6) {
                insertIntoSplit(old[i], old[i + 1], old[i + 2], old[i + 3], old[i + 4], old[i + 5]);
            }
        }

        private void insertIntoSplit(double bMinX, double bMinY, double bMinZ, double bMaxX, double bMaxY, double bMaxZ) {
            boolean loX = bMinX < this.midX;
            boolean hiX = bMaxX > this.midX;
            boolean loY = bMinY < this.midY;
            boolean hiY = bMaxY > this.midY;
            boolean loZ = bMinZ < this.midZ;
            boolean hiZ = bMaxZ > this.midZ;

            // Keep boxes straddling all 3 midplanes at this node to avoid 8x fan-out duplication.
            if (loX && hiX && loY && hiY && loZ && hiZ) {
                appendLocal(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
                return;
            }

            int xStart = loX ? 0 : 1;
            int xEnd = hiX ? 1 : 0;
            int yStart = loY ? 0 : 1;
            int yEnd = hiY ? 1 : 0;
            int zStart = loZ ? 0 : 1;
            int zEnd = hiZ ? 1 : 0;
            Node[] ch = this.children;
            int nextDepth = this.depth + 1;

            for (int xi = xStart; xi <= xEnd; xi++) {
                double cMinX = xi == 0 ? this.minX : this.midX;
                double cMaxX = xi == 0 ? this.midX : this.maxX;
                for (int yi = yStart; yi <= yEnd; yi++) {
                    double cMinY = yi == 0 ? this.minY : this.midY;
                    double cMaxY = yi == 0 ? this.midY : this.maxY;
                    for (int zi = zStart; zi <= zEnd; zi++) {
                        int oct = (xi << 2) | (yi << 1) | zi;
                        Node child = ch[oct];
                        if (child == null) {
                            double cMinZ = zi == 0 ? this.minZ : this.midZ;
                            double cMaxZ = zi == 0 ? this.midZ : this.maxZ;
                            child = new Node(cMinX, cMinY, cMinZ, cMaxX, cMaxY, cMaxZ, nextDepth);
                            ch[oct] = child;
                        }
                        child.insert(bMinX, bMinY, bMinZ, bMaxX, bMaxY, bMaxZ);
                    }
                }
            }
        }

        boolean intersects(double qMinX, double qMinY, double qMinZ, double qMaxX, double qMaxY, double qMaxZ) {
            double[] arr = this.boxes;
            int len = this.boxLen;
            for (int i = 0; i < len; i += 6) {
                if (qMinX < arr[i + 3] && qMaxX > arr[i]
                        && qMinY < arr[i + 4] && qMaxY > arr[i + 1]
                        && qMinZ < arr[i + 5] && qMaxZ > arr[i + 2]) {
                    return true;
                }
            }
            Node[] ch = this.children;
            if (ch == null) {
                return false;
            }
            int xStart = qMinX < this.midX ? 0 : 1;
            int xEnd = qMaxX > this.midX ? 1 : 0;
            int yStart = qMinY < this.midY ? 0 : 1;
            int yEnd = qMaxY > this.midY ? 1 : 0;
            int zStart = qMinZ < this.midZ ? 0 : 1;
            int zEnd = qMaxZ > this.midZ ? 1 : 0;

            for (int xi = xStart; xi <= xEnd; xi++) {
                for (int yi = yStart; yi <= yEnd; yi++) {
                    for (int zi = zStart; zi <= zEnd; zi++) {
                        Node child = ch[(xi << 2) | (yi << 1) | zi];
                        if (child != null && child.intersects(qMinX, qMinY, qMinZ, qMaxX, qMaxY, qMaxZ)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }
}
