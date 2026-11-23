package dev.foliagerecovery;

import org.bukkit.ChunkSnapshot;
import java.util.*;

/** Scans only an immutable chunk snapshot, never a live world, on the file worker. */
final class SnapshotCandidates {
    private SnapshotCandidates() {}
    static List<Position> find(ChunkSnapshot snapshot, int minY, int maxY, long chunk) {
        List<Position> result = new ArrayList<>();
        int firstSection = minY >> 4;
        boolean[] empty = new boolean[((maxY - 1) >> 4) - firstSection + 1];
        // CraftChunkSnapshot in the target Paper build takes an index from the bottom.
        for (int i = 0; i < empty.length; i++) empty[i] = snapshot.isSectionEmpty(i);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = Math.min(maxY - 1, snapshot.getHighestBlockYAt(x, z));
            for (int y = minY; y <= top; y++) {
                if ((y & 15) == 0 && Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                if (empty[(y >> 4) - firstSection]) {
                    y |= 15;
                    continue;
                }
                if (OwnerProbe.painted(snapshot.getBlockType(x, y, z).name())) {
                    result.add(new Position((Position.chunkX(chunk) << 4) + x, y,
                            (Position.chunkZ(chunk) << 4) + z));
                }
            }
        }
        return result;
    }
}
