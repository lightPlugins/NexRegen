package io.nexstudios.nexregen.util;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import java.util.Optional;
import java.util.regex.Pattern;

public final class BlockDataSpec {

  private static final Pattern AGE_BRACKET = Pattern.compile("(?i)(^|,)age\\s*=\\s*\\d+(,|$)");
  private static final Pattern AGE_SPACE = Pattern.compile("(?i)\\bage\\s*:\\s*\\d+\\b");

  private BlockDataSpec() {}

  public static Optional<Material> toMaterial(String input) {
    String s = normalize(input);
    int bracket = s.indexOf('[');
    String matPart = (bracket >= 0) ? s.substring(0, bracket) : s;

    Material m = null;

    NamespacedKey key = NamespacedKey.fromString(matPart);
    if (key != null) {
      m = Registry.MATERIAL.get(key);
    }

    if (m == null) {
      m = Material.matchMaterial(matPart, true);
    }

    if (m == null && matPart.contains(":")) {
      String withoutNamespace = matPart.substring(matPart.indexOf(':') + 1);
      m = Material.matchMaterial(withoutNamespace, true);
    }

    return Optional.ofNullable(m);
  }

  public static BlockData toBlockData(String input) {
    String s = normalize(input);
    return Bukkit.createBlockData(s);
  }

  public static BlockData toBlockDataWithoutAge(String input) {
    String normalized = normalize(input);
    String stripped = stripAgeProperty(normalized);
    return Bukkit.createBlockData(stripped);
  }

  public static BlockData applyAgeIfPossible(BlockData data, Optional<Integer> age) {
    if (age.isEmpty()) return data;
    if (data instanceof Ageable ageable) {
      int v = age.get();
      int clamped = Math.max(0, Math.min(ageable.getMaximumAge(), v));
      ageable.setAge(clamped);
    }
    return data;
  }

  /**
   * Accepts specs like:
   * - "minecraft:wheat"
   * - "minecraft:wheat age:0"
   * - "minecraft:wheat[age=0]" (already valid)
   */
  public static String normalize(String input) {
    String raw = input.trim();
    if (raw.contains("[")) return raw;

    String[] parts = raw.split("\\s+");
    if (parts.length == 1) return parts[0];

    String mat = parts[0];
    StringBuilder props = new StringBuilder();
    for (int i = 1; i < parts.length; i++) {
      String p = parts[i].trim();
      if (p.isEmpty()) continue;
      String[] kv = p.split(":", 2);
      if (kv.length != 2) continue;
      if (!props.isEmpty()) props.append(",");
      props.append(kv[0]).append("=").append(kv[1]);
    }

    if (props.isEmpty()) return mat;
    return mat + "[" + props + "]";
  }

  private static String stripAgeProperty(String normalized) {
    int open = normalized.indexOf('[');
    if (open < 0) {
      // "minecraft:wheat age:7" path should not exist after normalize, but keep safe:
      return AGE_SPACE.matcher(normalized).replaceAll("").trim();
    }

    int close = normalized.lastIndexOf(']');
    if (close < open) return normalized;

    String mat = normalized.substring(0, open);
    String props = normalized.substring(open + 1, close);

    String cleaned = AGE_BRACKET.matcher(props).replaceAll(m -> {
      String pre = m.group(1);
      String post = m.group(2);
      if (",".equals(pre) && ",".equals(post)) return ",";
      return "";
    });

    cleaned = cleaned.replaceAll(",,", ",").replaceAll("^,", "").replaceAll(",$", "").trim();

    if (cleaned.isEmpty()) return mat;
    return mat + "[" + cleaned + "]";
  }
}