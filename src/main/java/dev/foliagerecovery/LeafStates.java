package dev.foliagerecovery;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Leaves;

final class LeafStates {
    private LeafStates() {}
    static void copyProperties(BlockData before, BlockData after) {
        if (before instanceof Leaves from && after instanceof Leaves to) {
            to.setDistance(from.getDistance()); to.setPersistent(from.isPersistent());
        }
        if (before instanceof Waterlogged from && after instanceof Waterlogged to) {
            to.setWaterlogged(from.isWaterlogged());
        }
    }
}
