package dev.foliagerecovery;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public final class RegionChunks {
    private static final Pattern NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private RegionChunks() {}
    /** Reads Anvil location tables only. Loading is always requested with generation disabled. */
    public static List<Long> existing(Path region) throws IOException {
        if (!Files.isDirectory(region)) throw new IOException("World has no Anvil region directory");
        List<Long> chunks = new ArrayList<>();
        try (var paths = Files.list(region)) {
            for (Path path : paths.sorted().toList()) {
                Matcher m = NAME.matcher(path.getFileName().toString());
                if (!m.matches()) continue;
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                    for (int slot = 0; slot < 1024; slot++) {
                        int location = in.readInt();
                        if ((location >>> 8) >= 2 && (location & 255) != 0) {
                            chunks.add(Position.chunkKey(Math.addExact(Math.multiplyExact(rx, 32), slot % 32),
                                    Math.addExact(Math.multiplyExact(rz, 32), slot / 32)));
                        }
                    }
                }
            }
        }
        return chunks;
    }
}
