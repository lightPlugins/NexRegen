package io.nexstudios.nexregen.util;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;

import java.util.*;

public final class AffectedBlockCalculator {

  private AffectedBlockCalculator() {}

  public static Affected computeAffected(Block base) {
    if (base == null) {
      return new Affected(null, null, List.of(), List.of(), null);
    }

    if (base.getBlockData() instanceof Bisected bisected) {
      Block other = bisected.getHalf() == Bisected.Half.BOTTOM
          ? base.getRelative(0, 1, 0)
          : base.getRelative(0, -1, 0);

      List<Block> withBreak = List.of(base, other);
      return new Affected(null, null, withBreak, List.of(), other);
    }

    Material type = base.getType();
    if (type == Material.CACTUS || type == Material.SUGAR_CANE || type == Material.BAMBOO) {
      Block bottom = base;
      Block cursorDown = base.getRelative(0, -1, 0);
      while (cursorDown.getType() == type) {
        bottom = cursorDown;
        cursorDown = cursorDown.getRelative(0, -1, 0);
      }

      List<Block> column = new ArrayList<>();
      column.add(bottom);

      Block cursorUp = bottom.getRelative(0, 1, 0);
      while (cursorUp.getType() == type) {
        column.add(cursorUp);
        cursorUp = cursorUp.getRelative(0, 1, 0);
      }

      Set<BlockKey> columnKeys = new HashSet<>();
      for (Block b : column) {
        columnKeys.add(BlockKey.of(b.getLocation()));
      }

      if (!columnKeys.contains(BlockKey.of(base.getLocation()))) {
        column.add(base);
      }

      column.sort(Comparator.comparingInt(Block::getY));

      List<Block> viewOnly = new ArrayList<>();
      if (type == Material.CACTUS) {
        Block top = column.getLast();
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
    BlockKey needleKey = BlockKey.of(needle.getLocation());
    for (Block b : blocks) {
      if (b == null) continue;
      if (BlockKey.of(b.getLocation()).equals(needleKey)) {
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