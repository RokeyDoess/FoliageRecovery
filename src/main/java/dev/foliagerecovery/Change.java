package dev.foliagerecovery;

import java.io.IOException;
import java.util.UUID;

public record Change(UUID world, Position position, String before, String after, String reason) {
    public String encode() {
        return world + "\t" + position.x() + "\t" + position.y() + "\t" + position.z()
                + "\t" + before + "\t" + after + "\t" + reason;
    }
    public static Change decode(String line) throws IOException {
        try {
            String[] f = line.split("\t", -1);
            if (f.length != 7 || f[4].isBlank() || f[5].isBlank()
                    || !(f[6].equals("recorded") || f[6].equals("inferred"))) {
                throw new IllegalArgumentException("Invalid change fields");
            }
            return new Change(UUID.fromString(f[0]), new Position(Integer.parseInt(f[1]),
                    Integer.parseInt(f[2]), Integer.parseInt(f[3])), f[4], f[5], f[6]);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid plan row", e); }
    }
    public Change reversed() { return new Change(world, position, after, before, reason); }
}
