package com.tuservidor.legendaryspawner.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tuservidor.legendaryspawner.LegendarySpawner;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.entity.Entity;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public class LegendarySpawnManager {

    private static final Map<UUID, ActiveLegendary> activeLegendaries = new ConcurrentHashMap<>();
    private static final Set<UUID> pendingSpawn = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> spawnTask;
    private static final Gson GSON = new Gson();

    public static void start() {
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "LegendarySpawner-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
    }

    public static void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    public static void reschedule() {
        if (spawnTask != null) spawnTask.cancel(false);
        scheduleNext();
    }

    private static void scheduleNext() {
        int interval = Math.max(1, LegendarySpawner.config.getSpawnIntervalMinutes());
        spawnTask = scheduler.scheduleAtFixedRate(() -> {
            try { attemptSpawn(false, null, null); } catch (Exception e) {}
        }, interval, interval, TimeUnit.MINUTES);
    }

    public static boolean attemptSpawn(boolean forced, ServerCommandSource source, String rawProperties) {
        var server = LegendarySpawner.server;
        if (server == null) return false;

        var cfg = LegendarySpawner.config;
        List<ServerPlayerEntity> players = new ArrayList<>(server.getPlayerManager().getPlayerList());
        cleanupDead();
        
        if (players.isEmpty()) {
            if (source != null) sendMsg(source, cfg.getPrefix() + "&cNo hay jugadores online.");
            return false;
        }
        if (activeLegendaries.size() >= cfg.getMaxActiveLegendaries()) {
            if (source != null) sendMsg(source, cfg.format(cfg.getMsgAlreadyActive()));
            return false;
        }
        if (!forced && players.size() < cfg.getMinPlayersRequired()) return false;
        if (!forced && ThreadLocalRandom.current().nextInt(100) >= cfg.getSpawnChancePercent()) return false;

        ServerPlayerEntity adminPlayer = (source != null && source.getEntity() instanceof ServerPlayerEntity p) ? p : null;
        ServerPlayerEntity target = (forced && adminPlayer != null) ? adminPlayer : players.get(ThreadLocalRandom.current().nextInt(players.size()));

        scheduler.execute(() -> {
            try {
                Pokemon pokemon = null;
                if (rawProperties != null) {
                    pokemon = PokemonProperties.Companion.parse(rawProperties.toLowerCase(Locale.ROOT)).create();
                } else {
                    Species species = pickLegendaryForPlayer(target);
                    if (species != null) {
                        int randomLevel = 50 + ThreadLocalRandom.current().nextInt(21);
                        pokemon = PokemonProperties.Companion.parse(species.showdownId() + " level=" + randomLevel).create();
                    }
                }
                
                if (pokemon == null) return;
                
                final Pokemon finalPokemon = pokemon;
                server.execute(() -> spawnOnServerThread(cfg, finalPokemon, target, source, forced));
            } catch (Exception e) {
                if (source != null) sendMsg(source, cfg.getPrefix() + "&cPropiedad o especie invalida.");
            }
        });
        return true;
    }

    private static void spawnOnServerThread(com.tuservidor.legendaryspawner.config.SpawnerConfig cfg,
            Pokemon pokemon, ServerPlayerEntity target, ServerCommandSource source, boolean forced) {
        
        UUID trackId = UUID.randomUUID();
        try {
            ServerWorld world = (ServerWorld) target.getWorld();
            Vec3d pos = findSpawnPos(target, world, cfg.getSpawnRadiusBlocks());

            pokemon.getPersistentData().putString("legendaryspawner_id", trackId.toString());
            pendingSpawn.add(trackId);

            PokemonEntity entity = com.cobblemon.mod.common.CobblemonEntities.POKEMON.create(world);
            if (entity == null) throw new IllegalStateException("Error interno al instanciar PokemonEntity");
            
            entity.setPokemon(pokemon);
            entity.setPos(pos.x, pos.y, pos.z);
            entity.setPortalCooldown(1000000); 
            entity.fallDistance = 0;

            world.spawnEntityAndPassengers(entity);

            ActiveLegendary active = new ActiveLegendary(trackId, entity.getUuid(), pokemon.getSpecies().getName(), world.getRegistryKey().getValue().toString());
            activeLegendaries.put(trackId, active);

            String alert = cfg.format(cfg.getMsgSpawnAlert(), "%pokemon%", pokemon.getSpecies().getName(), "%player%", target.getName().getString(),
                "%x%", String.valueOf(MathHelper.floor(pos.x)), "%y%", String.valueOf(MathHelper.floor(pos.y)), "%z%", String.valueOf(MathHelper.floor(pos.z)));
            broadcastAll(alert);

            if (forced && source != null) {
                sendMsg(source, cfg.format(cfg.getMsgForced(), "%pokemon%", pokemon.getSpecies().getName(), "%player%", target.getName().getString()));
            }

            int despawnMin = cfg.getDespawnAfterMinutes();
            if (despawnMin > 0) active.despawnTask = scheduler.schedule(() -> despawn(trackId), despawnMin, TimeUnit.MINUTES);
            
            saveStateAsync(); // Llamada I/O no bloqueante

        } catch (Exception e) {
            LegendarySpawner.LOGGER.error("Fallo critico durante el spawn", e);
        } finally {
            // Protección contra Memory Leak en caso de Fallo Crítico
            pendingSpawn.remove(trackId);
        }
    }

    public static int removeAll(ServerCommandSource source) {
        cleanupDead();
        int count = 0;
        for (ActiveLegendary active : activeLegendaries.values()) {
            if (killEntity(active, true)) count++;
        }
        activeLegendaries.clear();
        saveStateAsync();
        if (source != null) sendMsg(source, LegendarySpawner.config.format(LegendarySpawner.config.getMsgRemoved()));
        return count;
    }

    public static int getActiveCount() {
        cleanupDead();
        return activeLegendaries.size();
    }

    public static boolean isManagedEntity(PokemonEntity entity) {
        UUID targetUuid = entity.getUuid();
        for (ActiveLegendary active : activeLegendaries.values()) {
            if (active.entityUuid.equals(targetUuid)) return true;
        }
        String tag = entity.getPokemon().getPersistentData().getString("legendaryspawner_id");
        if (!tag.isEmpty()) {
            try { return pendingSpawn.contains(UUID.fromString(tag)) || activeLegendaries.containsKey(UUID.fromString(tag)); } 
            catch (Exception ignored) {} // Proteccion contra NBT corrupta
        }
        return false;
    }

    private static void despawn(UUID trackId) {
        LegendarySpawner.server.execute(() -> {
            ActiveLegendary active = activeLegendaries.get(trackId);
            if (active == null) return;

            if (killEntity(active, false)) {
                activeLegendaries.remove(trackId);
                saveStateAsync();
                broadcastAll(LegendarySpawner.config.format(LegendarySpawner.config.getMsgDespawn(), "%pokemon%", active.speciesName));
            }
        });
    }

    private static boolean killEntity(ActiveLegendary active, boolean force) {
        try {
            ServerWorld world = getRealWorld(active.worldId);
            if (world != null) {
                Entity realEntity = world.getEntity(active.entityUuid);
                if (realEntity != null && !realEntity.isRemoved()) {
                    
                    var battle = Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingEntity(realEntity);
                    if (!force && battle != null) {
                        // Anti Alt-F4 Exploit: Verificamos si los jugadores siguen online y en la batalla
                        boolean hasRealPlayers = battle.getPlayers().stream().anyMatch(p -> !p.isDisconnected());
                        if (hasRealPlayers) {
                            if (active.despawnTask != null) active.despawnTask.cancel(false);
                            active.despawnTask = scheduler.schedule(() -> despawn(active.trackId), 1, TimeUnit.MINUTES);
                            return false; 
                        }
                    }

                    if (active.despawnTask != null) active.despawnTask.cancel(false);
                    realEntity.discard();
                    return true;
                } else if (realEntity == null) {
                    return true; // Chunk descargado, asumimos purga.
                }
            }
        } catch (Exception ignored) {}
        return true;
    }

    private static void cleanupDead() {
        boolean changed = activeLegendaries.entrySet().removeIf(e -> {
            ActiveLegendary active = e.getValue();
            ServerWorld world = getRealWorld(active.worldId);
            if (world == null) return false; 
            
            Entity realEntity = world.getEntity(active.entityUuid);
            if (realEntity != null && realEntity.isRemoved()) {
                var reason = realEntity.getRemovalReason();
                // Ignorar estados fantasmas temporales al cruzar portales o montar vehículos
                if (reason != Entity.RemovalReason.UNLOADED_TO_CHUNK && reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
                    if (active.despawnTask != null) active.despawnTask.cancel(false);
                    return true;
                }
            }
            return false; 
        });
        if (changed) saveStateAsync();
    }

    // --- Persistencia Asíncrona sin Bloquear Main Thread ---
    public static void saveStateAsync() {
        // Hacemos un snapshot concurrente rápido de los valores para evitar ConcurrentModificationException
        List<ActiveLegendary> snapshot = new ArrayList<>(activeLegendaries.values());
        CompletableFuture.runAsync(() -> {
            try {
                File file = new File(LegendarySpawner.DATA_PATH);
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                String json = GSON.toJson(snapshot);
                Files.writeString(file.toPath(), json);
            } catch (Exception e) {
                LegendarySpawner.LOGGER.error("Fallo al guardar estado asincrono", e);
            }
        });
    }

    public static void saveStateSync() {
        try {
            File file = new File(LegendarySpawner.DATA_PATH);
            Files.writeString(file.toPath(), GSON.toJson(new ArrayList<>(activeLegendaries.values())));
        } catch (Exception ignored) {}
    }

    public static void loadState() {
        try {
            File file = new File(LegendarySpawner.DATA_PATH);
            if (!file.exists()) return;
            String json = Files.readString(file.toPath());
            List<ActiveLegendary> list = GSON.fromJson(json, new TypeToken<List<ActiveLegendary>>(){}.getType());
            if (list != null) {
                for (ActiveLegendary a : list) {
                    int despawnMin = LegendarySpawner.config.getDespawnAfterMinutes();
                    if (despawnMin > 0) a.despawnTask = scheduler.schedule(() -> despawn(a.trackId), despawnMin, TimeUnit.MINUTES);
                    activeLegendaries.put(a.trackId, a);
                }
            }
        } catch (Exception ignored) {}
    }
    // ---------------------------------------------

    private static ServerWorld getRealWorld(String worldId) {
        try { return LegendarySpawner.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId))); } 
        catch (Exception e) { return null; }
    }

    private static Species pickLegendaryForPlayer(ServerPlayerEntity player) {
        List<String> blacklist = LegendarySpawner.config.getBlacklist(); 
        Map<String, List<String>> biomeMap = LegendarySpawner.config.getBiomeLegendsMap();

        String biomeString = null;
        try { // Protección NPE de biomas Custom 
            biomeString = player.getServerWorld().getBiome(player.getBlockPos()).getKey().map(k -> k.getValue().toString()).orElse(null);
        } catch (Exception ignored) {}

        List<String> candidates = new ArrayList<>();
        if (biomeString != null && biomeMap.containsKey(biomeString)) candidates.addAll(biomeMap.get(biomeString));
        
        for (String s : biomeMap.getOrDefault("*", List.of())) if (!candidates.contains(s)) candidates.add(s);
        candidates.replaceAll(String::toLowerCase);
        candidates.removeAll(blacklist);

        if (candidates.isEmpty()) return null; 

        List<Species> pool = new ArrayList<>();
        for (String id : candidates) {
            Species s = PokemonSpecies.INSTANCE.getByIdentifier(net.minecraft.util.Identifier.of(
                    id.contains(":") ? id.split(":")[0] : "cobblemon", id.contains(":") ? id.split(":")[1] : id));
            if (s != null) pool.add(s);
        }

        if (!pool.isEmpty()) return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        return null;
    }

    private static Vec3d findSpawnPos(ServerPlayerEntity player, ServerWorld world, int radius) {
        double px = player.getX();
        double pz = player.getZ();
        boolean isNether = world.getRegistryKey() == net.minecraft.world.World.NETHER;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double dist  = radius * 0.5 + ThreadLocalRandom.current().nextDouble() * radius * 0.5;
            double x = px + Math.cos(angle) * dist;
            double z = pz + Math.sin(angle) * dist;
            
            // Fix: Calculo bitwise seguro para chunks en cuadrantes negativos
            if (!world.getChunkManager().isChunkLoaded(MathHelper.floor(x) >> 4, MathHelper.floor(z) >> 4)) continue;

            int y = isNether ? findSafeNetherY(world, (int) x, (int) player.getY(), (int) z) 
                             : world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int) x, (int) z);

            if (y > world.getBottomY() && y < world.getTopY() - 2) {
                BlockState floor = world.getBlockState(new BlockPos((int)x, y - 1, (int)z));
                if (floor.isAir() || floor.getFluidState().isStill() || floor.getBlock() == net.minecraft.block.Blocks.LAVA) continue;
                return new Vec3d(x, y, z);
            }
        }
        
        int fallbackY = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, (int)px, (int)pz);
        // Fix de seguridad en el End: Si fallbackY indica 0 (el vacío), forzamos la altura del jugador.
        int safeY = (fallbackY > world.getBottomY() + 5) ? fallbackY : (int)player.getY();
        return new Vec3d(px, safeY, pz);
    }
    
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
        return world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir() && !world.getBlockState(pos.down()).isAir();
    }

    private static void broadcastAll(String rawMsg) {
        var server = LegendarySpawner.server;
        if (server == null) return;
        server.execute(() -> server.getPlayerManager().broadcast(net.minecraft.text.Text.literal(colorize(rawMsg)), false));
    }

    private static void sendMsg(ServerCommandSource source, String msg) {
        LegendarySpawner.server.execute(() -> source.sendMessage(net.minecraft.text.Text.literal(colorize(msg))));
    }

    private static String colorize(String s) {
        return s.replace("&0", "§0").replace("&1", "§1").replace("&a", "§a").replace("&b", "§b")
                .replace("&c", "§c").replace("&d", "§d").replace("&e", "§e").replace("&f", "§f")
                .replace("&6", "§6").replace("&7", "§7").replace("&8", "§8");
    }

    public static class ActiveLegendary {
        public UUID trackId;
        public UUID entityUuid;
        public String speciesName;
        public String worldId;
        public transient ScheduledFuture<?> despawnTask;

        public ActiveLegendary(UUID trackId, UUID entityUuid, String speciesName, String worldId) {
            this.trackId = trackId; this.entityUuid = entityUuid; this.speciesName = speciesName; this.worldId = worldId;
        }
    }
}