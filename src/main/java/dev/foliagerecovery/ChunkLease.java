package dev.foliagerecovery;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** A small set of temporary tickets, using Paper's public no-generation loading API. */
final class ChunkLease implements AutoCloseable {
    /** Multiple in-flight scans may need the same chunk. Only the last release removes our ticket. */
    static final class Tickets {
        private record Key(UUID world, long chunk) {}
        private final Plugin plugin;
        private final Map<Key, Integer> references = new HashMap<>();
        Tickets(Plugin plugin) { this.plugin = plugin; }
        private Key key(Chunk chunk) {
            return new Key(chunk.getWorld().getUID(), Position.chunkKey(chunk.getX(), chunk.getZ()));
        }
        boolean acquire(Chunk chunk) {
            if (!chunk.getWorld().isChunkLoaded(chunk.getX(), chunk.getZ())) return false;
            Key key = key(chunk);
            if (!references.containsKey(key)) chunk.addPluginChunkTicket(plugin);
            references.merge(key, 1, Integer::sum);
            return true;
        }
        void release(Chunk chunk) {
            Key key = key(chunk);
            Integer left = references.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
            if (left == null) chunk.removePluginChunkTicket(plugin);
        }
    }
    enum Failure { NO_FULL_CHUNK, TICKET_EXPIRED }

    private final Tickets shared;
    private final World world;
    private final Method loadAsync;
    private final List<Request> requests = new ArrayList<>();
    private final Request center;
    private boolean closed;

    private final class Request {
        final int x, z;
        Chunk chunk;
        Failure failure;
        RuntimeException callbackError;
        boolean complete, retried;

        Request(int x, int z) {
            this.x = x;
            this.z = z;
        }

        void start(boolean urgent) throws ReflectiveOperationException {
            complete = false;
            failure = null;
            // Paper delivers this callback on the server thread, within the load operation.
            // Take a ticket here so a chunk can survive the wait for the next scan tick.
            Consumer<Chunk> onLoaded = loaded -> {
                if (closed) return;
                try {
                    if (loaded == null) failure = Failure.NO_FULL_CHUNK;
                    else if (shared.acquire(loaded)) chunk = loaded;
                    else failure = Failure.TICKET_EXPIRED;
                } catch (RuntimeException error) {
                    callbackError = error;
                } finally {
                    complete = true;
                }
            };
            loadAsync.invoke(world, x, z, false, urgent, onLoaded);
        }
    }

    ChunkLease(Tickets shared, World world, long key, boolean neighbors) throws ReflectiveOperationException {
        this.shared = shared;
        this.world = world;
        loadAsync = world.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class,
                boolean.class, Consumer.class);
        center = new Request(Position.chunkX(key), Position.chunkZ(key));
        requests.add(center);
        if (neighbors) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) requests.add(new Request(center.x + dx, center.z + dz));
            }
        }
        try {
            for (Request request : requests) request.start(false);
        } catch (ReflectiveOperationException error) {
            close();
            throw error;
        }
    }

    boolean ready() throws ReflectiveOperationException {
        boolean allReady = true;
        for (Request request : requests) {
            if (request.callbackError != null) throw request.callbackError;
            if (request.complete && !request.retried
                    && (request.failure == Failure.TICKET_EXPIRED || request == center && request.failure != null)) {
                request.retried = true;
                request.start(true);
            }
            allReady &= request.complete;
        }
        return allReady;
    }

    boolean unavailable() { return requests.stream().anyMatch(r -> r.failure != null); }
    boolean centralUnavailable() { return center.failure != null; }
    Failure centralFailure() { return center.failure; }
    boolean centralRecovered() { return center.retried && center.chunk != null; }
    Chunk central() { return center.chunk; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Request request : requests) {
            if (request.chunk != null) shared.release(request.chunk);
        }
    }
}
