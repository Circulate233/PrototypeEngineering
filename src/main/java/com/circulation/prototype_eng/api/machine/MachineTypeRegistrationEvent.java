package com.circulation.prototype_eng.api.machine;

import net.minecraftforge.fml.common.eventhandler.Event;

import java.util.Objects;

import com.circulation.prototype_eng.block.MachineControllerBlock;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.util.ResourceLocation;

/** Fired before block registration so downstream declarations can generate their controllers. */
public final class MachineTypeRegistrationEvent extends Event {
    private final MachineTypeRegistry registry;
    private final Map<ResourceLocation, Function<MachineType, ? extends MachineControllerBlock<?>>> controllerFactories = new Object2ObjectOpenHashMap<>();

    public MachineTypeRegistrationEvent(MachineTypeRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    public MachineTypeRegistry registry() { return registry; }

    /** Supply a custom physical controller while declaring its machine in this synchronous event. */
    @SuppressWarnings("unused") // Public downstream registration entry point.
    public void controllerFactory(ResourceLocation type,
                                  Function<MachineType, ? extends MachineControllerBlock<?>> factory) {
        if (controllerFactories.putIfAbsent(Objects.requireNonNull(type), Objects.requireNonNull(factory)) != null) {
            throw new IllegalArgumentException("Controller factory already registered: " + type);
        }
    }

    public Map<ResourceLocation, Function<MachineType, ? extends MachineControllerBlock<?>>> controllerFactories() {
        return Map.copyOf(controllerFactories);
    }
}
