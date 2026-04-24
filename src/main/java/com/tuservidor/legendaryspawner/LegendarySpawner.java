package com.tuservidor.legendaryspawner;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.tuservidor.legendaryspawner.commands.SpawnerCommand;
import com.tuservidor.legendaryspawner.config.SpawnerConfig;
import com.tuservidor.legendaryspawner.spawn.LegendarySpawnManager;
import kotlin.Unit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegendarySpawner implements ModInitializer {

    public static final String MOD_ID = "legendaryspawner";
    public static final String CONFIG_PATH = "config/legendaryspawner/config.json";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static MinecraftServer server;
    public static SpawnerConfig config = new SpawnerConfig();

    @Override
    public void onInitialize() {
        LOGGER.info("LegendarySpawner loading...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            SpawnerCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            config.init();
            registerSpawnBlocker();
            LegendarySpawnManager.start();
            LOGGER.info("LegendarySpawner ready! Interval: {} min, Chance: {}%, Min players: {}",
                config.getSpawnIntervalMinutes(),
                config.getSpawnChancePercent(),
                config.getMinPlayersRequired());
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(srv ->
            LegendarySpawnManager.stop());
    }

    private void registerSpawnBlocker() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.HIGHEST, evt -> {
            var pokemon = evt.getEntity().getPokemon();
            if (pokemon == null) return Unit.INSTANCE;

            if (!pokemon.isWild()) return Unit.INSTANCE;

            java.util.Set<String> labels = pokemon.getSpecies().getLabels();
            // Corrección: Bloquear legendarios, míticos y ultraentes naturales
            boolean isLegendary = labels.contains("legendary") 
                               || labels.contains("mythical") 
                               || labels.contains("ultra-beast");
                               
            if (isLegendary && !isManaged(evt.getEntity())) {
                LOGGER.debug("Blocked natural special spawn: {}", pokemon.getSpecies().showdownId());
                evt.cancel();
            }
            return Unit.INSTANCE;
        });
        LOGGER.info("Legendary spawn blocker registered via event.");
    }

    private boolean isManaged(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return LegendarySpawnManager.isManagedEntity(entity);
    }
}