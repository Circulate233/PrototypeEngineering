package com.circulation.prototype_eng.api.client.jei;

import ae2.api.integrations.hei.IngredientConverter;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ScriptJeiEvents;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiOptions;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ZenJeiPage;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipePool;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import com.circulation.prototype_eng.integration.jei.AutoLayoutRecipePage;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import mezz.jei.api.recipe.IIngredientType;
import net.minecraft.client.resources.JeiTestLocale;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class ZenJeiPoolBridgeTest {
    private static final KeyType TYPE = new KeyType("ct_jei");
    private static final IIngredientType<GenericStack> INGREDIENT = () -> GenericStack.class;

    private static JeiRecipeIngredients ingredients(JeiRecipeDisplay display) {
        var converter = new IngredientConverter<GenericStack>() {
            @Override
            public IIngredientType<GenericStack> getIngredientType() {
                return INGREDIENT;
            }

            @Override
            public @Nullable GenericStack getIngredientFromStack(GenericStack value) {
                return value.what().getType() == TYPE ? value : null;
            }

            @Override
            public GenericStack getStackFromIngredient(GenericStack value) {
                return value;
            }
        };
        var identities = new JeiQueryIdentityIndex();
        identities.captureConverter(converter, new JeiTestViews.IdentityHelper<>(value -> value.what().toTag().toString()), List.of(), List.of(display));
        return new JeiRecipeIngredients(display, List.of(converter), List.of(INGREDIENT), identities);
    }

    @Test
    void compiledSimplePoolOptionsAndIndependentInstanceBindAndReplayTheSameNativePages() throws Exception {
        var module = compile("""
            import mods.prototype_eng.recipe.Recipe;
            import mods.prototype_eng.recipe.Requirement;
            import mods.prototype_eng.recipe.Pool;
            import mods.prototype_eng.jei.Options;
            import mods.prototype_eng.jei.Page;
            import tests.prototype_eng.JeiPoolHarness;
            var recipe = Recipe.create("ct_jei:recipe", "v1", 2,
                [Requirement.resources("ct_jei:resource", [JeiPoolHarness.resource()], [JeiPoolHarness.resource()])]);
            var ordinary = Pool.simple("ct_jei:ordinary", "pool.title", [recipe])
                .jeiOptions(Options.create(false, "expanded"));
            var independent = Pool.create("ct_jei:independent", "pool.title", [recipe])
                .customJeiPage(Page.named("ct_jei:addon_instance"));
            JeiPoolHarness.capture(ordinary, independent);
            """);
        Objects.requireNonNull(module.getMain()).run();
        Harness.ordinary.declareClient();
        Harness.independent.declareClient();
        var source = new RecipeRegistry();
        source.register(Harness.ordinary.value());
        source.register(Harness.independent.value());
        source.freeze();
        var pools = source.pools();
        var independent = new IndependentPage(Objects.requireNonNull(source.get(Harness.independent.value().id())));
        ScriptJeiEvents.page("ct_jei:addon_instance", independent);
        var displays = new RecipeJeiDisplays();
        displays.register(RecipeRequirements.RESOURCES, (_, requirement, builder) -> {
            var group = builder.candidates(new JeiRecipeDisplay.CandidateGroup("two_choices"));
            group.resource(requirement.payload().inputs().getFirst(), JeiRecipeDisplay.Role.INPUT);
            group.resource(new GenericStack(new Key(TYPE, "alternative"), 9), JeiRecipeDisplay.Role.INPUT);
        });
        displays.freeze();
        var descriptions = displays.snapshot(pools.stream().flatMap(pool -> pool.recipes().stream()).toList());
        var pages = new RecipePoolJeiRegistry();
        for (int generation = 0; generation < 2; generation++) {
            pages.beginRegistration();
            ScriptJeiEvents.register(new RecipePoolJeiRegistrationEvent(pages, displays, new JeiTestViews.Helper(), pools));
            pages.freeze(pools, pool -> new AutoLayoutRecipePage(pool, new JeiTestViews.Helper(), descriptions,
                pages.renderOptions(pool.id()), JeiTestViews.FONT), descriptions);
            assertEquals(2, pages.pages().size());
            assertTrue(pages.pages().contains(independent));
            var ordinary = pages.pages().stream().filter(page -> page != independent).findFirst().orElseThrow();
            assertInstanceOf(SimpleRecipePool.class, ordinary.pool());
            assertFalse(((AutoLayoutRecipePage) ordinary).renderOptions().chanceOverlay());
            var recipe = ordinary.pool().recipes().getFirst();
            var display = descriptions.describe(recipe);
            var ingredients = ingredients(display);
            var wrapper = ordinary.createRecipe(recipe, display, ingredients);
            var layout = new JeiTestViews.Layout();
            ordinary.setRecipe(layout, wrapper, new JeiTestViews.Ingredients());
            var nativeSlots = layout.group(INGREDIENT).values;
            assertEquals(2, nativeSlots.size(), "Script expanded options reach the actual native recipe layout");
            assertEquals(1, nativeSlots.get(0).size());
            assertEquals(1, nativeSlots.get(1).size());
            // Native converter icons keep one-unit query identity; the exact row supplies overlay/tooltip amounts.
            JeiTestLocale.withTranslations(Map.of(JeiText.Consume.getTranslationKey(), "Consume %s"), () -> {
                for (int slot = 0; slot < 2; slot++) {
                    var icon = nativeSlots.get(slot).getFirst();
                    var copiedIcon = new GenericStack(icon.what(), 1);
                    var tooltip = new ObjectArrayList<String>();
                    layout.group(INGREDIENT).tooltips.getFirst().onTooltip(slot, true, copiedIcon, tooltip);
                    assertTrue(tooltip.contains(slot == 0 ? "Consume 7" : "Consume 9"),
                        "Actual native tooltip callback must resolve a copied identity to its original quantity");
                }
            });
            assertEquals(9, Objects.requireNonNull(wrapper.patternRecipe()).inputs().get(1).getFirst().amount(),
                "The real wrapper's pattern transfer keeps the full displayed quantity");
            var customWrapper = independent.createRecipe(recipe, display, ingredients);
            independent.setRecipe(new JeiTestViews.Layout(), customWrapper, new JeiTestViews.Ingredients());
            assertEquals(generation + 1, independent.layouts);
            assertSame(independent.pool(), customWrapper.pool());
        }
    }

    @SuppressWarnings("SameParameterValue") // The actual compiler fixture receives this focused script.
    private ZenModule compile(String script) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenRecipe.class, ZenRecipePool.class, ZenRequirement.class, ZenResource.class,
            ZenJeiOptions.class, ZenJeiPage.class, IData.class, Harness.class))
            registry.registerNativeClass(type);
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile("jei_pool.zs", "JeiPoolScript", new ZenTokener(new StringReader(script), environment, "jei_pool.zs", false), global);
        ZenModule.compileScripts("jei_pool.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        return new ZenModule(classes, getClass().getClassLoader());
    }

    @ZenClass("tests.prototype_eng.JeiPoolHarness")
    @SuppressWarnings("unused") // Invoked by actual compiled ZenScript.
    public static final class Harness {
        private static ZenRecipePool ordinary;
        private static ZenRecipePool independent;

        @ZenMethod
        public static ZenResource resource() {
            return new ZenResource(new Key(TYPE, "input"), 7);
        }

        @ZenMethod
        public static void capture(ZenRecipePool first, ZenRecipePool second) {
            ordinary = first;
            independent = second;
        }
    }

    private static final class IndependentPage implements RecipePoolJeiPage {
        private final RecipePool pool;
        private int layouts;

        private IndependentPage(RecipePool pool) {
            this.pool = pool;
        }

        @Override
        public RecipePool pool() {
            return pool;
        }

        @Override
        public IDrawable getBackground() {
            return new JeiTestViews.Blank(88, 32);
        }

        @Override
        public void setRecipe(IRecipeLayout ignoredLayout, RecipePoolJeiRecipe wrapper, IIngredients ignoredIngredients) {
            assertSame(this, wrapper.page());
            assertSame(pool, wrapper.pool());
            layouts++;
        }
    }
}
