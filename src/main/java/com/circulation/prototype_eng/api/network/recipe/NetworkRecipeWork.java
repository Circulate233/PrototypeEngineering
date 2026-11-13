package com.circulation.prototype_eng.api.network.recipe;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.MachineRecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.recipe.requirement.NetworkRecipeWorkSystem;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

/**
 * Machine-wide PN defaults and per-recipe extensions, executed by the ordinary requirement pipeline.
 */
@SuppressWarnings("unused") // Machine declarations install this recreated component through the public Java API.
public final class NetworkRecipeWork implements MachineRecipeRequirements {
    private static final RecipeRequirementType<Binding> TYPE = new RecipeRequirementType<>(
        new ResourceLocation(Tags.MOD_ID, "network_work"), new NetworkRecipeWorkSystem());
    private final ResourceLocation id;
    private final ComponentType<MachineInstance, MachineNetworkDevice> device;
    private final NetworkWorkRequirements defaults;
    private final NetworkRecipeExtensions extensions;

    private NetworkRecipeWork(ResourceLocation id, ComponentType<MachineInstance, MachineNetworkDevice> device,
                              NetworkWorkRequirements defaults, NetworkRecipeExtensions extensions) {
        this.id = id;
        this.device = device;
        this.defaults = defaults;
        this.extensions = extensions;
    }

    public static ComponentType<MachineInstance, NetworkRecipeWork> component(ResourceLocation id,
                                                                              ComponentType<MachineInstance, MachineNetworkDevice> device, NetworkWorkRequirements defaults) {
        return component(id, device, defaults, NetworkRecipeExtensions.INSTANCE, ResearchRegistry.INSTANCE);
    }

    public static ComponentType<MachineInstance, NetworkRecipeWork> component(ResourceLocation id,
                                                                              ComponentType<MachineInstance, MachineNetworkDevice> device, NetworkWorkRequirements defaults,
                                                                              NetworkRecipeExtensions extensions, ResearchRegistry research) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(device);
        Objects.requireNonNull(defaults);
        Objects.requireNonNull(extensions);
        Objects.requireNonNull(research);
        return new ComponentType<>(id, _ -> {
            defaults.validate(research);
            if (!extensions.published()) {
                throw new IllegalStateException("Network recipe requirements have not been published");
            }
            return new NetworkRecipeWork(id, device, defaults, extensions);
        }, List.of(), List.of(device));
    }

    @Override
    public List<RecipeRequirement<?>> requirementsFor(MachineRecipe recipe) {
        var combined = NetworkWorkRequirements.combined(defaults, extensions.require(recipe));
        return List.of(TYPE.requirement(id, new Binding(id, device, combined)));
    }

    /**
     * Immutable declaration only; work permits, sequences and receipts are acquired from the live owner.
     */
    public record Binding(ResourceLocation sourceId, ComponentType<MachineInstance, MachineNetworkDevice> device,
                          NetworkWorkRequirements requirements) {
        public Binding {
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(device);
            Objects.requireNonNull(requirements);
        }
    }
}
