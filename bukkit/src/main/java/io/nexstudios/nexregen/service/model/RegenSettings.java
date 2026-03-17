package io.nexstudios.nexregen.service.model;

public record RegenSettings(
    boolean dropItems,
    boolean dropXp,
    boolean replaceOnlyBottom
) {
  public static RegenSettings defaults() {
    return new RegenSettings(false, false, false);
  }
}