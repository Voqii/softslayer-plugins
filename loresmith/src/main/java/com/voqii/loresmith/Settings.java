package com.voqii.loresmith;

import org.bukkit.Material;

import java.util.Set;

/** config.yml, read once on enable and on /lore reload. */
record Settings(int maxLines, int maxLength, boolean unmarkedEditable, Set<Material> blockedMaterials,
                Set<String> blockedNamespaces, boolean backdrop, boolean log) {
}
