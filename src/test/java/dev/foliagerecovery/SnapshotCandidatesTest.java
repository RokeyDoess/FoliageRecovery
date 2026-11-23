package dev.foliagerecovery;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SnapshotCandidatesTest {
    @Test void immutableSnapshotScanFindsOnlyPaintedLeavesAndPreservesNegativeCoordinates() {
        ChunkSnapshot snapshot = (ChunkSnapshot) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{ChunkSnapshot.class}, (p,m,a) -> {
                    if (m.getName().equals("getHighestBlockYAt")) return 80;
                    if (m.getName().equals("isSectionEmpty")) return (Integer) a[0] != 9;
                    if (m.getName().equals("getBlockType")) {
                        assertTrue((Integer) a[1] >= 80, "Skip air-only sections");
                        return (Integer)a[0] == 1 && (Integer)a[1] == 80 && (Integer)a[2] == 2
                                ? Material.ACACIA_LEAVES : Material.AIR;
                    }
                    throw new AssertionError("Unexpected snapshot call: " + m.getName());
                });
        assertEquals(List.of(new Position(-31,80,18)),
                SnapshotCandidates.find(snapshot, -64, 320, Position.chunkKey(-2,1)));
    }
}
