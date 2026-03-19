package io.nexstudios.nexregen.util;

import lombok.NoArgsConstructor;

import java.util.concurrent.ThreadLocalRandom;

@NoArgsConstructor()
public final class RegenTimeParser {

  /**
   * Parses a time specification in seconds or a range of seconds into game ticks.
   * If the input specifies a range (e.g., "5-10"), a random value within the range is chosen and converted to ticks.
   * If the input specifies a single value (e.g., "5"), it is directly converted to ticks.
   *
   * @param spec the time specification as a string, either a single integer
   *             or a range in the format "min-max".
   * @return the calculated number of ticks, where 1 second equals 20 ticks.
   * @throws NumberFormatException if the input string is not in a valid format
   *                               or cannot be converted to an integer.
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