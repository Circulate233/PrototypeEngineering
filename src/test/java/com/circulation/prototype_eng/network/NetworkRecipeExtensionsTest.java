package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class NetworkRecipeExtensionsTest {
    private static ResearchRegistry research() {
        var research = new ResearchRegistry();
        research.register(ResearchDefinition.builder(id("research")).maxLevel(2)
                                            .deliveryPolicy(ResearchDownloadDeliveryPolicy.ITEM_ONLY).stage(ResearchLevelStage.builder(1, 2)
                                                                                                                              .researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        research.freeze();
        return research;
    }

    private static RecipeCatalog catalog(MachineRecipe... recipes) {
        return new RecipeCatalog(List.of(pool("pool", recipes)));
    }

    private static RecipePool pool(String path, MachineRecipe... recipes) {
        return new DefaultRecipePool(id(path), "test.title", List.of(recipes));
    }

    private static MachineRecipe recipe(String path) {
        return new MachineRecipe(id(path), 1, List.of());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("recipe_extensions_test", path);
    }

    @Test
    void callbacksSeeEveryCanonicalRecipeOnceInIdPriorityAndRegistrationOrder() {
        var first = recipe("a");
        var last = recipe("z");
        var middle = new MachineRecipe(id("m"), 1, List.of(), List.of(id("group")));
        var catalog = new RecipeCatalog(List.of(pool("one", last, first), pool("two", middle, first)));
        var extensions = new NetworkRecipeExtensions();
        var calls = new ObjectArrayList<String>();
        var retained = new ObjectArrayList<NetworkWorkRequirements.Builder>();
        extensions.register(20, (recipe, builder) -> {
            calls.add(recipe.id().getPath() + ":late");
            builder.resource(ComputingResourceFixtures.COMPUTE, 2);
        });
        extensions.register(-10, (recipe, builder) -> {
            calls.add(recipe.id().getPath() + ":first");
            retained.add(builder);
            assertFalse(extensions.published());
            assertThrows(IllegalStateException.class, () -> extensions.require(first));
            builder.research(id("research"), 1).resource(ComputingResourceFixtures.COMPUTE, 3);
        });
        extensions.register(-10, (recipe, builder) -> {
            calls.add(recipe.id().getPath() + ":second");
            builder.research(id("research"), 2).resource(ComputingResourceFixtures.QUBIT, 7);
        });
        extensions.publish(catalog, research());
        assertEquals(List.of("a:first", "a:second", "a:late", "m:first", "m:second", "m:late", "z:first", "z:second", "z:late"), calls);
        assertTrue(extensions.published());
        for (var recipe : List.of(first, middle, last)) {
            var demands = extensions.require(recipe);
            assertEquals(2, demands.researchRequirements().getInt(id("research")));
            assertEquals(5, demands.resources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(7, demands.resources().getDouble(ComputingResourceFixtures.QUBIT));
            assertThrows(IllegalArgumentException.class, () -> extensions.require(recipe(recipe.id().getPath())));
        }
        for (var builder : retained) {
            assertThrows(IllegalStateException.class, () -> builder.resource(ComputingResourceFixtures.COMPUTE, 1));
            assertThrows(IllegalStateException.class, builder::build);
        }
        assertThrows(IllegalArgumentException.class, () -> extensions.require(recipe("foreign")));
        assertThrows(IllegalStateException.class, () -> extensions.register((_, _) -> fail("Late callback")));
        assertThrows(IllegalStateException.class, () -> extensions.publish(catalog, research()));
    }

    @Test
    void originalCallbackFailurePublishesNothingAndClosesCompletedAndFailingContextsWithoutReplay() {
        var first = recipe("a");
        var last = recipe("z");
        var catalog = catalog(first, last);
        var extensions = new NetworkRecipeExtensions();
        var retained = new ObjectArrayList<NetworkWorkRequirements.Builder>();
        var calls = new AtomicInteger();
        var failure = new IllegalArgumentException("Extension failure");
        extensions.register((recipe, builder) -> {
            calls.incrementAndGet();
            retained.add(builder);
            builder.resource(ComputingResourceFixtures.COMPUTE, 5);
            if (recipe == last) {
                throw failure;
            }
        });
        var research = research();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> extensions.publish(catalog, research)));
        assertEquals(2, calls.get());
        assertFalse(extensions.published());
        assertThrows(IllegalStateException.class, () -> extensions.require(first));
        assertThrows(IllegalStateException.class, () -> extensions.require(last));
        for (var builder : retained) {
            assertThrows(IllegalStateException.class, () -> builder.research(id("research"), 1));
            assertThrows(IllegalStateException.class, builder::build);
        }
        assertThrows(IllegalStateException.class, () -> extensions.publish(catalog, research));
        assertThrows(IllegalStateException.class, () -> extensions.register((_, _) -> calls.incrementAndGet()));
        assertEquals(2, calls.get());
    }

    @Test
    void lateResearchValidationFailureDiscardsEarlierEntriesAndSealsAllCallbackContexts() {
        for (var invalidId : List.of(id("research"), id("unknown_research"))) {
            var first = recipe("a");
            var last = recipe("z");
            var extensions = new NetworkRecipeExtensions();
            var retained = new ObjectArrayList<NetworkWorkRequirements.Builder>();
            extensions.register((recipe, builder) -> {
                retained.add(builder);
                builder.research(recipe == last ? invalidId : id("research"), recipe == last ? 3 : 1);
            });
            assertThrows(IllegalArgumentException.class, () -> extensions.publish(catalog(first, last), research()));
            assertFalse(extensions.published());
            assertThrows(IllegalStateException.class, () -> extensions.require(first));
            for (var builder : retained) {
                assertThrows(IllegalStateException.class, builder::build);
            }
        }
    }

    @Test
    void dependencyFailureStartsNoCallbacksAndCannotBeRetried() {
        var extensions = new NetworkRecipeExtensions();
        var calls = new AtomicInteger();
        extensions.register((_, _) -> calls.incrementAndGet());
        var catalog = catalog(recipe("one"));
        assertThrows(IllegalStateException.class, () -> extensions.publish(catalog, new ResearchRegistry()));
        assertThrows(IllegalStateException.class, () -> extensions.publish(catalog, research()));
        assertEquals(0, calls.get());
        assertFalse(extensions.published());
    }

    @Test
    void catalogIdentityRejectsCounterfeitsAndEmptyCatalogNeedsNoCallbacks() {
        var canonical = recipe("one");
        var extensions = new NetworkRecipeExtensions();
        extensions.publish(catalog(canonical), research());
        assertTrue(extensions.require(canonical).researchRequirements().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> extensions.require(recipe("one")));
        var empty = new NetworkRecipeExtensions();
        empty.register((_, _) -> fail("Empty catalog cannot invoke callbacks"));
        empty.publish(catalog(), research());
        assertTrue(empty.published());
        assertThrows(IllegalArgumentException.class, () -> empty.require(canonical));
    }
}
