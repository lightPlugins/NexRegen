package io.nexstudios.nexregen.service.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class AffectedBlockCalculator {

  private AffectedBlockCalculator() {}

  public static Affected computeAffected(Block base) {
    if (base == null) {
      return new Affected(null, null, List.of(), List.of(), null);
    }

    // Double height handling
    if (base.getBlockData() instanceof Bisected bisected) {
      Block other = bisected.getHalf() == Bisected.Half.BOTTOM
          ? base.getRelative(0, 1, 0)
          : base.getRelative(0, -1, 0);

      List<Block> withBreak = List.of(base, other);
      return new Affected(null, null, withBreak, List.of(), other);
    }

    Material type = base.getType();
    if (type == Material.CACTUS || type == Material.SUGAR_CANE || type == Material.BAMBOO) {
      // Find bottom-most (connected downwards)
      Block bottom = base;
      Block cursorDown = base.getRelative(0, -1, 0);
      while (cursorDown.getType() == type) {
        bottom = cursorDown;
        cursorDown = cursorDown.getRelative(0, -1, 0);
      }

      // Collect full column upwards
      List<Block> column = new ArrayList<>();
      column.add(bottom);

      Block cursorUp = bottom.getRelative(0, 1, 0);
      while (cursorUp.getType() == type) {
        column.add(cursorUp);
        cursorUp = cursorUp.getRelative(0, 1, 0);
      }

      if (!containsSameBlock(column, base)) {
        column.add(base);
      }

      column.sort(Comparator.comparingInt(Block::getY));

      // cactus flower on top of cactus. No break event for it.
      List<Block> viewOnly = new ArrayList<>();
      if (type == Material.CACTUS) {
        Block top = column.getLast(); // Java 21: SequencedCollection
        Block aboveTop = top.getRelative(0, 1, 0);
        if (aboveTop.getType() == Material.CACTUS_FLOWER) {
          viewOnly.add(aboveTop);
        }
      }

      return new Affected(List.copyOf(column), bottom, List.copyOf(column), List.copyOf(viewOnly), null);
    }

    return new Affected(null, null, List.of(base), List.of(), null);
  }

  public static boolean containsSameBlock(List<Block> blocks, Block needle) {
    if (blocks == null || needle == null) return false;
    for (Block b : blocks) {
      if (b == null) continue;
      if (b.getWorld().equals(needle.getWorld())
          && b.getX() == needle.getX()
          && b.getY() == needle.getY()
          && b.getZ() == needle.getZ()) {
        return true;
      }
    }
    return false;
  }

  public record Affected(
      List<Block> columnBlocks,
      Block bottomMost,
      List<Block> blocksWithBreakEvent,
      List<Block> blocksWithoutBreakEvent,
      Block doubleHeightOtherHalf
  ) {}
}