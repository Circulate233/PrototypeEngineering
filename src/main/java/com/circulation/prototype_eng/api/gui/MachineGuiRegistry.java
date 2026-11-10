package com.circulation.prototype_eng.api.gui;

import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.container.MachineContainer;
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
        var container = factory == null ? new MachineContainer(player, tile)
            : Objects.requireNonNull(factory.apply(player, tile), "Machine container factory returned null");
        if (container.getTarget() != tile.blockEntity()) {
            throw new IllegalArgumentException("Machine container must target its controller tile");
        }
        return container;
    }
}
