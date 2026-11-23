package dev.foliagerecovery;

import java.util.*;

/** Bounded search through adjacent leaves to compare reachable trunk distances. */
public final class OwnerProbe {
    public record Cell(String material, boolean persistent) {}
    public interface Grid { Cell get(Position position); }
    private record Node(Position position, int distance) {}
    private static final int[][] DIRECTIONS = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    private final Grid grid;
    private final Deque<Node> queue = new ArrayDeque<>();
    private final Set<Position> visited = new HashSet<>();
    private static final int OUT_OF_REACH = 99;
    private int birch = OUT_OF_REACH;
    private int spruce = OUT_OF_REACH;
    private int oak = OUT_OF_REACH;
    private int other = OUT_OF_REACH;
    private int birchNeighbors, spruceNeighbors, oakNeighbors;
    private final String nativeSpecies;
    private boolean finished;
    private String reason;
    private String replacement;

    public OwnerProbe(Grid grid, Position seed) {
        this(grid, seed, "ORANGE_POPLAR_LEAVES");
    }
    public OwnerProbe(Grid grid, Position seed, String leafMaterial) {
        this.grid = grid;
        nativeSpecies = leafMaterial.equals("ACACIA_LEAVES") ? "ACACIA_" : "POPLAR_";
        queue.add(new Node(seed, 0));
        visited.add(seed);
    }
    public static boolean painted(String material) {
        return material.equals("ACACIA_LEAVES") || material.equals("RED_POPLAR_LEAVES")
                || material.equals("ORANGE_POPLAR_LEAVES") || material.equals("YELLOW_POPLAR_LEAVES");
    }
    public void step() {
        if (finished) return;
        if (queue.isEmpty()) {
            selectReplacement();
            return;
        }
        Node node = queue.removeFirst();
        Cell cell = grid.get(node.position());
        if (cell == null) { reject("unavailable-context"); return; }
        String material = cell.material();
        if (material.endsWith("_LOG") || material.endsWith("_WOOD")) {
            String wood = material.startsWith("STRIPPED_") ? material.substring(9) : material;
            if (wood.startsWith(nativeSpecies)) {
                reject("protected-native-wood");
            } else if (wood.startsWith("BIRCH_")) birch = Math.min(birch, node.distance());
            else if (wood.startsWith("SPRUCE_")) spruce = Math.min(spruce, node.distance());
            else if (nativeSpecies.equals("POPLAR_") && wood.startsWith("OAK_")) {
                oak = Math.min(oak, node.distance());
            }
            else other = Math.min(other, node.distance());
            return;
        }
        if (!material.endsWith("_LEAVES") || cell.persistent()) return;
        if (node.distance() == 1) {
            switch (material) {
                case "BIRCH_LEAVES" -> birchNeighbors++;
                case "SPRUCE_LEAVES" -> spruceNeighbors++;
                case "OAK_LEAVES" -> oakNeighbors++;
                default -> { }
            }
        }
        if (node.distance() >= 6) return;
        for (int[] d : DIRECTIONS) {
            Position next = node.position().offset(d[0], d[1], d[2]);
            if (visited.add(next)) queue.addLast(new Node(next, node.distance() + 1));
        }
    }
    private void selectReplacement() {
        finished = true;
        int originalSpeciesDistance = Math.min(birch, spruce);
        int closest = Math.min(originalSpeciesDistance, oak);
        String canopy = supportedCanopy(closest);
        if (closest == OUT_OF_REACH) {
            reason = other == OUT_OF_REACH ? "no-trunk-evidence" : "other-wood-only";
        } else if (other - closest < 2) {
            if (canopy != null) replacement = canopy;
            else reason = "competing-other-wood";
        } else if (oak < originalSpeciesDistance) {
            replacement = "OAK_LEAVES";
        } else if (Math.max(birch, spruce) - originalSpeciesDistance < 2) {
            if (canopy != null) replacement = canopy;
            else reason = "mixed-birch-spruce-evidence";
        } else {
            replacement = birch < spruce ? "BIRCH_LEAVES" : "SPRUCE_LEAVES";
        }
    }
    private String supportedCanopy(int closest) {
        if (!nativeSpecies.equals("POPLAR_") || closest == OUT_OF_REACH) return null;
        int maxSupportDistance = Math.min(closest + 1, other);
        if (birchNeighbors >= 2 && spruceNeighbors == 0 && oakNeighbors == 0
                && birch != OUT_OF_REACH && birch <= maxSupportDistance) return "BIRCH_LEAVES";
        if (spruceNeighbors >= 2 && birchNeighbors == 0 && oakNeighbors == 0
                && spruce != OUT_OF_REACH && spruce <= maxSupportDistance) return "SPRUCE_LEAVES";
        if (oakNeighbors >= 2 && birchNeighbors == 0 && spruceNeighbors == 0
                && oak != OUT_OF_REACH && oak <= maxSupportDistance) return "OAK_LEAVES";
        return null;
    }
    private void reject(String why) { finished = true; reason = why; }
    public boolean finished() { return finished; }
    public boolean accepted() { return finished && replacement != null; }
    public String reason() { return reason; }
    public String original() {
        if (!accepted()) throw new IllegalStateException("No unambiguous original");
        return replacement;
    }
}
