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
    public static final String DATA_PATH = "config/legendaryspawner/active_state.json";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static MinecraftServer server;
    public static SpawnerConfig config = new SpawnerConfig();

    @Override
    public void onInitialize() {
        LOGGER.info("Iniciando LegendarySpawner (Optimizado para Produccion)...");

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            SpawnerCommand.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTED.register(srv -> {
            server = srv;
            config.init();
            LegendarySpawnManager.loadState();
            registerSpawnBlocker();
            LegendarySpawnManager.start();
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(srv -> {
            LegendarySpawnManager.saveStateSync(); // Guardado de emergencia síncrono antes de morir
            LegendarySpawnManager.stop();
        });
    }

    private void registerSpawnBlocker() {
        CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.HIGHEST, evt -> {
            if (evt.getEntity() == null) return Unit.INSTANCE;
            var pokemon = evt.getEntity().getPokemon();
            if (pokemon == null || !pokemon.isWild()) return Unit.INSTANCE;

            java.util.Set<String> labels = pokemon.getSpecies().getLabels();
            boolean isLegendary = labels.contains("legendary") || labels.contains("mythical") || labels.contains("ultra-beast");
                               
            if (isLegendary && !isManaged(evt.getEntity())) {
                LOGGER.debug("Bloqueo natural ejecutado: {}", pokemon.getSpecies().showdownId());
                evt.cancel();
            }
            return Unit.INSTANCE;
        });
    }

    private boolean isManaged(com.cobblemon.mod.common.entity.pokemon.PokemonEntity entity) {
        return LegendarySpawnManager.isManagedEntity(entity);
    }
}