package com.voqii.timingghost;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Locale;
import java.util.logging.Logger;

record Config(Mode defaultMode, Material material, boolean glow, Color glowColor, boolean showLabel,
              float headYaw, int maxRunSeconds) {

    enum Mode {
        OFF, PB, BEST, NEXT;

        static Mode parse(String s) {
            try {
                return s == null ? null : valueOf(s.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    static Config load(FileConfiguration cfg, Logger log) {
        Mode mode = Mode.parse(cfg.getString("default-mode"));
        if (mode == null) {
            log.warning("default-mode must be pb, best, next or off; using pb");
            mode = Mode.PB;
        }
        Material material = Material.matchMaterial(cfg.getString("material", ""));
        if (material == null || !material.isBlock()) {
            log.warning("material isn't a block; using LIGHT_BLUE_STAINED_GLASS");
            material = Material.LIGHT_BLUE_STAINED_GLASS;
        }
        Color glowColor;
        try {
            glowColor = Color.fromRGB(Integer.parseInt(cfg.getString("glow-color", "#8FE3FF").replace("#", ""), 16));
        } catch (IllegalArgumentException e) {
            log.warning("glow-color must look like '#8FE3FF'");
            glowColor = Color.fromRGB(0x8FE3FF);
        }
        return new Config(mode, material, cfg.getBoolean("glow", true), glowColor, cfg.getBoolean("show-label", true),
                (float) cfg.getDouble("head-yaw", 0), Math.max(10, cfg.getInt("max-run-seconds", 900)));
    }
}
