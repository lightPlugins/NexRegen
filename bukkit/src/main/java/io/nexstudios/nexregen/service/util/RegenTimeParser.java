package io.nexstudios.nexregen.service.util;

import java.util.concurrent.ThreadLocalRandom;

public final class RegenTimeParser {

  private RegenTimeParser() {}

  /**
   * Supported:
   * - "5" -> 5 seconds
   * - "3-5" -> random 3..5 seconds
   */
  public static long parseToTicks(String spec) {
    String s = spec.trim();
    if (s.contains("-")) {
      String[] p = s.split("-", 2);
      int min = Integer.parseInt(p[0].trim());
      int max = Integer.parseInt(p[1].trim());
      int sec = ThreadLocalRandom.current().nextInt(Math.min(min, max), Math.max(min, max) + 1);
      return sec * 20L;
    }
    int sec = Integer.parseInt(s);
    return sec * 20L;
  }
}