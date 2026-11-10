package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.integration.jei.DefaultRecipeDisplays;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMaps;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Objects;
import java.util.Collection;
import java.util.Map;

/**
 * Display-only extension for custom requirement types. Register before the JEI binding event closes.
 */
@SideOnly(Side.CLIENT)
public class RecipeJeiDisplays {
    public static final RecipeJeiDisplays INSTANCE = new RecipeJeiDisplays();
    private final Reference2ObjectOpenHashMap<RecipeRequirementType<?>, Provider<?>> providers = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<RecipeRequirementType<?>, Provider<?>> clientProviders = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, JeiRecipeDisplay> descriptions = new Reference2ObjectOpenHashMap<>();
    private boolean frozen;
    private boolean sealedDeclaration;
    private boolean started;
    private boolean describing;

    /** Rebuild event providers/descriptions while preserving explicit client-init declarations. */
    public void beginRegistration() {
        if (sealedDeclaration) throw new IllegalStateException("JEI declaration providers are immutable");
        if (describing) throw new IllegalStateException("JEI descriptions are currently binding");
        if (!started) {
            clientProviders.putAll(providers);
            started = true;
        }
        providers.clear();
        providers.putAll(clientProviders);
        descriptions.clear();
        frozen = false;
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
        boolean previous = describing;
        describing = true;
        try {
            return descriptions.computeIfAbsent(recipe, _ -> DefaultRecipeDisplays.describe(recipe, this));
        } finally {
            describing = previous;
        }
    }

    public RecipeJeiDisplays frozenCopy() {
        if (!frozen) throw new IllegalStateException("JEI displays are not ready");
        var copy = new RecipeJeiDisplays();
        copy.providers.putAll(providers);
        copy.frozen = true;
        copy.sealedDeclaration = true;
        return copy;
    }

    /** Exact local provider identities; behavior/revision promises remain the declaration author's contract. */
    public boolean sameProviders(RecipeJeiDisplays other) {
        Objects.requireNonNull(other);
        if (!frozen || !other.frozen) throw new IllegalStateException("JEI provider comparison requires frozen declarations");
        if (providers.size() != other.providers.size()) return false;
        for (var entry : providers.reference2ObjectEntrySet())
            if (other.providers.get(entry.getKey()) != entry.getValue()) return false;
        return true;
    }

    /** Describe the entire generation privately, without clearing current providers/descriptions. */
    public Snapshot snapshot(Collection<MachineRecipe> recipes) {
        var copy = frozenCopy();
        copy.descriptions.putAll(descriptions);
        var prepared = new Reference2ObjectOpenHashMap<MachineRecipe, JeiRecipeDisplay>();
        for (var recipe : recipes) prepared.put(recipe, copy.describe(recipe));
        return new Snapshot(Reference2ObjectMaps.unmodifiable(prepared));
    }

    public static final class Snapshot {
        private final Map<MachineRecipe, JeiRecipeDisplay> descriptions;
        private Snapshot(Map<MachineRecipe, JeiRecipeDisplay> descriptions) { this.descriptions = descriptions; }
        public JeiRecipeDisplay describe(MachineRecipe recipe) {
            return Objects.requireNonNull(descriptions.get(recipe), "Recipe is outside the prepared JEI generation");
        }
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

    @FunctionalInterface
    public interface Provider<C> {
        void describe(MachineRecipe recipe, RecipeRequirement<C> requirement, JeiRecipeDisplay.Builder display);
    }
}
