package io.nexstudios.regen.regen.model;

import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

@Getter
@Setter
public class ActiveRegen {

    // DB primary key
    private Long id;

    private String worldName;
    private int x;
    private int y;
    private int z;

    private String originalMaterial;
    private String originalBlockData;

    private String replacementMaterial;
    private String replacementBlockData;

    private long regenAt; // Unix time in millis

    // Referenz auf Regenerator-System (nur regenId wird noch genutzt)
    private String regenId;

    // Convenience: Location bauen
    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    public static ActiveRegen fromLocation(Location location) {
        ActiveRegen active = new ActiveRegen();
        active.setWorldName(location.getWorld().getName());
        active.setX(location.getBlockX());
        active.setY(location.getBlockY());
        active.setZ(location.getBlockZ());
        return active;
    }
}