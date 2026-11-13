package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.Objects;
import java.util.function.Function;

/**
 * Captures an existing Java runtime factory; assembly invokes it for both creation and restore.
 */
@ZenRegister
@ZenClass("mods.prototype_eng.MachineFactory")
@SuppressWarnings("unused") // ZenScript/addons supply these handles.
public record ZenMachineFactory(Function<MachineType, ? extends MachineInstance> value) {
    public ZenMachineFactory {
        Objects.requireNonNull(value);
    }

    @ZenMethod
    public static ZenMachineFactory multithreaded() {
        return new ZenMachineFactory(MultithreadController::new);
    }

    @ZenMethod
    public static ZenMachineFactory crossRecipe() {
        return new ZenMachineFactory(CrossRecipeController::new);
    }
}
