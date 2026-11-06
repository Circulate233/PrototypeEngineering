package com.circulation.prototype_eng.api.recipe.matching;

import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;

/** Built-in matcher bindings; external mods can construct NamedResourceMatcher with their own predicate. */
public final class ResourceMatchers {
    public static final NamedResourceMatcher EXACT = new NamedResourceMatcher(new ResourceLocation(Tags.MOD_ID, "exact"), AEKey::equals);
    /** Downstream recipe definitions use item identity and metadata while ignoring NBT. */
    @SuppressWarnings("unused")
    public static final NamedResourceMatcher ITEM_ONLY = new NamedResourceMatcher(new ResourceLocation(Tags.MOD_ID, "item_only"),
        (candidate, pattern) -> candidate instanceof AEItemKey item && pattern instanceof AEItemKey prototype
            && item.getItem() == prototype.getItem()
            && item.getReadOnlyStack().getMetadata() == prototype.getReadOnlyStack().getMetadata());

    private ResourceMatchers() {
    }
}
