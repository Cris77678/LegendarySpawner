package com.tuservidor.legendaryspawner.spawn;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.tuservidor.legendaryspawner.LegendarySpawner;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.*;

public class LegendarySpawnManager {

    private static final Map<UUID, ActiveLegendary> activeLegendaries = new ConcurrentHashMap<>();
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

    public static boolean attemptSpawn(boolean forced, ServerPlayerEntity adminSource, String forcedSpecies) {
        var server = LegendarySpawner.server;
        if (server == null) return false;

        var cfg = LegendarySpawner.config;
        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());

        cleanupDead();
        
        // Fix para consolas sin jugadores online
        if (players.isEmpty()) {
            if (adminSource != null) sendMsg(adminSource, cfg.format(cfg.getPrefix() + "&cNo hay jugadores online."));
            LegendarySpawner.LOGGER.info("Spawn skipped: 0 players online.");
            return false;
        }

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

        ServerPlayerEntity target = players.get(new Random().nextInt(players.size()));

        Species speciesHint = null;
        if (forcedSpecies != null) {
            speciesHint = PokemonSpecies.INSTANCE.getByIdentifier(
                net.minecraft.util.Identifier.of("cobblemon", forcedSpecies));
            if (speciesHint == null) {
                LegendarySpawner.LOGGER.warn("Unknown species: {}", forcedSpecies);
                return false;
            }
        }

        final Species finalSpecies = speciesHint;
        final ServerPlayerEntity finalTarget = target;
        final ServerPlayerEntity finalAdmin = adminSource;

        server.execute(() -> spawnOnServerThread(cfg, finalSpecies, finalTarget, finalAdmin, forced));
        return true;
    }

    private static void spawnOnServerThread(
            com.tuservidor.legendaryspawner.config.SpawnerConfig cfg,
            Species speciesHint, ServerPlayerEntity target,
            ServerPlayerEntity adminSource, boolean forced) {
        try {
            Species species = speciesHint != null ? speciesHint : pickLegendaryForPlayer(target);
            if (species == null) {
                LegendarySpawner.LOGGER.warn("No legendary species found to spawn.");
                return;
            }
            ServerWorld world = (ServerWorld) target.getWorld();
            Vec3d pos = findSpawnPos(target, world, cfg.getSpawnRadiusBlocks());

            // Nivel Aleatorio (50 a 70) para evitar que aparezcan al Nivel 1.
            int randomLevel = 50 + new Random().nextInt(21);
            String buildProps = species.showdownId() + " level=" + randomLevel;
            Pokemon pokemon = PokemonProperties.Companion.parse(buildProps).create();
            
            UUID trackId = UUID.randomUUID();
            pokemon.getPersistentData().putString("legendaryspawner_id", trackId.toString());
            pendingSpawn.add(trackId);

            PokemonEntity entity;
            try {
                entity = new PokemonEntity(world, pokemon, com.cobblemon.mod.common.CobblemonEntities.POKEMON);
                entity.setPos(pos.x, pos.y, pos.z);
                world.spawnEntityAndPassengers(entity);
            } finally {
                pendingSpawn.remove(trackId);
            }

            ActiveLegendary active = new ActiveLegendary(trackId, entity, species.getName(),
                world.getRegistryKey().getValue().toString());
            activeLegendaries.put(trackId, active);

            LegendarySpawner.LOGGER.info("Spawned legendary {} at level {} (trackId: {})", species.getName(), randomLevel, trackId);

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

    public static boolean isManagedEntity(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        for (ActiveLegendary active : activeLegendaries.values()) {
            if (active.entity() == entity) return true;
        }
        String tag = entity.getPokemon().getPersistentData().getString("legendaryspawner_id");
        if (!tag.isEmpty()) {
            try {
                return pendingSpawn.contains(UUID.fromString(tag));
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static void despawn(UUID trackId) {
        ActiveLegendary active = activeLegendaries.remove(trackId);
        if (active == null) return;
        if (active.entity().isRemoved()) return; 

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

    private static Species pickLegendaryForPlayer(ServerPlayerEntity player) {
        List<String> blacklist = LegendarySpawner.config.getBlacklist();
        Map<String, List<String>> biomeMap = LegendarySpawner.config.getBiomeLegendsMap();

        var biomeKey = player.getServerWorld().getBiome(player.getBlockPos()).getKey().orElse(null);
        String biomeString = biomeKey != null ? biomeKey.getValue().toString() : null;

        List<String> candidates = new ArrayList<>();
        if (biomeString != null && biomeMap.containsKey(biomeString)) {
            candidates.addAll(biomeMap.get(biomeString));
        }

        List<String> global = biomeMap.getOrDefault("*", List.of());
        for (String s : global) {
            if (!candidates.contains(s)) candidates.add(s);
        }

        candidates.removeAll(blacklist);

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
            return pool.get(new Random().nextInt(pool.size()));
        }

        List<Species> allLegendaries = new ArrayList<>();
        for (Species species : com.cobblemon.mod.common.api.pokemon.PokemonSpecies.INSTANCE.getImplemented()) {
            if (blacklist.contains(species.showdownId())) continue;
            if (species.getLabels().contains("legendary")) allLegendaries.add(species);
        }
        if (allLegendaries.isEmpty()) return null;
        return allLegendaries.get(new Random().nextInt(allLegendaries.size()));
    }

    private static Vec3d findSpawnPos(ServerPlayerEntity player, ServerWorld world, int radius) {
        Random rand = new Random();
        double px = player.getX();
        double pz = player.getZ();
        // Verificar si estamos en el Nether para evitar la bedrock superior
        boolean isNether = world.getRegistryKey() == net.minecraft.world.World.NETHER;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = rand.nextDouble() * Math.PI * 2;
            double dist  = radius * 0.5 + rand.nextDouble() * radius * 0.5;
            double x = px + Math.cos(angle) * dist;
            double z = pz + Math.sin(angle) * dist;
            
            int y;
            if (isNether) {
                y = findSafeNetherY(world, (int) x, (int) player.getY(), (int) z);
            } else {
                y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);
            }

            // Validar que no se generen en el techo o vacio
            if (y > world.getBottomY() && y < world.getTopY() - 2) {
                return new Vec3d(x, y, z);
            }
        }
        return player.getPos().add(5, 0, 0);
    }
    
    // Función auxiliar para escanear huecos dentro del Nether
    private static int findSafeNetherY(ServerWorld world, int x, int startY, int z) {
        BlockPos.Mutable pos = new BlockPos.Mutable(x, startY, z);
        for (int dy = 0; dy < 30; dy++) {
            pos.setY(startY + dy);
            if (isSafe(world, pos)) return pos.getY();
            pos.setY(startY - dy);
            if (isSafe(world, pos)) return pos.getY();
        }
        return startY;
    }

    private static boolean isSafe(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir() 
            && world.getBlockState(pos.up()).isAir() 
            && !world.getBlockState(pos.down()).isAir();
    }

    private static void broadcastAll(String rawMsg) {
        var server = LegendarySpawner.server;
        if (server == null) return;
        server.execute(() ->
            server.getPlayerManager().broadcast(
                net.minecraft.text.Text.literal(colorize(rawMsg)), false));
    }

    private static void sendMsg(ServerPlayerEntity player, String msg) {
        LegendarySpawner.server.execute(() ->
            player.sendMessage(net.minecraft.text.Text.literal(colorize(msg))));
    }

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