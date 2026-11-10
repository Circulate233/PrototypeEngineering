package com.circulation.prototype_eng.integration.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistry;
import com.circulation.prototype_eng.api.client.jei.StaticJeiTestViews;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.client.recipe.ClientRecipeDefinitions;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.network.clientbound.RecipeDefinitionManifestPacket;
import com.circulation.prototype_eng.recipe.RecipeDefinitionManifestCodec;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
class StaticJeiFallbackTest {
    private static final KeyType TYPE = new KeyType("static_jei_fallback");
    private static final RecipeJeiDisplays.Provider<ResourceRequirement> PROVIDER = (recipe, requirement, builder) -> {
        for (var input : requirement.payload().inputs()) builder.resource(input, JeiRecipeDisplay.Role.INPUT);
        builder.information(JeiRecipeDisplay.Text.of(JeiText.CustomRequirement, recipe.durationTicks()));
    };

    private static ResourceLocation id(String name) { return new ResourceLocation("static_jei_fallback", name); }

    private static MachineRecipe recipe(String name, long amount) {
        return new MachineRecipe(id(name), 20, List.of(RecipeRequirements.RESOURCES.requirement(id(name + "_io"),
            new ResourceRequirement(List.of(new GenericStack(new Key(TYPE, name), amount)), List.of()))));
    }

    private static RecipePool pool(String name, MachineRecipe recipe) {
        return new DefaultRecipePool(id(name), "static.jei.pool", List.of(recipe));
    }

    private static RecipeRegistry.Snapshot snapshot(RecipePool... pools) {
        var registry = new RecipeRegistry();
        for (var pool : pools) registry.register(pool);
        registry.freeze(List.of());
        return registry.snapshot();
    }

    private static RecipeJeiDisplays displays(RecipeJeiDisplays.Provider<ResourceRequirement> provider) {
        var displays = new RecipeJeiDisplays();
        displays.register(RecipeRequirements.RESOURCES, provider);
        displays.freeze();
        return displays;
    }

    @Test
    void frozenBaselineUsesMeasuredReferencesForDefaultAndLegacyStaticWrappers() {
        var renderer = new PageRenderer(true);
        var initial = renderer.wrappers;
        var display = initial.getFirst().display();
        assertNotSame(display, renderer.baseline.displays().snapshot(List.of(renderer.defaultRecipe)).describe(renderer.defaultRecipe));
        assertTrue(renderer.showLocalStatic().ready());
        assertSame(display, renderer.wrappers.getFirst().display());
        assertSame(initial.getLast().display(), renderer.wrappers.getLast().display());
        assertSame(renderer.baseline.snapshot().orderedPools().getFirst(), renderer.pages.pages().getFirst().pool());
        assertFalse(initial.getFirst().isCurrent());
        assertFalse(initial.getLast().isCurrent());
        assertTrue(renderer.wrappers.stream().allMatch(RecipePoolJeiRecipe::isCurrent));
    }

