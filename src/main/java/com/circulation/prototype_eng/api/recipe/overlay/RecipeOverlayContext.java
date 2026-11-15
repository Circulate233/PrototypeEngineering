package com.circulation.prototype_eng.api.recipe.overlay;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import net.minecraft.util.ResourceLocation;

import java.util.List;

/**
 * One modifier application. Tracked writes are valid only during apply; direct process/machine
 * mutations do not acquire compensation rights. Each descriptor occurrence owns separate layer IDs.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class RecipeOverlayContext {
    private final RecipeProcess process;
    private final ResourceLocation modifierId;
    private final int ordinal;
    private final List<RecipeOverlays.Change> changes;
    private boolean active = true;

    RecipeOverlayContext(RecipeProcess process, ResourceLocation modifierId, int ordinal, List<RecipeOverlays.Change> changes) {
        this.process = process;
        this.modifierId = modifierId;
        this.ordinal = ordinal;
        this.changes = changes;
    }

    private static String frame(String value) {
        return value.length() + ":" + value;
    }

    public RecipeProcess process() {
        return process;
    }

    public MachineInstance machine() {
        return process.owner();
    }

    public ResourceLocation modifierId() {
        return modifierId;
    }

    public int modifierOrdinal() {
        return ordinal;
    }

    /**
     * Writes a root requirement layer; repeated writes to this application's same layer replace it.
     */
    public <C, D> void put(RecipeRequirement<C> target, String layerId, RecipeOverlayType<C, D> type, D data) {
        put(target, target.path(null), layerId, type, data);
    }

    /**
     * Writes an exact nested target path and retains only this write's local undo information.
     */
    public <C, D> void put(RecipeRequirement<C> target, RequirementPath path, String layerId, RecipeOverlayType<C, D> type, D data) {
        if (!active) {
            throw new IllegalStateException("Recipe overlay modifier context is no longer active");
        }
        if (layerId.isBlank()) {
            throw new IllegalArgumentException("Blank overlay layer id");
        }
        String uniqueId = "recipe_overlay/" + frame(modifierId.toString()) + frame(Integer.toString(ordinal)) + frame(layerId);
        var change = RecipeOverlays.getOrCreate(process).put(target, path, uniqueId, type, data);
        changes.add(change);
    }

    void close() {
        active = false;
    }
}
