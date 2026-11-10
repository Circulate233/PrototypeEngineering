package com.circulation.prototype_eng.api.client.gui;

import ae2.client.gui.AEBaseGui;
import ae2.container.AEBaseContainer;
import com.circulation.prototype_eng.client.gui.MachineGui;
import com.circulation.prototype_eng.container.MachineContainer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Client-only Java screen bindings; custom screens directly extend AEBaseGui.
 */
@SideOnly(Side.CLIENT)
public final class MachineScreenRegistry {
    private static final Object2ObjectOpenHashMap<ResourceLocation, BiFunction<AEBaseContainer, InventoryPlayer, AEBaseGui<?>>> FACTORIES = new Object2ObjectOpenHashMap<>();

    private MachineScreenRegistry() {
    }

    @SuppressWarnings("unused") // Called only from a downstream mod's client initialization.
    public static <C extends AEBaseContainer> void register(ResourceLocation type, Class<C> containerClass,
                                                            BiFunction<C, InventoryPlayer, ? extends AEBaseGui<C>> factory) {
        Objects.requireNonNull(containerClass);
        Objects.requireNonNull(factory);
        if (FACTORIES.putIfAbsent(Objects.requireNonNull(type), (container, player) -> factory.apply(containerClass.cast(container), player)) != null) {
            throw new IllegalArgumentException("Machine screen already registered: " + type);
        }
    }

    public static AEBaseGui<?> create(ResourceLocation type, AEBaseContainer container, InventoryPlayer player) {
        var factory = FACTORIES.get(type);
        if (factory != null) {
            return Objects.requireNonNull(factory.apply(container, player), "Machine screen factory returned null");
        }
        if (container instanceof MachineContainer machine) {
            return new MachineGui(machine, player);
        }
        throw new IllegalStateException("Custom machine container has no client screen binding: " + type);
    }
}
