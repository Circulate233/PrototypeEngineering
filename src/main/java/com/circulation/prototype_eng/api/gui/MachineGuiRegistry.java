package com.circulation.prototype_eng.api.gui;

import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.container.ContainerComputer;
import com.circulation.prototype_eng.container.ContainerMachine;
import com.circulation.prototype_eng.container.ContainerNetworkCenter;
import com.circulation.prototype_eng.container.ContainerResearchStation;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import com.circulation.prototype_eng.api.network.NetworkCenter;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Common-side Java container bindings. Register during mod initialization, on both logical sides.
 * Custom containers call super(player, tile); bind their matching screen on the client only.
 */
public final class MachineGuiRegistry {
    private static final Object2ObjectOpenHashMap<ResourceLocation, BiFunction<InventoryPlayer, MachineControllerHost, ? extends AEBaseContainer>> FACTORIES = new Object2ObjectOpenHashMap<>();

    private MachineGuiRegistry() {
    }

    @SuppressWarnings("unused") // Downstream mods register their own containers before opening any GUI.
    public static void register(ResourceLocation type, BiFunction<InventoryPlayer, MachineControllerHost, ? extends AEBaseContainer> factory) {
        if (FACTORIES.putIfAbsent(Objects.requireNonNull(type), Objects.requireNonNull(factory)) != null) {
            throw new IllegalArgumentException("Machine container already registered: " + type);
        }
    }

    public static AEBaseContainer create(InventoryPlayer player, MachineControllerHost tile) {
        var factory = FACTORIES.get(tile.type().id());
        return factory == null ? (tile.type().componentTypes().contains(NetworkCenter.DEVICE)
            ? new ContainerNetworkCenter(player, tile) : tile.type().componentTypes().contains(ComputerController.DEVICE)
            ? new ContainerComputer(player, tile) : tile.type().componentTypes().contains(ResearchStation.DEVICE)
            ? new ContainerResearchStation(player, tile) : new ContainerMachine(player, tile))
            : factory.apply(player, tile);
    }
}
