package dev.foliagerecovery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;

/** Durable intents precede edits; commits distinguish completed edits from unresolved crash intents. */
final class RecoveryJournal implements AutoCloseable {
    private final FileOutputStream file;
    private final BufferedWriter writer;
    private long sequence;
    RecoveryJournal(Path path) throws IOException {
        if (Files.exists(path)) throw new IOException("Journal already exists");
        file = new FileOutputStream(path.toFile());
        writer = new BufferedWriter(new OutputStreamWriter(file, StandardCharsets.UTF_8));
    }
    int apply(List<Change> changes, Predicate<Change> edit) throws IOException {
        if (changes.size() > 64) throw new IllegalArgumentException("Journal batch too large");
        List<Long> ids = new ArrayList<>();
        for (Change change : changes) {
            long id = ++sequence; ids.add(id);
            writer.write("I\t" + id + "\t" + change.encode()); writer.newLine();
        }
        sync();
        int changed = 0;
        try {
            for (int i = 0; i < changes.size(); i++) {
                boolean applied = edit.test(changes.get(i));
                writer.write((applied ? "C\t" : "S\t") + ids.get(i)); writer.newLine();
                if (applied) changed++;
            }
        } finally { sync(); }
        return changed;
    }
    private void sync() throws IOException { writer.flush(); file.getChannel().force(true); }
    @Override public void close() throws IOException { sync(); writer.close(); }
}
