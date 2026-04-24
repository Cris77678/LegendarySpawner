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

    private int spawnIntervalMinutes = 30;
    private int spawnChancePercent = 80;
    private int minPlayersRequired = 3;
    private int spawnRadiusBlocks = 64;
    private int despawnAfterMinutes = 15;
    private int maxActiveLegendaries = 1;

    // --- Nuevas Integraciones ---
    private String discordWebhookUrl = "";
    private boolean enableVisualEffects = true;  // Rayos cosméticos
    private boolean enableGlobalSound = true;    // Sonido de Wither global
    // ----------------------------

    private String prefix = "&7[&6⚡ Legendario&7] ";
    private String msgSpawnAlert = "%prefix% &6¡Un &e%pokemon% &6legendario ha aparecido cerca de &e%player%&6! &8[%x%, %y%, %z%]";
    private String msgDespawn = "%prefix% &7El &e%pokemon% &7legendario ha desaparecido sin ser capturado.";
    private String msgForced = "%prefix% &a(Admin) Spawn de &e%pokemon% &aforzado cerca de &e%player%&a.";
    private String msgNoPlayers = "%prefix% &cNo hay suficientes jugadores online.";
    private String msgAlreadyActive = "%prefix% &cYa hay un legendario activo en el servidor.";
    private String msgRemoved = "%prefix% &aLegendario removido del mundo.";
    private String msgNoneActive = "%prefix% &cNo hay ningun legendario activo ahora mismo.";
    private String msgReload = "%prefix% &aConfiguracion recargada.";

    private List<String> blacklist = new ArrayList<>(Arrays.asList("eternatus", "necrozma"));

    private Map<String, List<String>> biomeLegendsMap = new LinkedHashMap<>(Map.ofEntries(
        Map.entry("*", new ArrayList<>(Arrays.asList("mewtwo"))),
        Map.entry("minecraft:plains", new ArrayList<>(Arrays.asList("entei", "raikou", "suicune", "ho_oh")))
    ));

    public void init() {
        Path path = Path.of(LegendarySpawner.CONFIG_PATH);
        var gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                try {
                    LegendarySpawner.config = gson.fromJson(Files.readString(path), SpawnerConfig.class);
                } catch (Exception e) {
                    LegendarySpawner.LOGGER.error("CRITICO: Error de sintaxis en config.json.", e);
                    return; 
                }
            }
            Path tempPath = Path.of(LegendarySpawner.CONFIG_PATH + ".tmp");
            Files.writeString(tempPath, gson.toJson(LegendarySpawner.config));
            Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {}
    }

    public List<String> getBlacklist() {
        if (blacklist == null) return new ArrayList<>(); 
        return blacklist.stream().map(String::trim).map(String::toLowerCase).toList();
    }

    public Map<String, List<String>> getBiomeLegendsMap() {
        return biomeLegendsMap == null ? new HashMap<>() : biomeLegendsMap;
    }

    public String format(String msg, Object... replacements) {
        if (msg == null) return "";
        msg = msg.replace("%prefix%", prefix != null ? prefix : "");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            msg = msg.replace(String.valueOf(replacements[i]), String.valueOf(replacements[i + 1]));
        }
        return msg;
    }
}