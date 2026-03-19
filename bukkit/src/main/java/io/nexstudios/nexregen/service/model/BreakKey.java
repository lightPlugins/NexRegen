package io.nexstudios.nexregen.service.model;

import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.Locale;

public enum BreakKey {
  LEFT_CLICK,
  SHIFT_LEFT_CLICK,
  RIGHT_CLICK,
  SHIFT_RIGHT_CLICK;

  public static BreakKey fromConfigString(String raw) {
    String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    return switch (s) {
      case "left-click", "left_click", "left" -> LEFT_CLICK;
      case "shift-left-click", "shift_left_click", "shift-left", "sneak-left-click" -> SHIFT_LEFT_CLICK;
      case "right-click", "right_click", "right" -> RIGHT_CLICK;
      case "shift-right-click", "shift_right_click", "shift-right", "sneak-right-click" -> SHIFT_RIGHT_CLICK;
      default -> throw new IllegalArgumentException("Unknown break-key: " + raw);
    };
  }

  public static BreakKey fromInteract(PlayerInteractEvent event) {
    if (event == null) return null;
    Action a = event.getAction();
    if (a != Action.RIGHT_CLICK_BLOCK) return null;

    boolean sneaking = event.getPlayer().isSneaking();
    return sneaking ? SHIFT_RIGHT_CLICK : RIGHT_CLICK;
  }

  public static BreakKey fromBreak(boolean sneaking) {
    return sneaking ? SHIFT_LEFT_CLICK : LEFT_CLICK;
  }

  public String toConfigString() {
    return switch (this) {
      case LEFT_CLICK -> "left-click";
      case SHIFT_LEFT_CLICK -> "shift-left-click";
      case RIGHT_CLICK -> "right-click";
      case SHIFT_RIGHT_CLICK -> "shift-right-click";
    };
  }
}