    @Test
    void unversionedStaticNoticeRestoresRealPagesWithoutAnAppliedDeclaration() {
        var renderer = new PageRenderer(true);
        var state = new ClientRecipeDefinitions(new ClientRecipeDeclarations(), renderer);
        state.runtimeReady();
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());
        var old = renderer.wrappers;
        var connection = new Object();
        state.connected(connection);
        assertFalse(state.localStaticVisible());
        assertFalse(old.getFirst().isCurrent());
        assertFalse(state.receive(connection, RecipeDefinitionManifestPacket.notice(UUID.randomUUID(), 0, false)));
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());
        assertTrue(renderer.wrappers.stream().allMatch(RecipePoolJeiRecipe::isCurrent));
        var connectedWrappers = renderer.wrappers;
        state.disconnected(connection);
        state.retry();
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());
        assertFalse(connectedWrappers.getFirst().isCurrent());
        assertTrue(renderer.wrappers.getFirst().isCurrent());
    }

    @Test
    void categoryReconstructionMeasuresOneNewSourceAndRetainsTheOriginalLocalCatalog() {
        var renderer = new PageRenderer(true);
        var state = new ClientRecipeDefinitions(new ClientRecipeDeclarations(), renderer);
        state.runtimeReady();
        var oldWrappers = renderer.wrappers;
        var originalCatalog = renderer.baseline.snapshot();
        state.runtimeUnavailable();
        renderer.rebuild();
        state.runtimeReady();
        assertSame(originalCatalog, renderer.baseline.snapshot());
        assertNotSame(oldWrappers.getFirst().page(), renderer.wrappers.getFirst().page());
        assertNotSame(oldWrappers.getLast().page(), renderer.wrappers.getLast().page());
        assertNotSame(oldWrappers.getFirst().display(), renderer.wrappers.getFirst().display());
        assertTrue(renderer.wrappers.stream().allMatch(RecipePoolJeiRecipe::isCurrent));
        assertNull(state.applied());
        var managed = new PrototypeJeiPlugin.Definitions(snapshot(pool("default", recipe("managed", 9))), displays(PROVIDER));
        assertSame(managed, PrototypeJeiPlugin.registrationDefinition(managed, renderer.baseline));
        assertSame(renderer.baseline, PrototypeJeiPlugin.registrationDefinition(null, renderer.baseline));
    }

    @Test
    void managedReplacementThenDisconnectRemeasuresDefaultAgainstOriginalStaticCatalog() {
        var renderer = new PageRenderer(false);
        var declarations = new ClientRecipeDeclarations();
        var state = new ClientRecipeDefinitions(declarations, renderer);
        state.runtimeReady();
        var connection = new Object();
        state.connected(connection);
        var managedRecipe = recipe("managed", Long.MAX_VALUE);
        var registry = new RecipeRegistry();
        registry.register(pool("default", managedRecipe));
        registry.declareRevision(managedRecipe, "v2");
        registry.freeze(List.of());
        var managed = registry.snapshot();
        var key = new RecipeDeclarationKey(id("managed_declaration"), "v2");
        declarations.register(key, managed, displays(PROVIDER));
        var manifest = RecipeDefinitionManifest.capture(UUID.randomUUID(), key, managed);
        byte[] encoded = RecipeDefinitionManifestCodec.encode(manifest);
        assertTrue(state.receive(connection, RecipeDefinitionManifestPacket.part(manifest, encoded, 0)));
        assertEquals(manifest, state.applied());
        assertSame(managedRecipe, renderer.wrappers.getFirst().recipe());
        assertEquals(Long.MAX_VALUE, renderer.wrappers.getFirst().display().resources().getFirst().stack().amount());
        var managedWrappers = renderer.wrappers;
        state.disconnected(connection);
        state.retry();
        assertTrue(state.localStaticVisible());
        assertNull(state.applied());
        assertSame(renderer.defaultRecipe, renderer.wrappers.getFirst().recipe());
        assertSame(renderer.baseline.snapshot().orderedPools().getFirst(), renderer.pages.pages().getFirst().pool());
        assertFalse(managedWrappers.getFirst().isCurrent());
        assertTrue(renderer.wrappers.getFirst().isCurrent());
    }

    @Test
    void requiredDeclarationNoticeStaysStickyAndDoesNotReopenStaticPages() {
        var renderer = new PageRenderer(true);
        var state = new ClientRecipeDefinitions(new ClientRecipeDeclarations(), renderer);
        var connection = new Object();
        var session = UUID.randomUUID();
        state.connected(connection);
        state.receive(connection, RecipeDefinitionManifestPacket.notice(session, 0, false));
        assertTrue(state.localStaticVisible());
        var visible = renderer.wrappers;
        state.receive(connection, RecipeDefinitionManifestPacket.notice(session, 0, true));
        state.receive(connection, RecipeDefinitionManifestPacket.notice(session, 0, false));
        state.runtimeReady();
        assertFalse(state.localStaticVisible());
        assertNull(state.applied());
        assertFalse(visible.getFirst().isCurrent());
    }

    @Test
    void opaquePageRefusesChangedPoolsProvidersAndUnconfirmedData() {
        var renderer = new PageRenderer(true);
        var recipes = renderer.pages.ownedRecipes(renderer.baseline.snapshot().orderedPools());
        var reused = renderer.baseline.displays().snapshot(recipes, renderer.previousProviders, renderer.measured);
        assertNotNull(PrototypeJeiPlugin.prepareStaticPages(renderer.pages.pages(), renderer.baseline.snapshot(), reused, renderer.measured, true));
        assertNull(PrototypeJeiPlugin.prepareStaticPages(renderer.pages.pages(), renderer.baseline.snapshot(), reused, renderer.measured, false));
        RecipeJeiDisplays.Provider<ResourceRequirement> changedProvider = (_, _, builder) ->
            builder.information(JeiRecipeDisplay.Text.of(JeiText.End));
        var changedSource = displays(changedProvider);
        var changed = changedSource.snapshot(recipes, renderer.previousProviders, renderer.measured);
        assertNotSame(renderer.measured.describe(renderer.customRecipe), changed.describe(renderer.customRecipe));
        assertNull(PrototypeJeiPlugin.prepareStaticPages(renderer.pages.pages(), renderer.baseline.snapshot(), changed, renderer.measured,
            changedSource.sameProviders(renderer.previousProviders)));
        var replacementRecipe = recipe("custom_recipe", 7);
        var replacementPool = pool("legacy", replacementRecipe);
        var replacement = snapshot(renderer.baseline.snapshot().orderedPools().getFirst(), replacementPool);
        var replacedRecipes = renderer.pages.ownedRecipes(replacement.orderedPools());
        var replaced = renderer.baseline.displays().snapshot(replacedRecipes, renderer.previousProviders, renderer.measured);
        assertNotSame(renderer.measured.describe(renderer.customRecipe), replaced.describe(replacementRecipe));
        assertNull(PrototypeJeiPlugin.prepareStaticPages(renderer.pages.pages(), replacement, replaced, renderer.measured, true));
        var defaultPage = renderer.pages.pages().getFirst();
        var foreign = displays(PROVIDER).snapshot(List.of(renderer.defaultRecipe)).describe(renderer.defaultRecipe);
        assertThrows(NullPointerException.class, () -> defaultPage.createRecipe(renderer.defaultRecipe, foreign, StaticJeiTestViews.ingredients(foreign)));
    }

    /** Native calls are omitted; all display, default Data, legacy cache, wrapper and token work is real. */
    private static final class PageRenderer implements ClientRecipeDefinitions.Renderer {
        private final boolean legacy;
        private final MachineRecipe defaultRecipe = recipe("default_recipe", 1);
        private final MachineRecipe customRecipe = recipe("custom_recipe", 2);
        private PrototypeJeiPlugin.Definitions baseline;
        private RecipePoolJeiRegistry pages;
        private RecipeJeiDisplays previousProviders;
        private RecipeJeiDisplays.Snapshot measured;
        private List<RecipePoolJeiRecipe> wrappers;
        private RecipePoolJeiRecipe.DisplayToken token;
        private boolean pageDataCurrent;

        private PageRenderer(boolean legacy) {
            this.legacy = legacy;
            var local = legacy ? snapshot(pool("default", defaultRecipe), pool("legacy", customRecipe))
                : snapshot(pool("default", defaultRecipe));
            baseline = new PrototypeJeiPlugin.Definitions(local, displays(PROVIDER).frozenCopy());
            rebuild();
        }

        private void rebuild() {
            var source = displays(PROVIDER);
            var selected = PrototypeJeiPlugin.registrationDefinition(null, new PrototypeJeiPlugin.Definitions(baseline.snapshot(), source));
            measured = source.capture(RecipePoolJeiRegistry.recipesForOwnPages(selected.snapshot().orderedPools(), Set.of()));
            pages = new RecipePoolJeiRegistry();
            if (legacy) {
                var customPool = selected.snapshot().pools().get(id("legacy"));
                pages.register(customPool.id(), StaticJeiTestViews.legacyPage(customPool, measured));
            }
            pages.freeze(selected.snapshot().orderedPools(), pool -> StaticJeiTestViews.page(pool, measured), measured);
            var initial = new ObjectArrayList<RecipePoolJeiRecipe>();
            for (var page : pages.pages()) for (var recipe : page.pool().recipes()) {
                var display = measured.describe(recipe);
                assertSame(source.describe(recipe), display);
                initial.add(page.createRecipe(recipe, display, StaticJeiTestViews.ingredients(display)));
            }
            baseline = new PrototypeJeiPlugin.Definitions(baseline.snapshot(), source.frozenCopy());
            previousProviders = source.frozenCopy();
            publish(initial);
        }

        @Override public ClientRecipeDefinitions.ApplyResult showLocalStatic() {
            var recipes = pages.ownedRecipes(baseline.snapshot().orderedPools());
            var descriptions = baseline.displays().snapshot(recipes, previousProviders, measured);
            var updates = PrototypeJeiPlugin.prepareStaticPages(pages.pages(), baseline.snapshot(), descriptions, measured,
                pageDataCurrent && baseline.displays().sameProviders(previousProviders));
            return install(baseline, descriptions, updates);
        }

        @Override public ClientRecipeDefinitions.ApplyResult apply(ClientRecipeDeclarations.Declaration declaration) {
            var recipes = pages.ownedRecipes(declaration.snapshot().orderedPools());
            var descriptions = declaration.displays().snapshot(recipes);
            return install(new PrototypeJeiPlugin.Definitions(declaration.snapshot(), declaration.displays()), descriptions,
                pages.tryPrepareUpdates(declaration.snapshot().orderedPools(), descriptions));
        }

        private ClientRecipeDefinitions.ApplyResult install(PrototypeJeiPlugin.Definitions definitions,
                RecipeJeiDisplays.Snapshot descriptions, @Nullable List<RecipePoolJeiPage.PreparedUpdate> updates) {
            if (updates == null) return new ClientRecipeDefinitions.ApplyResult(false, "Page refused update");
            var next = new ObjectArrayList<RecipePoolJeiRecipe>();
            for (var update : updates) for (var recipe : update.pool().recipes()) {
                var display = descriptions.describe(recipe);
                next.add(update.createRecipe(recipe, display, StaticJeiTestViews.ingredients(display)));
            }
            pageDataCurrent = false;
            for (var update : updates) update.apply();
            measured = descriptions;
            previousProviders = definitions.displays().frozenCopy();
            publish(next);
            return new ClientRecipeDefinitions.ApplyResult(true, "Ready");
        }

        private void publish(List<RecipePoolJeiRecipe> next) {
            if (token != null) token.revoke();
            token = new RecipePoolJeiRecipe.DisplayToken();
            for (var wrapper : next) wrapper.bindDisplayToken(token);
            token.activate();
            wrappers = next;
            pageDataCurrent = true;
        }

        @Override public void withdraw(String ignoredReason) {
            Objects.requireNonNull(token).revoke();
        }
    }
}
