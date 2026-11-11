package com.circulation.prototype_eng.integration.crafttweaker.render;

import com.circulation.prototype_eng.api.client.render.ClientRenderDeclarations;
import crafttweaker.annotations.ZenRegister;
import net.minecraft.util.ResourceLocation;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Declarative resources only, safe during ordinary scripts on a dedicated server. */
@ZenRegister
@ZenClass("mods.prototype_eng.render.RenderBindings")
@SuppressWarnings({"unused", "resource"}) // ZenScript resolves methods; declarations retain optional handles' bindings for the client lifetime.
public final class ZenRenderBindings {
    private ZenRenderBindings() {
    }

    @ZenMethod
    public static ZenGeckoBinding gecko() {
        return new ZenGeckoBinding();
    }

    @ZenMethod
    public static void bindGeckoToMachineType(String machine, ZenGeckoBinding binding) {
        var id = new ResourceLocation(machine);
        var model = binding.model();
        ClientRenderDeclarations.register(event -> event.bindings().bindMachine(id, model));
    }

    @ZenMethod
    public static void bindGeckoToComponentType(String component, ZenGeckoBinding binding) {
        var id = new ResourceLocation(component);
        var model = binding.model();
        ClientRenderDeclarations.register(event -> event.bindings().bindComponent(id, model));
    }

    @ZenMethod
    public static void bindGeckoToStructure(String machine, String structure, ZenGeckoBinding binding) {
        var machineId = new ResourceLocation(machine);
        var structureId = new ResourceLocation(structure);
        var model = binding.structure();
        ClientRenderDeclarations.register(event -> event.bindings().bindStructure(machineId, structureId, model));
    }
}
