package com.tuservidor.legendaryspawner.config;

import com.google.gson.GsonBuilder;
import com.tuservidor.legendaryspawner.LegendarySpawner;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Getter
@Setter
public class SpawnerConfig {

    /** Minutes between each spawn attempt */
    private int spawnIntervalMinutes = 30;

    /** Probability (0-100) that a legendary actually spawns each interval */
    private int spawnChancePercent = 80;

    /** Minimum players online for a spawn to occur */
    private int minPlayersRequired = 3;

    /** Radius around the chosen player where the legendary spawns (blocks) */
    private int spawnRadiusBlocks = 64;

    /** Minutes until the legendary despawns if uncaught (0 = never auto-despawn) */
    private int despawnAfterMinutes = 15;

    /** Max active legendaries at once - new spawn won't trigger if reached */
    private int maxActiveLegendaries = 1;

    // ── Messages ──────────────────────────────────────────────────────────────
    private String prefix = "&7[&6⚡ Legendario&7] ";
    private String msgSpawnAlert =
        "%prefix% &6¡Un &e%pokemon% &6legendario ha aparecido cerca de &e%player%&6! &8[%x%, %y%, %z%]";
    private String msgDespawn =
        "%prefix% &7El &e%pokemon% &7legendario ha desaparecido sin ser capturado.";
    private String msgForced =
        "%prefix% &a(Admin) Spawn de &e%pokemon% &aforzado cerca de &e%player%&a.";
    private String msgNoPlayers =
        "%prefix% &cNo hay suficientes jugadores online (%online%/%required%).";
    private String msgAlreadyActive =
        "%prefix% &cYa hay un legendario activo en el servidor.";
    private String msgRemoved =
        "%prefix% &aLegendario removido del mundo.";
    private String msgNoneActive =
        "%prefix% &cNo hay ningún legendario activo ahora mismo.";
    private String msgReload =
        "%prefix% &aConfiguración recargada.";

    /** Species that should NEVER spawn via this system (showdown IDs) */
    private List<String> blacklist = new ArrayList<>(List.of(
        "eternatus", "necrozma"
    ));

    /**
     * Biome-to-legendary map.
     * Key: biome ID (e.g. "minecraft:ocean", "minecraft:plains")
     * Value: list of legendary showdown IDs that can spawn in that biome.
     *
     * Use "*" as key for legendaries that can spawn in ANY biome.
     * If the player's biome has no entry, falls back to "*" list.
     * If "*" is also empty, picks fully random.
     */
    private Map<String, List<String>> biomeLegendsMap = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("*", List.of()),  // Empty = no global fallback, use biome-specific only

        // Water / Ocean
        Map.entry("minecraft:ocean",             List.of("kyogre", "lugia", "manaphy", "phione")),
        Map.entry("minecraft:deep_ocean",        List.of("kyogre", "lugia", "manaphy")),
        Map.entry("minecraft:cold_ocean",        List.of("kyogre", "suicune", "articuno")),
        Map.entry("minecraft:frozen_ocean",      List.of("articuno", "suicune", "kyogre")),
        Map.entry("minecraft:warm_ocean",        List.of("kyogre", "lugia", "manaphy")),
        Map.entry("minecraft:lukewarm_ocean",    List.of("kyogre", "lugia", "manaphy")),
        Map.entry("minecraft:beach",             List.of("lugia", "manaphy", "tapu_fini")),

        // Snow / Ice
        Map.entry("minecraft:snowy_plains",      List.of("articuno", "regice", "suicune")),
        Map.entry("minecraft:ice_spikes",        List.of("articuno", "regice")),
        Map.entry("minecraft:frozen_river",      List.of("articuno", "suicune", "regice")),
        Map.entry("minecraft:snowy_taiga",       List.of("articuno", "suicune")),
        Map.entry("minecraft:grove",             List.of("articuno", "regice", "suicune")),
        Map.entry("minecraft:snowy_slopes",      List.of("articuno", "regice")),
        Map.entry("minecraft:jagged_peaks",      List.of("articuno", "regice", "rayquaza")),
        Map.entry("minecraft:frozen_peaks",      List.of("articuno", "regice")),

