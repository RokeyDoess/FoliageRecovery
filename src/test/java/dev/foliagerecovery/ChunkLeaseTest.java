package dev.foliagerecovery;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ChunkLeaseTest {
    public interface AsyncWorld extends World {
        void getChunkAtAsync(int x, int z, boolean generate, boolean urgent, Consumer<? super Chunk> callback);
    }

    private static final class Fixture {
        final UUID id = UUID.randomUUID();
        final Set<Long> available = new HashSet<>();
        final List<Boolean> generation = new ArrayList<>();
        final Set<Long> activeTickets = new HashSet<>();
        final Set<Long> delayed = new HashSet<>();
        final Set<Long> availableOnRetry = new HashSet<>();
        final Set<Long> expired = new HashSet<>();
        final List<Runnable> callbacks = new ArrayList<>();
        final AsyncWorld world = (AsyncWorld) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{AsyncWorld.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> id;
                    case "isChunkLoaded" -> available.contains(Position.chunkKey((int) args[0], (int) args[1]));
                    case "getChunkAtAsync" -> {
                        int x = (int) args[0], z = (int) args[1];
                        long key = Position.chunkKey(x, z);
                        generation.add((boolean) args[2]);
                        if ((boolean) args[3] && availableOnRetry.contains(key)) available.add(key);
                        @SuppressWarnings("unchecked")
                        Consumer<Chunk> callback = (Consumer<Chunk>) args[4];
                        Runnable complete = () -> callback.accept(
                                available.contains(key) || expired.contains(key) ? chunk(x, z) : null);
                        if (delayed.contains(key)) callbacks.add(complete);
                        else complete.run();
                        yield null;
                    }
                    default -> throw new AssertionError(method.getName());
                });

        Chunk chunk(int x, int z) {
            long key = Position.chunkKey(x, z);
            return (Chunk) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Chunk.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getWorld" -> world;
                        case "getX" -> x;
                        case "getZ" -> z;
                        case "addPluginChunkTicket" -> {
                            assertTrue(activeTickets.add(key), "Duplicate server ticket");
                            yield true;
                        }
                        case "removePluginChunkTicket" -> {
                            assertTrue(activeTickets.remove(key), "Released without owning a ticket");
                            yield true;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
        }
    }

    @Test void overlappingRequestsNeverGenerateAndHoldTicketsUntilTheLastConsumerReleases() throws Exception {
        Fixture fixture = new Fixture();
        for (int x = -3; x <= -1; x++) for (int z = 0; z <= 2; z++) {
            fixture.available.add(Position.chunkKey(x, z));
        }
        var tickets = new ChunkLease.Tickets(null);
        long centerKey = Position.chunkKey(-2, 1);
        try (ChunkLease center = new ChunkLease(tickets, fixture.world, centerKey, false)) {
            assertTrue(center.ready());
            try (ChunkLease border = new ChunkLease(tickets, fixture.world, centerKey, true)) {
                assertTrue(border.ready());
                assertFalse(border.centralUnavailable());
                assertEquals(9, fixture.activeTickets.size());
            }
            assertEquals(Set.of(centerKey), fixture.activeTickets);
        }
        assertTrue(fixture.activeTickets.isEmpty());
        assertEquals(10, fixture.generation.size());
        assertTrue(fixture.generation.stream().noneMatch(Boolean::booleanValue));
    }

    @Test void missingNeighborsDoNotHideAnExistingCentralChunk() throws Exception {
        Fixture fixture = new Fixture();
        fixture.available.add(Position.chunkKey(0, 0));
        try (ChunkLease lease = new ChunkLease(new ChunkLease.Tickets(null), fixture.world,
                Position.chunkKey(0, 0), true)) {
            assertTrue(lease.ready());
            assertTrue(lease.unavailable());
            assertFalse(lease.centralUnavailable());
            assertNotNull(lease.central());
        }
    }

    @Test void deliveredChunksAreHeldBeforePollingAndLateCallbacksAreIgnored() throws Exception {
        Fixture fixture = new Fixture();
        long key = Position.chunkKey(2, -4);
        fixture.available.add(key);
        fixture.delayed.add(key);
        var tickets = new ChunkLease.Tickets(null);
        try (ChunkLease lease = new ChunkLease(tickets, fixture.world, key, false)) {
            assertFalse(lease.ready());
            fixture.callbacks.removeFirst().run();
            assertEquals(Set.of(key), fixture.activeTickets, "Acquire in the loading callback");
        }
        try (ChunkLease lease = new ChunkLease(tickets, fixture.world, key, false)) {
            assertFalse(lease.ready());
        }
        fixture.callbacks.removeFirst().run();
        assertTrue(fixture.activeTickets.isEmpty());
    }

    @Test void retryCanRecoverACenterWithoutGenerationAndReportsMissingFullChunks() throws Exception {
        Fixture fixture = new Fixture();
        long key = Position.chunkKey(2, -4);
        fixture.availableOnRetry.add(key);
        var tickets = new ChunkLease.Tickets(null);
        try (ChunkLease lease = new ChunkLease(tickets, fixture.world, key, false)) {
            assertTrue(lease.ready());
            assertTrue(lease.centralRecovered());
            assertFalse(lease.centralUnavailable());
        }
        try (ChunkLease lease = new ChunkLease(tickets, fixture.world, Position.chunkKey(0, 0), false)) {
            assertTrue(lease.ready());
            assertEquals(ChunkLease.Failure.NO_FULL_CHUNK, lease.centralFailure());
            assertTrue(lease.ready());
        }
        assertEquals(List.of(false, false, false, false), fixture.generation);
    }

    @Test void anExpiredChunkHandleDoesNotAcquireATicketThatCouldLoadItAgain() throws Exception {
        Fixture fixture = new Fixture();
        long key = Position.chunkKey(2, -4);
        fixture.expired.add(key);
        try (ChunkLease lease = new ChunkLease(new ChunkLease.Tickets(null), fixture.world, key, false)) {
            assertTrue(lease.ready());
            assertEquals(ChunkLease.Failure.TICKET_EXPIRED, lease.centralFailure());
            assertTrue(fixture.activeTickets.isEmpty());
        }
        assertEquals(List.of(false, false), fixture.generation);
    }
}
