package io.nexstudios.nexregen.service.model;

public record RegenSettings(
    boolean applyPhysics,
    boolean dropItems,
    boolean dropXp
) {
  public static RegenSettings defaults() {
    return new RegenSettings(false, false, false);
  }
}