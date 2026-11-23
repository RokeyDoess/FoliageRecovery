package dev.foliagerecovery;

public record Position(int x, int y, int z) {
    public long chunk() { return chunkKey(x >> 4, z >> 4); }
    public Position offset(int dx, int dy, int dz) { return new Position(x + dx, y + dy, z + dz); }
    public boolean touchesChunkEdge(int distance) {
        int localX = x & 15, localZ = z & 15;
        return localX < distance || localX >= 16 - distance
                || localZ < distance || localZ >= 16 - distance;
    }
    public static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    public static int chunkX(long key) { return (int) (key >> 32); }
    public static int chunkZ(long key) { return (int) key; }
}
