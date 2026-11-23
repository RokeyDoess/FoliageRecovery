package dev.foliagerecovery;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class PlanFiles {
    private PlanFiles() {}
    public static String hash(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream stream = new DigestInputStream(input, digest)) { stream.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public static void ready(Path directory) throws IOException {
        Path plan = directory.resolve("changes.tsv");
        try (FileChannel channel = FileChannel.open(plan, StandardOpenOption.WRITE)) { channel.force(true); }
        Files.writeString(directory.resolve("READY"), hash(plan), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    /** Only committed changes become automatic rollback proposals. Unresolved intents remain reviewable. */
    public static int rollback(Path journal, Path destination) throws IOException {
        Map<Long, Change> pending = new LinkedHashMap<>();
        boolean terminated = true;
        try (FileChannel channel = FileChannel.open(journal, StandardOpenOption.READ)) {
            if (channel.size() != 0) {
                ByteBuffer tail = ByteBuffer.allocate(1);
                channel.read(tail, channel.size() - 1);
                terminated = tail.array()[0] == '\n';
            }
        }
        try (BufferedReader reader = Files.newBufferedReader(journal);
             BufferedWriter writer = Files.newBufferedWriter(destination.resolve("changes.tsv"))) {
            String following;
            for (String line = reader.readLine(); line != null; line = following) {
                following = reader.readLine();
                boolean lastLine = following == null;
                if (lastLine && !terminated) {
                    Files.writeString(destination.resolve("journal-tail-fragment.txt"), line);
                    break;
                }
                String[] f = line.split("\t", 3);
                try {
                    if (f[0].equals("I") && f.length == 3) {
                        long id = Long.parseLong(f[1]);
                        if (pending.putIfAbsent(id, Change.decode(f[2])) != null) throw new IOException("Duplicate journal intent");
                    } else if ((f[0].equals("C") || f[0].equals("S")) && f.length == 2) {
                        Change change = pending.remove(Long.parseLong(f[1]));
                        if (change == null) throw new IOException("Journal result without intent");
                        if (f[0].equals("C")) { writer.write(change.reversed().encode()); writer.newLine(); }
                    } else throw new IOException("Malformed journal row");
                } catch (NumberFormatException e) { throw new IOException("Invalid journal sequence", e); }
                if (pending.size() > 64) throw new IOException("Journal exceeds bounded intent batch size");
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(destination.resolve("unresolved-intents.tsv"))) {
            for (Change change : pending.values()) { writer.write(change.encode()); writer.newLine(); }
        }
        ready(destination);
        return pending.size();
    }
}
