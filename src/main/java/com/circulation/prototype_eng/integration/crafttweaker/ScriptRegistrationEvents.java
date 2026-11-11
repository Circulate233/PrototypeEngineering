package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** No optional types are linked here; Forge can load this subscriber without CraftTweaker installed. */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class ScriptRegistrationEvents {
    private ScriptRegistrationEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void machines(MachineTypeRegistrationEvent event) {
        DefinitionRegistrations.dispatch(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void recipes(RecipeRegistrationEvent event) {
        DefinitionRegistrations.dispatch(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void runtime(MachineRuntimeRegistrationEvent event) {
        DefinitionRegistrations.dispatch(event);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void declarations(RecipeDeclarationRegistrationEvent event) {
        DefinitionRegistrations.dispatch(event);
    }
}
