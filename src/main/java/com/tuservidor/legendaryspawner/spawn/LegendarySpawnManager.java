package com.tuservidor.legendaryspawner.spawn;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.reflect.TypeToken;
import com.tuservidor.legendaryspawner.LegendarySpawner;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;

import java.io.File;
import java.nio.file.Files;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class LegendarySpawnManager {

    private static final Map<UUID, ActiveLegendary> activeLegendaries = new ConcurrentHashMap<>();
    private static final Set<UUID> pendingSpawn = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService scheduler;
    private static ScheduledFuture<?> spawnTask;
    private static final Gson GSON = new Gson();
    private static final String AUDIT_LOG_PATH = "logs/legendary_history.log";

    public static void start() {
        if (scheduler != null && !scheduler.isShutdown()) scheduler.shutdownNow();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "LegendarySpawner-Worker");
            t.setDaemon(true);
            return t;
        });
        scheduleNext();
    }

    public static void stop() { if (scheduler != null) scheduler.shutdownNow(); }

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

            // --- EFECTOS NATIVOS ---
            if (cfg.isEnableGlobalSound()) {
                LegendarySpawner.server.getPlayerManager().getPlayerList().forEach(p -> 
                    p.playSoundToPlayer(SoundEvents.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, 0.5f, 1.0f));
            }
            if (cfg.isEnableVisualEffects()) {
                LightningEntity lightning = EntityType.LIGHTNING_BOLT.create(world);
                if (lightning != null) {
                    lightning.refreshPositionAfterTeleport(pos);
                    lightning.setCosmetic(true); // Rayo falso, sin fuego ni daño
                    world.spawnEntity(lightning);
                }
            }
            // -----------------------

            int locX = MathHelper.floor(pos.x); int locY = MathHelper.floor(pos.y); int locZ = MathHelper.floor(pos.z);
            String pokemonName = pokemon.getSpecies().getName();

            String alert = cfg.format(cfg.getMsgSpawnAlert(), "%pokemon%", pokemonName, "%player%", target.getName().getString(),
                "%x%", String.valueOf(locX), "%y%", String.valueOf(locY), "%z%", String.valueOf(locZ));
            broadcastAll(alert);

            if (forced && source != null) sendMsg(source, cfg.format(cfg.getMsgForced(), "%pokemon%", pokemonName, "%player%", target.getName().getString()));

            // --- AUDITORÍA & DISCORD ---
            String auditMsg = "[SPAWN] " + pokemonName + " apareció en " + world.getRegistryKey().getValue() + " [" + locX + ", " + locY + ", " + locZ + "]";
            logAudit(auditMsg);
            sendDiscordWebhook("⚡ Legendario Salvaje Apareció", "**" + pokemonName + "** ha aparecido cerca de **" + target.getName().getString() + "**.\nCoordenadas: `X: " + locX + " Y: " + locY + " Z: " + locZ + "`", 16753920); // Amarillo

            int despawnMin = cfg.getDespawnAfterMinutes();
            if (despawnMin > 0) active.despawnTask = scheduler.schedule(() -> despawn(trackId), despawnMin, TimeUnit.MINUTES);
            
            saveStateAsync(); 

        } catch (Exception e) {} finally { pendingSpawn.remove(trackId); }
    }

    public static void handleCapture(ServerPlayerEntity player, Pokemon pokemon) {
        String tag = pokemon.getPersistentData().getString("legendaryspawner_id");
        if (tag.isEmpty()) return;

        try {
            UUID trackId = UUID.fromString(tag);
            ActiveLegendary active = activeLegendaries.remove(trackId);
            if (active != null) {
                if (active.despawnTask != null) active.despawnTask.cancel(false);
                
                String msg = "[CAPTURA] " + player.getName().getString() + " ha capturado a " + active.speciesName;
                logAudit(msg);
                sendDiscordWebhook("🎉 ¡Legendario Capturado!", "**" + player.getName().getString() + "** ha logrado atrapar a **" + active.speciesName + "**.", 5763719); // Verde
                
                saveStateAsync();
            }
        } catch (Exception ignored) {}
    }

    private static void despawn(UUID trackId) {
        LegendarySpawner.server.execute(() -> {
            ActiveLegendary active = activeLegendaries.get(trackId);
            if (active == null) return;

            if (killEntity(active, false)) {
                activeLegendaries.remove(trackId);
                
                logAudit("[DESPAWN] " + active.speciesName + " ha desaparecido del mundo.");
                sendDiscordWebhook("💨 Legendario Escapó", "**" + active.speciesName + "** se ha ido sin ser capturado.", 15548997); // Rojo
                
                saveStateAsync();
                broadcastAll(LegendarySpawner.config.format(LegendarySpawner.config.getMsgDespawn(), "%pokemon%", active.speciesName));
            }
        });
    }

    public static int removeAll(ServerCommandSource source) {
        cleanupDead();
        int count = 0;
        for (ActiveLegendary active : activeLegendaries.values()) if (killEntity(active, true)) count++;
        activeLegendaries.clear();
        saveStateAsync();
        if (source != null) sendMsg(source, LegendarySpawner.config.format(LegendarySpawner.config.getMsgRemoved()));
        return count;
    }

    public static int getActiveCount() { cleanupDead(); return activeLegendaries.size(); }

    public static boolean isManagedEntity(PokemonEntity entity) {
        UUID targetUuid = entity.getUuid();
        for (ActiveLegendary active : activeLegendaries.values()) if (active.entityUuid.equals(targetUuid)) return true;
        return isManagedPokemon(entity.getPokemon());
    }

    public static boolean isManagedPokemon(Pokemon pokemon) {
        String tag = pokemon.getPersistentData().getString("legendaryspawner_id");
        if (!tag.isEmpty()) {
            try { return pendingSpawn.contains(UUID.fromString(tag)) || activeLegendaries.containsKey(UUID.fromString(tag)); } 
            catch (Exception ignored) {} 
        }
        return false;
    }

    private static boolean killEntity(ActiveLegendary active, boolean force) {
        try {
            ServerWorld world = getRealWorld(active.worldId);
            if (world != null) {
                Entity realEntity = world.getEntity(active.entityUuid);
                if (realEntity != null && !realEntity.isRemoved()) {
                    var battle = Cobblemon.INSTANCE.getBattleRegistry().getBattleByParticipatingEntity(realEntity);
                    if (!force && battle != null) {
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
                } else if (realEntity == null) return true; 
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
                if (reason != Entity.RemovalReason.UNLOADED_TO_CHUNK && reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER && reason != Entity.RemovalReason.CHANGED_DIMENSION) {
                    if (active.despawnTask != null) active.despawnTask.cancel(false);
                    return true;
                }
            }
            return false; 
        });
        if (changed) saveStateAsync();
    }

    // --- MÉTODOS DE AUDITORÍA Y DISCORD ---
    public static void logAudit(String event) {
        CompletableFuture.runAsync(() -> {
            try {
                File f = new File(AUDIT_LOG_PATH);
                if (!f.getParentFile().exists()) f.getParentFile().mkdirs();
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                String line = "[" + time + "] " + event + "\n";
                Files.writeString(f.toPath(), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch(Exception e){}
        });
    }

    public static List<String> getAuditHistory() {
        try {
            File f = new File(AUDIT_LOG_PATH);
            if (!f.exists()) return List.of("&cNo hay historial registrado aún.");
            List<String> lines = Files.readAllLines(f.toPath());
            int start = Math.max(0, lines.size() - 15); // Devolvemos los ultimos 15 eventos
            return lines.subList(start, lines.size());
        } catch(Exception e){ return List.of("&cError leyendo el archivo de historial."); }
    }

    private static void sendDiscordWebhook(String title, String description, int color) {
        CompletableFuture.runAsync(() -> {
            String url = LegendarySpawner.config.getDiscordWebhookUrl();
            if (url == null || url.trim().isEmpty()) return;
            try {
                JsonObject embed = new JsonObject();
                embed.addProperty("title", title);
                embed.addProperty("description", description);
                embed.addProperty("color", color);
                
                JsonArray embedsArray = new JsonArray();
                embedsArray.add(embed);
                
                JsonObject payload = new JsonObject();
                payload.add("embeds", embedsArray);
                
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();
                client.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                LegendarySpawner.LOGGER.warn("Fallo al enviar webhook de Discord: " + e.getMessage());
            }
        });
    }
    // ---------------------------------------

    public static void saveStateAsync() {
        List<ActiveLegendary> snapshot = new ArrayList<>(activeLegendaries.values());
        CompletableFuture.runAsync(() -> {
            try {
                File file = new File(LegendarySpawner.DATA_PATH);
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                Files.writeString(file.toPath(), GSON.toJson(snapshot));
            } catch (Exception e) {}
        });
    }

    public static void saveStateSync() {
        try { Files.writeString(new File(LegendarySpawner.DATA_PATH).toPath(), GSON.toJson(new ArrayList<>(activeLegendaries.values()))); } 
        catch (Exception ignored) {}
    }

    public static void loadState() {
        try {
            File file = new File(LegendarySpawner.DATA_PATH);
            if (!file.exists()) return;
            List<ActiveLegendary> list = GSON.fromJson(Files.readString(file.toPath()), new TypeToken<List<ActiveLegendary>>(){}.getType());
            if (list != null) {
                for (ActiveLegendary a : list) {
                    int despawnMin = LegendarySpawner.config.getDespawnAfterMinutes();
                    if (despawnMin > 0) a.despawnTask = scheduler.schedule(() -> despawn(a.trackId), despawnMin, TimeUnit.MINUTES);
                    activeLegendaries.put(a.trackId, a);
                }
            }
        } catch (Exception ignored) {}
    }

    private static ServerWorld getRealWorld(String worldId) {
        try { return LegendarySpawner.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(worldId))); } 
        catch (Exception e) { return null; }
    }

    private static Species pickLegendaryForPlayer(ServerPlayerEntity player) {
        List<String> blacklist = LegendarySpawner.config.getBlacklist(); 
        Map<String, List<String>> biomeMap = LegendarySpawner.config.getBiomeLegendsMap();

        String biomeString = null;
        try { biomeString = player.getServerWorld().getBiome(player.getBlockPos()).getKey().map(k -> k.getValue().toString()).orElse(null); } catch (Exception ignored) {}

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
        double px = player.getX(); double pz = player.getZ();
        boolean isNether = world.getRegistryKey() == net.minecraft.world.World.NETHER;

        for (int attempt = 0; attempt < 10; attempt++) {
            double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
            double dist  = radius * 0.5 + ThreadLocalRandom.current().nextDouble() * radius * 0.5;
            double x = px + Math.cos(angle) * dist; double z = pz + Math.sin(angle) * dist;
            
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
        return new Vec3d(px, (fallbackY > world.getBottomY() + 5) ? fallbackY : player.getY(), pz);
    }
    
    private static int findSafeNetherY(ServerWorld world, int x, int startY, int z) {
        BlockPos.Mutable pos = new BlockPos.Mutable(x, startY, z);
        for (int dy = 0; dy < 30; dy++) {
            pos.setY(startY + dy); if (isSafe(world, pos)) return pos.getY();
            pos.setY(startY - dy); if (isSafe(world, pos)) return pos.getY();
        }
        return startY;
    }

    private static boolean isSafe(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir() && !world.getBlockState(pos.down()).isAir();
    }

    private static void broadcastAll(String rawMsg) {
        if (LegendarySpawner.server != null) LegendarySpawner.server.execute(() -> LegendarySpawner.server.getPlayerManager().broadcast(net.minecraft.text.Text.literal(colorize(rawMsg)), false));
    }

    private static void sendMsg(ServerCommandSource source, String msg) {
        if (LegendarySpawner.server != null) LegendarySpawner.server.execute(() -> source.sendMessage(net.minecraft.text.Text.literal(colorize(msg))));
    }

    private static String colorize(String s) {
        return s.replace("&0", "§0").replace("&1", "§1").replace("&a", "§a").replace("&b", "§b")
                .replace("&c", "§c").replace("&d", "§d").replace("&e", "§e").replace("&f", "§f")
                .replace("&6", "§6").replace("&7", "§7").replace("&8", "§8");
    }

    public static class ActiveLegendary {
        public UUID trackId; public UUID entityUuid; public String speciesName; public String worldId;
        public transient ScheduledFuture<?> despawnTask;
        public ActiveLegendary(UUID trackId, UUID entityUuid, String speciesName, String worldId) {
            this.trackId = trackId; this.entityUuid = entityUuid; this.speciesName = speciesName; this.worldId = worldId;
        }
    }
}