package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionRevision;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.localization.RecipeText;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeRegistryReplacementTest {
    private static ResourceLocation id(String name) {
        return new ResourceLocation("registry_replacement", name);
    }

    private static MachineRecipe recipe(String name, String group) {
        return new MachineRecipe(id(name), 3, List.of(), Set.of(id(group)));
    }

    private static RecipeRegistry registry(MachineRecipe recipe) {
        var registry = new RecipeRegistry();
        registry.register(new DefaultRecipePool(id("pool"), "pool.title", List.of(recipe)));
        registry.declareRevision(recipe, "v1");
        return registry;
    }

    @Test
    void replacesAllOwningPoolsAndGroupsWhileKeepingPriorityMachinesAndCustomStrategy() {
        var old = recipe("shared", "old_group");
        var next = recipe("shared", "new_group");
        var first = recipe("first", "common");
        var last = recipe("last", "common");
        var control = new FreezeControl();
        var registry = new RecipeRegistry();
        registry.register(new StrategyPool(id("a"), "pool.a", List.of(first, old), List.of(), control));
        registry.register(new DefaultRecipePool(id("b"), "pool.b", List.of(old, last)));
        registry.register(new DefaultRecipePool(id("c"), "pool.c", List.of(last)));
        registry.declareRevision(old, "v1");
        var machine = MachineType.builder(id("machine"), RecipeText.Cancelled, id("structure")).recipePool(id("a")).build();
        registry.freeze(List.of(machine));
        var before = registry.snapshot();
        var untouched = registry.get(id("c"));
        var prepared = registry.prepareReplacement(before, List.of(new RecipeRegistry.Definition(next, "v2")));
        assertSame(before, registry.snapshot());
        assertEquals(List.of(first, old), Objects.requireNonNull(registry.get(id("a"))).recipes());
        assertSame(control, assertInstanceOf(StrategyPool.class, prepared.next().pools().get(id("a"))).control());
        assertEquals(List.of(machine), prepared.next().pools().get(id("a")).machines());
        assertSame(untouched, prepared.next().pools().get(id("c")));
        registry.install(prepared);
        assertEquals(List.of(first, next), Objects.requireNonNull(registry.get(id("a"))).recipes());
        assertEquals(List.of(next, last), Objects.requireNonNull(registry.get(id("b"))).recipes());
        assertEquals(List.of(next), registry.catalog().candidates(new MachineInstance(), Set.of(id("new_group")), List.of()));
        assertTrue(registry.catalog().candidates(new MachineInstance(), Set.of(id("old_group")), List.of()).isEmpty());
        assertSame(old, registry.snapshot().historicalRecipe(new RecipeDefinitionRevision(old.id(), "v1")));
        assertFalse(registry.canInstall(prepared));
        assertThrows(IllegalStateException.class, () -> registry.install(prepared));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareReplacement(before, prepared.replacements()));
    }

    @Test
    void failingOrIdentityChangingFreezeNeverPublishesAndPreservesTheOriginalFailure() {
        var old = recipe("shared", "group");
        var next = recipe("shared", "changed");
        var control = new FreezeControl();
        var registry = new RecipeRegistry();
        registry.register(new StrategyPool(id("pool"), "title", List.of(old), List.of(), control));
        registry.freeze();
        var expected = registry.snapshot();
        control.failure = new IllegalStateException("pool cannot freeze this definition");
        assertSame(control.failure, assertThrows(IllegalStateException.class,
            () -> registry.prepareReplacement(expected, List.of(new RecipeRegistry.Definition(next, "v2")))));
        assertSame(expected, registry.snapshot());
        control.failure = null;
        control.changeIdentity = true;
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(expected, List.of(new RecipeRegistry.Definition(next, "v2"))));
        assertSame(expected, registry.snapshot());
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(expected, List.of(new RecipeRegistry.Definition(recipe("unknown", "group"), "v2"))));
        assertSame(expected, registry.snapshot());
    }

    @Test
    void nonPersistentDefinitionsKeepHistoryAndRejectReusedRevisionsAndForeignExpectedSnapshots() {
        var old = recipe("shared", "group");
        var next = recipe("shared", "changed");
        var registry = registry(old);
        registry.freeze();
        var before = registry.snapshot();
        assertNull(registry.processCodecs());
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(before, List.of(new RecipeRegistry.Definition(next, "v1"))));
        var prepared = registry.prepareReplacement(before, List.of(new RecipeRegistry.Definition(next, "v2")));
        var foreign = registry(old);
        foreign.freeze();
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(foreign.snapshot(), prepared.replacements()));
        registry.install(prepared);
        var third = recipe("shared", "third");
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(registry.snapshot(), List.of(new RecipeRegistry.Definition(third, "v1"))));
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(registry.snapshot(), List.of(new RecipeRegistry.Definition(old, "v3"))));
        assertSame(next, registry.catalog().recipes().getFirst());
    }

    @Test
    void boundResolverRequiresOnlyTheReplacedPersistedDefinitionAndPublishesBothTablesTogether() {
        var old = recipe("saved", "group");
        var next = recipe("saved", "changed");
        var outside = recipe("independent", "group");
        var outsideNext = recipe("independent", "changed");
        var oldCodec = RecipeProcessNbt.builder(old, "v1").build();
        var nextCodec = RecipeProcessNbt.builder(next, "v2").build();
        var resolver = new RecipeProcessCodecs(List.of(oldCodec));
        var registry = registry(old);
        registry.registerRecipe(id("pool"), outside);
        registry.bindProcessCodecs(resolver);
        registry.freeze();
        var expected = registry.snapshot();
        var codecExpected = resolver.snapshot();
        assertThrows(IllegalArgumentException.class,
            () -> registry.prepareReplacement(expected, List.of(new RecipeRegistry.Definition(next, "v2"))));
        assertThrows(IllegalArgumentException.class, () -> registry.prepareReplacement(expected,
            List.of(new RecipeRegistry.Definition(next, "v2")), List.of(RecipeProcessNbt.builder(next, "wrong").build())));
        assertSame(expected, registry.snapshot());
        assertSame(codecExpected, resolver.snapshot());
        var prepared = registry.prepareReplacement(expected,
            List.of(new RecipeRegistry.Definition(next, "v2"), new RecipeRegistry.Definition(outsideNext, "external-v2")), List.of(nextCodec));
        assertNull(resolver.snapshot().get(next));
        assertThrows(IllegalStateException.class, () -> resolver.install(Objects.requireNonNull(prepared.codecPreparation())));
        registry.install(prepared);
        assertSame(nextCodec, resolver.snapshot().require(next));
        assertSame(oldCodec, resolver.snapshot().require(old.id(), "v1"));
        assertNull(resolver.snapshot().get(outsideNext));
        assertEquals(List.of(next, outsideNext), registry.catalog().recipes());
    }

    @Test
    void coldStartHistoryAndCodecIdentityConflictsFailBeforeAnyPublication() {
        var old = recipe("shared", "old");
        var current = recipe("shared", "new");
        var oldCodec = RecipeProcessNbt.builder(old, "v1").build();
        var resolver = new RecipeProcessCodecs(List.of(oldCodec));
        var before = resolver.snapshot();
        assertThrows(IllegalArgumentException.class,
            () -> resolver.prepare(before, List.of(RecipeProcessNbt.builder(current, "v1").build())));
        assertThrows(IllegalArgumentException.class,
            () -> resolver.prepare(before, List.of(RecipeProcessNbt.builder(old, "v2").build())));
        assertThrows(IllegalArgumentException.class,
            () -> resolver.prepare(before, List.of(RecipeProcessNbt.builder(old, "v1").build())));
        assertSame(before, resolver.snapshot());
        var currentCodec = RecipeProcessNbt.builder(current, "v2").build();
        var additions = resolver.prepare(before, List.of(oldCodec, currentCodec));
        resolver.install(additions);
        assertSame(oldCodec, resolver.snapshot().require(old));
        assertSame(currentCodec, resolver.snapshot().require(current.id(), "v2"));
        assertThrows(IllegalStateException.class, () -> resolver.install(additions));
        var registry = registry(current);
        registry.declareHistoricalRevision(old, "v1");
        assertThrows(IllegalArgumentException.class, registry::freeze);
        var valid = new RecipeRegistry();
        valid.register(new DefaultRecipePool(id("pool"), "title", List.of(current)));
        valid.declareRevision(current, "v2");
        valid.declareHistoricalRevision(old, "v1");
        valid.freeze();
        assertSame(old, valid.snapshot().historicalRecipe(new RecipeDefinitionRevision(old.id(), "v1")));
        assertEquals(List.of(current), valid.catalog().recipes());
    }

    private static final class FreezeControl {
        private RuntimeException failure;
        private boolean changeIdentity;
    }

    private record StrategyPool(ResourceLocation id, String translationKey, List<MachineRecipe> recipes,
                                List<MachineType> machines, FreezeControl control) implements RecipePool {
        @Override
        public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            if (control.failure != null) throw control.failure;
            return new StrategyPool(control.changeIdentity ? RecipeRegistryReplacementTest.id("foreign_pool") : id,
                translationKey, recipes, machines, control);
        }
    }
}