        // Mountains
        Map.entry("minecraft:stony_peaks",       List.of("regirock", "terrakion", "cobalion")),
        Map.entry("minecraft:meadow",            List.of("cobalion", "virizion", "keldeo")),
        Map.entry("minecraft:windswept_hills",   List.of("rayquaza", "registeel", "cobalion")),
        Map.entry("minecraft:windswept_gravelly_hills", List.of("rayquaza", "regirock")),
        Map.entry("minecraft:windswept_forest",  List.of("virizion", "cobalion", "celebi")),
        Map.entry("minecraft:windswept_savanna", List.of("entei", "raikou", "terrakion")),

        // Forest
        Map.entry("minecraft:forest",            List.of("celebi", "virizion", "shaymin")),
        Map.entry("minecraft:flower_forest",     List.of("shaymin", "celebi", "virizion")),
        Map.entry("minecraft:birch_forest",      List.of("celebi", "shaymin")),
        Map.entry("minecraft:old_growth_birch_forest", List.of("celebi", "shaymin")),
        Map.entry("minecraft:taiga",             List.of("suicune", "celebi", "virizion")),
        Map.entry("minecraft:old_growth_pine_taiga", List.of("celebi", "suicune")),
        Map.entry("minecraft:old_growth_spruce_taiga", List.of("celebi", "suicune")),
        Map.entry("minecraft:dark_forest",       List.of("darkrai", "yveltal", "xerneas")),

        // Plains / Grassland
        Map.entry("minecraft:plains",            List.of("entei", "raikou", "suicune", "ho_oh")),
        Map.entry("minecraft:sunflower_plains",  List.of("entei", "raikou", "jirachi")),
        Map.entry("minecraft:savanna",           List.of("entei", "raikou", "terrakion")),
        Map.entry("minecraft:savanna_plateau",   List.of("entei", "terrakion", "ho_oh")),

        // Desert / Dry
        Map.entry("minecraft:desert",            List.of("groudon", "regirock", "landorus")),
        Map.entry("minecraft:badlands",          List.of("groudon", "regirock", "terrakion")),
        Map.entry("minecraft:eroded_badlands",   List.of("groudon", "regirock")),
        Map.entry("minecraft:wooded_badlands",   List.of("groudon", "entei")),

        // Jungle
        Map.entry("minecraft:jungle",            List.of("celebi", "virizion", "shaymin", "zarude")),
        Map.entry("minecraft:sparse_jungle",     List.of("celebi", "virizion")),
        Map.entry("minecraft:bamboo_jungle",     List.of("celebi", "shaymin")),

        // Swamp / Wetlands
        Map.entry("minecraft:swamp",             List.of("darkrai", "marshadow", "keldeo")),
        Map.entry("minecraft:mangrove_swamp",    List.of("keldeo", "marshadow")),
        Map.entry("minecraft:river",             List.of("suicune", "keldeo", "manaphy")),

        // Sky / End
        Map.entry("minecraft:the_end",           List.of("giratina", "necrozma", "lunala")),
        Map.entry("minecraft:end_highlands",     List.of("giratina", "yveltal")),

        // Nether
        Map.entry("minecraft:nether_wastes",     List.of("giratina", "darkrai", "heatran")),
        Map.entry("minecraft:basalt_deltas",     List.of("heatran", "volcanion", "entei")),
        Map.entry("minecraft:crimson_forest",    List.of("heatran", "yveltal")),
        Map.entry("minecraft:soul_sand_valley",  List.of("giratina", "darkrai")),
        Map.entry("minecraft:warped_forest",     List.of("giratina", "marshadow")),

        // Sky
        Map.entry("minecraft:the_void",          List.of("rayquaza", "giratina"))
    ));

    // ─────────────────────────────────────────────────────────────────────────

    public void init() {
        Path path = Path.of(LegendarySpawner.CONFIG_PATH);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                LegendarySpawner.config = gson.fromJson(Files.readString(path), SpawnerConfig.class);
            }
            Files.writeString(path, gson.toJson(LegendarySpawner.config));
        } catch (IOException e) {
            LegendarySpawner.LOGGER.error("Failed to load config", e);
        }
    }

    public String format(String msg, Object... replacements) {
        msg = msg.replace("%prefix%", prefix);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        }
        return msg;
    }
}
