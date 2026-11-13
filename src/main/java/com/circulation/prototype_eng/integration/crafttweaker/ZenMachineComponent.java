package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Java addons expose a real descriptor and its explicit persistence/recreation policy as one handle.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.MachineComponent")
@SuppressWarnings("unused") // Public addon factories return this script handle.
public final class ZenMachineComponent {
    private final ComponentType<MachineInstance, ?> type;
    private final Consumer<MachineAssembly.Builder> binding;

    private ZenMachineComponent(ComponentType<MachineInstance, ?> type, Consumer<MachineAssembly.Builder> binding) {
        this.type = Objects.requireNonNull(type);
        this.binding = binding;
    }

    public static <C> ZenMachineComponent persistent(ComponentType<MachineInstance, C> type, MachineComponentCodec<C> codec) {
        Objects.requireNonNull(codec);
        return new ZenMachineComponent(type, assembly -> assembly.component(type, codec));
    }

    public static ZenMachineComponent recreated(ComponentType<MachineInstance, ?> type) {
        return new ZenMachineComponent(type, assembly -> assembly.recreated(type));
    }

    void declare(MachineType.Builder machine) {
        machine.component(type);
    }

    void bind(MachineAssembly.Builder assembly) {
        binding.accept(assembly);
    }
}
