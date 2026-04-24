package com.tuservidor.legendaryspawner.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.tuservidor.legendaryspawner.LegendarySpawner;
import net.minecraft.entity.SpawnReason;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.*;

public class LegendarySpawnManager {

    // Active legendary entities (UUID -> entity id)
    private static final Map<UUID, ActiveLegendary> activeLegendaries = new ConcurrentHashMap<>();
    // Entities currently being spawned - prevents the spawn event from cancelling our own spawns
    private static final Set<UUID> pendingSpawn = ConcurrentHashMap.newKeySet();

    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> spawnTask;

    public static void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LegendarySpawner-Scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
        LegendarySpawner.LOGGER.info("LegendarySpawner scheduler started. Interval: "
            + LegendarySpawner.config.getSpawnIntervalMinutes() + " min");
    }

    public static void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public static void reschedule() {
        if (spawnTask != null) spawnTask.cancel(false);
        scheduleNext();
    }

    private static void scheduleNext() {
        int interval = LegendarySpawner.config.getSpawnIntervalMinutes();
        spawnTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                attemptSpawn(false, null, null);
            } catch (Exception e) {
                LegendarySpawner.LOGGER.error("Error in spawn task", e);
            }
        }, interval, interval, TimeUnit.MINUTES);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called by the timer and by admin force command */
    public static boolean attemptSpawn(boolean forced, ServerPlayerEntity adminSource, String forcedSpecies) {
        var server = LegendarySpawner.server;
        if (server == null) return false;

        var cfg = LegendarySpawner.config;
        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());

        // Pre-checks (run on scheduler thread - safe to read these)
        cleanupDead();
        if (activeLegendaries.size() >= cfg.getMaxActiveLegendaries()) {
            if (adminSource != null)
                sendMsg(adminSource, cfg.format(cfg.getMsgAlreadyActive()));
            return false;
        }
        if (!forced && players.size() < cfg.getMinPlayersRequired()) {
            LegendarySpawner.LOGGER.info("Spawn skipped: not enough players ({}/{})",
                players.size(), cfg.getMinPlayersRequired());
            return false;
        }
        if (!forced) {
            int roll = new Random().nextInt(100);
            if (roll >= cfg.getSpawnChancePercent()) {
                LegendarySpawner.LOGGER.info("Spawn skipped: probability roll failed ({}/{})",
                    roll, cfg.getSpawnChancePercent());
                return false;
            }
        }

        // Pick target player
        ServerPlayerEntity target = players.get(new Random().nextInt(players.size()));

        // For forced spawn with specific species, resolve it here
        // For normal spawns, pass null so biome-aware picker runs on server thread
        Species speciesHint = null;
        if (forcedSpecies != null) {
            speciesHint = PokemonSpecies.INSTANCE.getByIdentifier(
                net.minecraft.util.Identifier.of("cobblemon", forcedSpecies));
            if (speciesHint == null) {
                LegendarySpawner.LOGGER.warn("Unknown species: {}", forcedSpecies);
                return false;
            }
        }

        // Everything from here runs on the SERVER THREAD to avoid race conditions
        final Species finalSpecies = speciesHint;
        final ServerPlayerEntity finalTarget = target;
        final ServerPlayerEntity finalAdmin = adminSource;

        server.execute(() -> spawnOnServerThread(cfg, finalSpecies, finalTarget, finalAdmin, forced));
        return true;
    }

    /** Runs entirely on the server thread - no race conditions. */
    private static void spawnOnServerThread(
            com.tuservidor.legendaryspawner.config.SpawnerConfig cfg,
            Species speciesHint, ServerPlayerEntity target,
            ServerPlayerEntity adminSource, boolean forced) {
        try {
            // Use biome-aware picker (speciesHint is only set for forced /ls spawn <species>)
            Species species = speciesHint != null ? speciesHint : pickLegendaryForPlayer(target);
            if (species == null) {
                LegendarySpawner.LOGGER.warn("No legendary species found to spawn.");
                return;
            }
            ServerWorld world = (ServerWorld) target.getWorld();
            Vec3d pos = findSpawnPos(target, world, cfg.getSpawnRadiusBlocks());

            // Create pokemon and mark as managed before entity spawn event fires
            Pokemon pokemon = PokemonProperties.Companion.parse(species.showdownId()).create();
            UUID trackId = UUID.randomUUID();
            pokemon.getPersistentData().putString("legendaryspawner_id", trackId.toString());
            pendingSpawn.add(trackId);

            // Spawn entity - POKEMON_ENTITY_SPAWN fires here, isManagedEntity returns true
            PokemonEntity entity;
            try {
                entity = new PokemonEntity(world, pokemon, com.cobblemon.mod.common.CobblemonEntities.POKEMON);
                entity.setPos(pos.x, pos.y, pos.z);
                world.spawnEntityAndPassengers(entity);
            } finally {
                pendingSpawn.remove(trackId);
            }

            // Register as active immediately after spawn
            ActiveLegendary active = new ActiveLegendary(trackId, entity, species.getName(),
                world.getRegistryKey().getValue().toString());
            activeLegendaries.put(trackId, active);

            LegendarySpawner.LOGGER.info("Spawned legendary {} (trackId: {})", species.getName(), trackId);

            // Broadcast alert
            String alert = cfg.format(cfg.getMsgSpawnAlert(),
                "%pokemon%", species.getName(),
                "%player%", target.getName().getString(),
                "%x%", String.valueOf((int) pos.x),
                "%y%", String.valueOf((int) pos.y),
                "%z%", String.valueOf((int) pos.z));
            broadcastAll(alert);

            if (forced && adminSource != null) {
                sendMsg(adminSource, cfg.format(cfg.getMsgForced(),
                    "%pokemon%", species.getName(),
                    "%player%", target.getName().getString()));
            }

            // Schedule despawn
            int despawnMin = cfg.getDespawnAfterMinutes();
            if (despawnMin > 0) {
                scheduler.schedule(() -> despawn(trackId), despawnMin, TimeUnit.MINUTES);
            }

        } catch (Exception e) {
            LegendarySpawner.LOGGER.error("Error spawning legendary on server thread", e);
        }
    }

    public static int removeAll(ServerPlayerEntity admin) {
        cleanupDead();
        int count = 0;
        for (ActiveLegendary active : activeLegendaries.values()) {
            if (killEntity(active)) count++;
        }
        activeLegendaries.clear();
        if (admin != null)
            sendMsg(admin, LegendarySpawner.config.format(LegendarySpawner.config.getMsgRemoved()));
        return count;
    }

    public static int getActiveCount() {
        cleanupDead();
        return activeLegendaries.size();
    }

    /** Returns true if this entity was spawned by our system (not a natural spawn). */
    public static boolean isManagedEntity(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        // Check if it's in our active tracked set
        for (ActiveLegendary active : activeLegendaries.values()) {
            if (active.entity() == entity) return true;
        }
        // Check if it has our pending spawn tag (being spawned right now)
        String tag = entity.getPokemon().getPersistentData().getString("legendaryspawner_id");
        if (!tag.isEmpty()) {
            try {
                return pendingSpawn.contains(UUID.fromString(tag));
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void despawn(UUID trackId) {
        ActiveLegendary active = activeLegendaries.remove(trackId);
        if (active == null) return;
        if (active.entity().isRemoved()) return; // already gone (caught/defeated)

        String name = active.speciesName();
        killEntity(active);

        broadcastAll(LegendarySpawner.config.format(
            LegendarySpawner.config.getMsgDespawn(), "%pokemon%", name));
    }

    private static boolean killEntity(ActiveLegendary active) {
        try {
            var entity = active.entity();
            if (!entity.isRemoved()) {
                LegendarySpawner.server.execute(entity::discard);
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void cleanupDead() {
        activeLegendaries.entrySet().removeIf(e -> e.getValue().entity().isRemoved());
    }

    /**
     * Picks a random legendary compatible with the player's current biome
     * using the biomeLegendsMap defined in config.
     */
    private static Species pickLegendaryForPlayer(ServerPlayerEntity player) {
        List<String> blacklist = LegendarySpawner.config.getBlacklist();
        Map<String, List<String>> biomeMap = LegendarySpawner.config.getBiomeLegendsMap();

        // Get player's biome
        var biomeKey = player.getServerWorld().getBiome(player.getBlockPos())
            .getKey().orElse(null);

        String biomeString = biomeKey != null ? biomeKey.getValue().toString() : null;
        LegendarySpawner.LOGGER.info("Player biome: {}", biomeString);

        // Build candidate list from config map
        List<String> candidates = new ArrayList<>();

        if (biomeString != null && biomeMap.containsKey(biomeString)) {
            candidates.addAll(biomeMap.get(biomeString));
        }

        // Also add global "*" entries
        List<String> global = biomeMap.getOrDefault("*", List.of());
        for (String s : global) {
            if (!candidates.contains(s)) candidates.add(s);
        }

        // Remove blacklisted
        candidates.removeAll(blacklist);

        // Resolve species
        List<Species> pool = new ArrayList<>();
        for (String id : candidates) {
            Species s = com.cobblemon.mod.common.api.pokemon.PokemonSpecies.INSTANCE
                .getByIdentifier(net.minecraft.util.Identifier.of(
                    id.contains(":") ? id.split(":")[0] : "cobblemon",
                    id.contains(":") ? id.split(":")[1].toLowerCase() : id.toLowerCase()
                ));
            if (s != null) pool.add(s);
        }

        if (!pool.isEmpty()) {
            LegendarySpawner.LOGGER.info("Biome-compatible legendaries for {}: {}",
                biomeString, pool.stream().map(Species::showdownId).toList());
            return pool.get(new Random().nextInt(pool.size()));
        }

        // Fallback: any legendary not blacklisted
        LegendarySpawner.LOGGER.info("No biome entry for {}, picking random legendary.", biomeString);
        List<Species> allLegendaries = new ArrayList<>();
        for (Species species : com.cobblemon.mod.common.api.pokemon.PokemonSpecies.INSTANCE.getImplemented()) {
            if (blacklist.contains(species.showdownId())) continue;
            if (species.getLabels().contains("legendary")) allLegendaries.add(species);
        }
        if (allLegendaries.isEmpty()) return null;
        return allLegendaries.get(new Random().nextInt(allLegendaries.size()));
    }

    private static Species pickRandomLegendary() {
        List<String> blacklist = LegendarySpawner.config.getBlacklist();
        List<Species> pool = new ArrayList<>();
        for (Species species : com.cobblemon.mod.common.api.pokemon.PokemonSpecies.INSTANCE.getImplemented()) {
            if (blacklist.contains(species.showdownId())) continue;
            if (species.getLabels().contains("legendary")) pool.add(species);
        }
        if (pool.isEmpty()) return null;
        return pool.get(new Random().nextInt(pool.size()));
    }

    private static Vec3d findSpawnPos(ServerPlayerEntity player, ServerWorld world, int radius) {
        Random rand = new Random();
        double px = player.getX();
        double pz = player.getZ();

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rand.nextDouble() * Math.PI * 2;
            double dist  = radius * 0.5 + rand.nextDouble() * radius * 0.5;
            double x = px + Math.cos(angle) * dist;
            double z = pz + Math.sin(angle) * dist;
            int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                (int) x, (int) z);
            if (y > world.getBottomY()) {
                return new Vec3d(x, y, z);
            }
        }
        // Fallback: spawn right on the player
        return player.getPos().add(10, 0, 0);
    }

    private static void broadcastAll(String rawMsg) {
        var server = LegendarySpawner.server;
        if (server == null) return;
        Text text = net.minecraft.text.Text.literal(
            rawMsg.replaceAll("&[0-9a-fk-or]|<#[0-9a-fA-F]{6}>", "")
                  .replaceAll("%prefix%", "")
        );
        // Use adventure-style coloring via literal (simple approach)
        server.execute(() ->
            server.getPlayerManager().broadcast(
                net.minecraft.text.Text.literal(colorize(rawMsg)), false));
    }

    private static void sendMsg(ServerPlayerEntity player, String msg) {
        LegendarySpawner.server.execute(() ->
            player.sendMessage(net.minecraft.text.Text.literal(colorize(msg))));
    }

    /** Basic & color code conversion */
    private static String colorize(String s) {
        return s.replace("&0", "§0").replace("&1", "§1").replace("&2", "§2")
                .replace("&3", "§3").replace("&4", "§4").replace("&5", "§5")
                .replace("&6", "§6").replace("&7", "§7").replace("&8", "§8")
                .replace("&9", "§9").replace("&a", "§a").replace("&b", "§b")
                .replace("&c", "§c").replace("&d", "§d").replace("&e", "§e")
                .replace("&f", "§f").replace("&l", "§l").replace("&n", "§n")
                .replace("&o", "§o").replace("&r", "§r");
    }

    public record ActiveLegendary(UUID id, PokemonEntity entity, String speciesName, String world) {}
}
