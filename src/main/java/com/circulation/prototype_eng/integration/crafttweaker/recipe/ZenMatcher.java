package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.matching.ResourceMatchers;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;


// ZenScript discovers these entry points through annotations rather than Java call sites.
@SuppressWarnings("unused")
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Matcher")
public record ZenMatcher(NamedResourceMatcher value) {

    @ZenMethod
    public static ZenMatcher create(String id, ZenResourceMatcher matcher) {
        return new ZenMatcher(new NamedResourceMatcher(ZenRecipeSupport.id(id),
            (candidate, pattern) -> matcher.matches(new ZenResource(candidate, 1), new ZenResource(pattern, 1))));
    }

    @ZenMethod
    public static ZenMatcher exact() {
        return new ZenMatcher(ResourceMatchers.EXACT);
    }

    @ZenMethod
    public static ZenMatcher itemOnly() {
        return new ZenMatcher(ResourceMatchers.ITEM_ONLY);
    }

    @ZenGetter("id")
    public String id() {
        return value.id().toString();
    }

}
