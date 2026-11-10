package com.circulation.prototype_eng.api.client.jei;

import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Objects;

/**
 * Explicit local declaration registration after initial pages/providers freeze. No key is inferred.
 */
@SideOnly(Side.CLIENT)
public final class ClientRecipeDeclarationRegistrationEvent extends Event {
    private final ClientRecipeDeclarations declarations;
    private final RecipeRegistry.Snapshot snapshot;
    private final RecipeJeiDisplays displays;

    public ClientRecipeDeclarationRegistrationEvent(ClientRecipeDeclarations declarations, RecipeRegistry.Snapshot snapshot,
                                                    RecipeJeiDisplays displays) {
        this.declarations = Objects.requireNonNull(declarations);
        this.snapshot = Objects.requireNonNull(snapshot);
        this.displays = Objects.requireNonNull(displays);
    }

    public ClientRecipeDeclarations declarations() {
        return declarations;
    }

    public RecipeRegistry.Snapshot snapshot() {
        return snapshot;
    }

    public RecipeJeiDisplays displays() {
        return displays;
    }
}
