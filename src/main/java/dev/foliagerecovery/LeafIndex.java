package dev.foliagerecovery;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Streaming reader for the Aeternum original-leaf format, with a disk chunk index. */
public final class LeafIndex {
    public record Original(Position position, String material) {}
    public record Result(long records, int chunks, String sha256) {}
    private record Key(UUID world, long chunk) {}
    private LeafIndex() {}

    public static Result build(Path source, Path destination) throws IOException {
        if (Files.exists(destination)) throw new IOException("Index destination already exists");
        Files.createDirectories(destination);
        Path buckets = Files.createDirectory(destination.resolve("buckets"));
        List<DataOutputStream> outputs = new ArrayList<>();
        long records;
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        try {
            for (int i = 0; i < 64; i++) outputs.add(new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(buckets.resolve(i + ".bin")))));
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                    new DigestInputStream(Files.newInputStream(source), digest)))) {
                if (in.readInt() != 1095517745) throw new IOException("Invalid original-leaf file header");
                records = in.readInt();
                if (records < 0 || records > 10_000_000) throw new IOException("Invalid original-leaf count");
                for (long i = 0; i < records; i++) {
                    String[] p = in.readUTF().split(":", -1);
                    String material = in.readUTF();
                    if (p.length != 4) throw new IOException("Invalid leaf location at record " + i);
                    UUID world; Position pos;
                    try {
                        world = UUID.fromString(p[0]);
                        pos = new Position(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
                    } catch (IllegalArgumentException e) { throw new IOException("Invalid leaf coordinates", e); }
                    if (Math.abs((long) pos.x()) > 30_000_000 || Math.abs((long) pos.z()) > 30_000_000
                            || pos.y() < -2048 || pos.y() > 4096) throw new IOException("Leaf outside supported bounds");
                    byte type = switch (material) {
                        case "BIRCH_LEAVES" -> 1;
                        case "SPRUCE_LEAVES" -> 2;
                        default -> throw new IOException("Unsupported original material: " + material);
                    };
                    DataOutputStream out = outputs.get(Math.floorMod(Objects.hash(world, pos.chunk()), 64));
                    out.writeLong(world.getMostSignificantBits()); out.writeLong(world.getLeastSignificantBits());
                    out.writeInt(pos.x()); out.writeInt(pos.y()); out.writeInt(pos.z()); out.writeByte(type);
                }
                if (in.read() != -1) throw new IOException("Trailing data in original-leaf file");
            }
        } finally {
            IOException failure = null;
            for (DataOutputStream out : outputs) try { out.close(); } catch (IOException e) { failure = e; }
            if (failure != null) throw failure;
        }
        int chunks = 0;
        for (int i = 0; i < 64; i++) {
            Path bucket = buckets.resolve(i + ".bin");
            Map<Key, Map<Position, Byte>> grouped = new HashMap<>();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(bucket)))) {
                long count = Files.size(bucket) / 29;
                for (long j = 0; j < count; j++) {
                    UUID world = new UUID(in.readLong(), in.readLong());
                    Position pos = new Position(in.readInt(), in.readInt(), in.readInt());
                    byte type = in.readByte();
                    Map<Position, Byte> leaves = grouped.computeIfAbsent(new Key(world, pos.chunk()), k -> new HashMap<>());
                    Byte previous = leaves.putIfAbsent(pos, type);
                    if (previous != null) throw new IOException("Duplicate original-leaf location: " + pos);
                }
            }
            for (var group : grouped.entrySet()) {
                Path directory = destination.resolve(group.getKey().world().toString());
                Files.createDirectories(directory);
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(
                        file(destination, group.getKey().world(), group.getKey().chunk()))))) {
                    out.writeInt(group.getValue().size());
                    for (var leaf : group.getValue().entrySet()) {
                        out.writeInt(leaf.getKey().x()); out.writeInt(leaf.getKey().y());
                        out.writeInt(leaf.getKey().z()); out.writeByte(leaf.getValue());
                    }
                }
                chunks++;
            }
            Files.delete(bucket);
        }
        Files.delete(buckets);
        String hash = HexFormat.of().formatHex(digest.digest());
        Files.writeString(destination.resolve("READY"), records + " records\n" + chunks + " chunks\nsha256=" + hash + "\n");
        return new Result(records, chunks, hash);
    }

    static Path file(Path root, UUID world, long chunk) {
        return root.resolve(world.toString()).resolve(Position.chunkX(chunk) + "_" + Position.chunkZ(chunk) + ".bin");
    }
    public static List<Long> chunks(Path root, UUID world) throws IOException {
        if (!Files.isRegularFile(root.resolve("READY"))) throw new IOException("Index is incomplete");
        Path directory = root.resolve(world.toString());
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            List<Long> result = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                String[] xy = file.getFileName().toString().replace(".bin", "").split("_");
                result.add(Position.chunkKey(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])));
            }
            return result;
        }
    }
    public static List<Original> read(Path root, UUID world, long chunk) throws IOException {
        Path path = file(root, world, chunk);
        if (!Files.exists(path)) return List.of();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int count = in.readInt();
            if (count < 0 || count > 1_000_000) throw new IOException("Invalid indexed chunk count");
            List<Original> leaves = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                Position pos = new Position(in.readInt(), in.readInt(), in.readInt());
                byte type = in.readByte();
                if (type != 1 && type != 2) throw new IOException("Invalid original material in index");
                leaves.add(new Original(pos, type == 1 ? "BIRCH_LEAVES" : "SPRUCE_LEAVES"));
            }
            if (in.read() != -1) throw new IOException("Trailing index data");
            return leaves;
        }
    }
}
