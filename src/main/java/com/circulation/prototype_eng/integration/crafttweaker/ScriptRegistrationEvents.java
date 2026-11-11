package com.circulation.prototype_eng.integration.crafttweaker;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.RecipeRegistrationEvent;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistrationEvent;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistrationEvent;
import com.circulation.prototype_eng.api.research.ResearchRegistrationEvent;
import com.circulation.prototype_eng.api.research.ResearchServerConfigurationEvent;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistrationEvent;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistrationEvent;
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void resources(NetworkResourceRegistrationEvent event) { DefinitionRegistrations.dispatch(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void computerComponents(NetworkComputerComponentRegistrationEvent event) { DefinitionRegistrations.dispatch(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void research(ResearchRegistrationEvent event) { DefinitionRegistrations.dispatch(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void downloads(ResearchItemDownloadRegistrationEvent event) { DefinitionRegistrations.dispatch(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void researchServer(ResearchServerConfigurationEvent event) { DefinitionRegistrations.dispatch(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void structurePredicates(StructurePredicateRegistrationEvent event) { DefinitionRegistrations.dispatch(event); }
}
