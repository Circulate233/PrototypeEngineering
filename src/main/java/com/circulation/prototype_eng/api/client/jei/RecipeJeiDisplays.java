package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.integration.jei.DefaultRecipeDisplays;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Objects;

/**
 * Display-only extension for custom requirement types. Register before the JEI binding event closes.
 */
@SideOnly(Side.CLIENT)
public class RecipeJeiDisplays {
    public static final RecipeJeiDisplays INSTANCE = new RecipeJeiDisplays();
    private final Reference2ObjectOpenHashMap<RecipeRequirementType<?>, Provider<?>> providers = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, JeiRecipeDisplay> descriptions = new Reference2ObjectOpenHashMap<>();
    private boolean frozen;

    @FunctionalInterface
    public interface Provider<C> {
        void describe(MachineRecipe recipe, RecipeRequirement<C> requirement, JeiRecipeDisplay.Builder display);
    }

    public <C> void register(RecipeRequirementType<C> type, Provider<C> provider) {
        if (frozen) {
            throw new IllegalStateException("JEI display registration has ended");
        }
        if (providers.putIfAbsent(Objects.requireNonNull(type), Objects.requireNonNull(provider)) != null) {
            throw new IllegalArgumentException("Duplicate JEI requirement display: " + type.id());
        }
    }

    public void freeze() {
        if (frozen) {
            throw new IllegalStateException("JEI display registry already frozen");
        }
        frozen = true;
    }

    public JeiRecipeDisplay describe(MachineRecipe recipe) {
        if (!frozen) {
            throw new IllegalStateException("JEI displays are not ready");
        }
        return descriptions.computeIfAbsent(recipe, _ -> DefaultRecipeDisplays.describe(recipe, this));
    }

    /**
     * Dispatch checks the exact typed registration identity before the sole erased generic cast.
     */
    @SuppressWarnings("unchecked")
    public <C> boolean describeCustom(MachineRecipe recipe, RecipeRequirement<C> requirement, JeiRecipeDisplay.Builder display) {
        var provider = (Provider<C>) providers.get(requirement.type());
        if (provider == null) {
            return false;
        }
        provider.describe(recipe, requirement, display);
        return true;
    }
}
