package com.circulation.prototype_eng.integration.jei;

import ae2.api.integrations.hei.IngredientConverters;
import ae2.container.me.patternencode.ContainerPEATerm;
import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import com.circulation.prototype_eng.api.client.jei.JeiEnergyIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiQueryIdentityIndex;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistry;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.gui.recipes.RecipesGui;
import mezz.jei.config.Config;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IModIngredientRegistration;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import com.circulation.prototype_eng.api.machine.MachineType;
import java.util.function.Supplier;
import it.unimi.dsi.fastutil.objects.ObjectLists;

/**
 * HEI 4.35.1 discovers this entry on the client; common mod initialization never references it.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("unused") // Discovered by HEI's @JEIPlugin scan on the client.
public class PrototypeJeiPlugin implements IModPlugin {
    @Nullable private static IJeiRuntime runtime;
    @Nullable private static IIngredientRegistry ingredients;
    @Nullable private static Generation generation;
    @Nullable private static Supplier<Definitions> desiredResolved;
    @Nullable private static Runnable runtimeReady;
    @Nullable private static Runnable runtimeUnavailable;
    private static RecipeRegistry.Snapshot registrationSnapshot;
    private static RecipeJeiDisplays registrationDisplays;
    private static RecipeJeiDisplays.Snapshot registrationDescriptions;
    private static boolean pageDataCurrent;
    @Nullable private static Definitions localStaticBaseline;
    private static boolean declarationEventPosted;
    private static boolean applying;
    private static boolean withdrawing;
    private static long invalidationEpoch;
    private boolean deferPoolRegistration;
    private boolean postInitialDeclaration;
    private static String unreadyReason = "HEI runtime is not available";

    public record Definitions(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
        public Definitions { Objects.requireNonNull(snapshot); Objects.requireNonNull(displays); }
    }

    public record ApplyResult(boolean ready, boolean pendingRuntime, String reason) {}

    public static void configureDefinitions(Supplier<Definitions> resolved, Runnable available) {
        configureDefinitions(resolved, available, () -> {});
    }

    public static void configureDefinitions(Supplier<Definitions> resolved, Runnable available, Runnable unavailable) {
        requireClientOwner();
        Objects.requireNonNull(resolved);
        Objects.requireNonNull(available);
        Objects.requireNonNull(unavailable);
        desiredResolved = resolved;
        runtimeReady = available;
        runtimeUnavailable = unavailable;
    }

    public static ApplyResult status() {
        return new ApplyResult(generation != null && generation.token.current(), runtime == null, unreadyReason);
    }

    public static void withdrawDefinitions(String reason) {
        requireClientOwner();
        Objects.requireNonNull(reason);
        invalidationEpoch = Math.incrementExact(invalidationEpoch);
        if (generation != null) generation.token.revoke();
        JeiRecipeInputs.reset();
        unreadyReason = reason;
        if (runtime == null || generation == null || withdrawing) return;
        var capturedRuntime = runtime;
        var capturedGeneration = generation;
        withdrawing = true;
        try {
            if (capturedRuntime.getRecipesGui() instanceof RecipesGui gui) HeiRecipeGeneration.closeLayout(Minecraft.getMinecraft(), gui);
            if (capturedRuntime.getRecipeRegistry() instanceof mezz.jei.recipes.RecipeRegistry registry)
                HeiRecipeGeneration.hideAll(new HeiRecipeGeneration.NativeAccess(registry), capturedGeneration.wrappers, List.of());
        } finally {
            withdrawing = false;
        }
    }

    /** A complete display generation is prepared before the first native mutation. */
    public static ApplyResult applyDefinitions(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
        return apply(snapshot, displays, false);
    }

    /** Local legacy view only; this does not establish a matching remote declaration. */
    public static ApplyResult showLocalStaticDefinitions() {
        var baseline = localStaticBaseline;
        if (baseline == null) return new ApplyResult(false, runtime == null, "Local static recipe baseline is not captured");
        return apply(baseline.snapshot(), baseline.displays(), true);
    }

    private static ApplyResult apply(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays, boolean staticInitial) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(displays);
        if (runtime == null || ingredients == null) return new ApplyResult(false, true, "HEI runtime is not available");
        requireClientOwner();
        if (!(runtime.getRecipeRegistry() instanceof mezz.jei.recipes.RecipeRegistry registry)
            || !(runtime.getRecipesGui() instanceof RecipesGui gui)) {
            withdrawDefinitions("Unsupported HEI runtime implementation");
            return status();
        }
        if (applying || withdrawing) return new ApplyResult(false, false, "A JEI definition update is already applying or withdrawing");
        applying = true;
        try {
            return applyPrepared(snapshot, displays, staticInitial, registry, gui);
        } finally {
            applying = false;
        }
    }

    private static ApplyResult applyPrepared(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays, boolean staticInitial,
                                              mezz.jei.recipes.RecipeRegistry registry, RecipesGui gui) {
        var capturedRuntime = runtime;
        var capturedIngredients = Objects.requireNonNull(ingredients);
        var capturedDesiredSource = desiredResolved;
        long capturedInvalidation = invalidationEpoch;
        var token = new RecipePoolJeiRecipe.DisplayToken();
        var wrappers = new ObjectArrayList<RecipePoolJeiRecipe>();
        var previous = generation;
        try {
            boolean initialDesired = staticInitial || matchesDesired(snapshot, displays);
            if (!initialDesired || desiredResolved != capturedDesiredSource || invalidationEpoch != capturedInvalidation || runtime != capturedRuntime || ingredients != capturedIngredients)
                return rejectGeneration("Local JEI declaration is no longer the desired server generation", capturedInvalidation, token, previous, wrappers, registry, gui);
            if (!RecipePoolJeiRegistry.INSTANCE.acceptsPools(snapshot.orderedPools()))
                return rejectGeneration("JEI replacement changed the pool set or category bindings", capturedInvalidation, token, previous, wrappers, registry, gui);
            var recipes = RecipePoolJeiRegistry.INSTANCE.ownedRecipes(snapshot.orderedPools());
            var descriptions = staticInitial && previous != null
                ? displays.snapshot(recipes, previous.displays, previous.descriptions) : displays.snapshot(recipes);
            var updates = staticInitial
                ? prepareStaticPages(RecipePoolJeiRegistry.INSTANCE.pages(), snapshot, descriptions,
                    previous == null ? null : previous.descriptions,
                    pageDataCurrent && previous != null && displays.sameProviders(previous.displays))
                : RecipePoolJeiRegistry.INSTANCE.tryPrepareUpdates(snapshot.orderedPools(), descriptions);
            if (updates == null)
                return rejectGeneration("A JEI page does not support definition updates", capturedInvalidation, token, previous, wrappers, registry, gui);
            var allDisplays = new ObjectArrayList<JeiRecipeDisplay>();
            for (var recipe : RecipePoolJeiRegistry.INSTANCE.ownedRecipes(snapshot.orderedPools())) allDisplays.add(descriptions.describe(recipe));
            var converters = IngredientConverters.getConverters();
            var identities = JeiQueryIdentityIndex.capture(converters, capturedIngredients, allDisplays);
            for (var update : updates) {
                boolean disabled = HeiRecipeGeneration.categoryDisabled(update.page().getUid());
                var category = registry.getRecipeCategory(update.page().getUid());
                if (category != update.page() && !(disabled && category == null))
                    throw new IllegalStateException("HEI category identity changed: " + update.page().getUid());
                if (disabled) continue;
                for (var recipe : update.pool().recipes()) {
                    var display = descriptions.describe(recipe);
                    var recipeIngredients = new JeiRecipeIngredients(display, converters, capturedIngredients, identities);
                    var wrapper = Objects.requireNonNull(update.createRecipe(recipe, display, recipeIngredients), "JEI page returned null wrapper");
                    validateWrapper(wrapper, update.page(), update.pool(), recipe, display, recipeIngredients);
                    wrapper.bindDisplayToken(token);
                    wrappers.add(wrapper);
                }
            }
            boolean preparedDesired = staticInitial || matchesDesired(snapshot, displays);
            if (!preparedDesired || desiredResolved != capturedDesiredSource || invalidationEpoch != capturedInvalidation || runtime != capturedRuntime || ingredients != capturedIngredients)
                return rejectGeneration("HEI runtime or desired declaration changed during preparation", capturedInvalidation, token, previous, wrappers, registry, gui);
            if (previous != null) previous.token.revoke();
            JeiRecipeInputs.reset();
            HeiRecipeGeneration.closeLayout(Minecraft.getMinecraft(), gui);
            var access = new HeiRecipeGeneration.NativeAccess(registry);
            if (!HeiRecipeGeneration.install(access, previous == null ? List.of() : previous.wrappers, wrappers)) {
                token.revoke();
                if (invalidationEpoch == capturedInvalidation) unreadyReason = "HEI did not confirm all category, focus and recipe ID mappings";
                return status();
            }
            pageDataCurrent = false;
            for (var update : updates) update.apply();
            for (var update : updates) if (update.page().pool() != update.pool()) throw new IllegalStateException("JEI page did not install its prepared pool");
            for (var wrapper : wrappers) if (!access.confirmed(wrapper, false))
                throw new IllegalStateException("HEI category or focus mappings changed after page data installation");
            var preparedGeneration = new Generation(snapshot, displays.frozenCopy(), descriptions, identities, ObjectLists.unmodifiable(wrappers), token);
            boolean finalDesired = staticInitial || matchesDesired(snapshot, displays);
            if (!finalDesired || desiredResolved != capturedDesiredSource || invalidationEpoch != capturedInvalidation || runtime != capturedRuntime || ingredients != capturedIngredients)
                return rejectGeneration("HEI runtime or desired declaration became obsolete during installation", capturedInvalidation, token, previous, wrappers, registry, gui);
            generation = preparedGeneration;
            pageDataCurrent = true;
            token.activate();
            unreadyReason = "";
            return status();
        } catch (RuntimeException | Error failure) {
            return rejectGeneration("JEI definition update failed: " + failure, capturedInvalidation, token, previous, wrappers, registry, gui);
        }
    }

    private static ApplyResult rejectGeneration(String reason, long expectedInvalidation,
                                                RecipePoolJeiRecipe.DisplayToken token, @Nullable Generation previous,
                                                List<RecipePoolJeiRecipe> wrappers, mezz.jei.recipes.RecipeRegistry registry,
                                                RecipesGui gui) {
        token.revoke();
        if (previous != null) previous.token.revoke();
        JeiRecipeInputs.reset();
        if (invalidationEpoch == expectedInvalidation) unreadyReason = reason;
        try {
            HeiRecipeGeneration.closeLayout(Minecraft.getMinecraft(), gui);
            HeiRecipeGeneration.hideAll(new HeiRecipeGeneration.NativeAccess(registry), previous == null ? List.of() : previous.wrappers, wrappers);
        } catch (RuntimeException | Error cleanupFailure) { unreadyReason += "; cleanup failed: " + cleanupFailure; }
        return status();
    }

    /** Default pages remeasure the selected snapshot; unchanged opaque static pages retain their original factory. */
    static @Nullable List<RecipePoolJeiPage.PreparedUpdate> prepareStaticPages(List<RecipePoolJeiPage> pages,
                            RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays.Snapshot descriptions,
                            @Nullable RecipeJeiDisplays.Snapshot measured, boolean currentProviders) {
        var updates = new ObjectArrayList<RecipePoolJeiPage.PreparedUpdate>();
        for (var page : pages) {
            var pool = snapshot.pools().get(page.pool().id());
            if (pool == null) return null;
            var update = page.prepareUpdate(pool, descriptions);
            if (update == null) {
                if (page.pool() != pool || measured == null || !currentProviders
                    || !descriptions.sameDescriptions(pool.recipes(), measured)) return null;
                update = new StaticPageUpdate(page, pool);
            }
            if (update.page() != page || update.pool() != pool)
                throw new IllegalArgumentException("Static JEI page prepared a foreign pool or category");
            updates.add(update);
        }
        return updates;
    }

    private record StaticPageUpdate(RecipePoolJeiPage page, RecipePool pool) implements RecipePoolJeiPage.PreparedUpdate {
        @Override public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            return page.createRecipe(recipe, display, ingredients);
        }
        @Override public void apply() {
            if (page.pool() != pool) throw new IllegalStateException("Static JEI page data changed before installation");
        }
    }

    static Definitions registrationDefinition(@Nullable Definitions resolved, Definitions baseline) {
        return resolved == null ? baseline : resolved;
    }

    private static void requireClientOwner() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) throw new IllegalStateException("JEI definition updates require the client owner");
    }

    private static boolean matchesDesired(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
        if (desiredResolved == null) return true;
        var desired = desiredResolved.get();
        return desired != null && desired.snapshot() == snapshot && desired.displays() == displays;
    }

    private static void validateWrapper(RecipePoolJeiRecipe wrapper, RecipePoolJeiPage page,
                                       RecipePool pool,
                                       MachineRecipe recipe,
                                       JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
        if (wrapper.page() != page || wrapper.pool() != pool || wrapper.recipe() != recipe || wrapper.display() != display || wrapper.ingredients() != ingredients)
            throw new IllegalArgumentException("Custom JEI wrapper must retain its category, target pool, recipe and supplied display data");
    }

    private record Generation(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays, RecipeJeiDisplays.Snapshot descriptions,
                              JeiQueryIdentityIndex identities, List<RecipePoolJeiRecipe> wrappers, RecipePoolJeiRecipe.DisplayToken token) {}

    @Override
    public void onRuntimeAvailable(IJeiRuntime available) {
        requireClientOwner();
        runtime = Objects.requireNonNull(available);
        if (runtimeReady != null) runtimeReady.run();
        else apply(registrationSnapshot, registrationDisplays, true);
        if (generation != null && !generation.token.current()) withdrawDefinitions(unreadyReason);
    }

    @Override
    public void registerIngredients(IModIngredientRegistration registration) {
        JeiEnergyIngredients.register(registration);
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        withdrawDefinitions("HEI runtime is rebuilding");
        runtime = null;
        ingredients = null;
        if (runtimeUnavailable != null) runtimeUnavailable.run();
        var resolved = desiredResolved == null ? null : desiredResolved.get();
        var initialLocal = localStaticBaseline == null ? RecipeRegistry.INSTANCE.snapshot() : null;
        var baselineSnapshot = initialLocal == null ? Objects.requireNonNull(localStaticBaseline).snapshot() : initialLocal;
        registrationSnapshot = resolved != null ? resolved.snapshot()
            : baselineSnapshot;
        var pools = registrationSnapshot.orderedPools();
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var displays = RecipeJeiDisplays.INSTANCE;
        var helper = registration.getJeiHelpers().getGuiHelper();
        JeiRecipeInputs.reset();
        pages.beginRegistration();
        displays.beginRegistration();
        MinecraftForge.EVENT_BUS.post(new RecipePoolJeiRegistrationEvent(pages, displays, helper,
            initialLocal == null ? pools : initialLocal.orderedPools()));
        displays.freeze();
        if (initialLocal != null || resolved == null)
            localStaticBaseline = new Definitions(baselineSnapshot, displays.frozenCopy());
        // Static event pages and the wrappers share this event's final registration source.
        var selected = registrationDefinition(resolved, new Definitions(baselineSnapshot, displays));
        var categoryDisplays = selected.displays();
        registrationDisplays = categoryDisplays;
        deferPoolRegistration = ExternalRecipePoolPages.requested(pools);
        postInitialDeclaration = resolved == null;
        ExternalRecipePoolPages.reset();
        if (!deferPoolRegistration) bindPoolCategories(registration, pools, categoryDisplays, Set.of(), postInitialDeclaration);
        registration.addRecipeCategories(new StructurePreviewJeiCategory(helper));
    }

    @Override
    public void register(IModRegistry registration) {
        requireClientOwner();
        ingredients = registration.getIngredientRegistry();
        registerStructures(registration);
        if (deferPoolRegistration) {
            var expected = registrationSnapshot;
            var expectedDisplays = registrationDisplays;
            ExternalRecipePoolPages.defer(registration, categoryIds -> {
                if (registrationSnapshot != expected || registrationDisplays != expectedDisplays) return;
                if (!(registration instanceof IRecipeCategoryRegistration categories))
                    throw new IllegalStateException("HEI registration does not support category definitions");
                bindPoolCategories(categories, expected.orderedPools(), expectedDisplays, categoryIds, postInitialDeclaration);
                if (registrationSnapshot == expected && registrationDisplays == expectedDisplays) registerPoolRecipes(registration);
            });
        } else registerPoolRecipes(registration);
    }

    private static void bindPoolCategories(IRecipeCategoryRegistration registration, List<RecipePool> pools,
                                          RecipeJeiDisplays displays, Set<String> categoryIds, boolean postDeclaration) {
        requireClientOwner();
        var known = new ObjectOpenHashSet<>(categoryIds);
        for (var pool : pools) {
            var target = pool.existingJeiPageId();
            if (pool.hasExistingJeiPage() && target != null && !target.isBlank()
                && Config.isRecipeCategoryDisabled(target)) known.add(target);
        }
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var helper = registration.getJeiHelpers().getGuiHelper();
        var descriptions = displays.capture(RecipePoolJeiRegistry.recipesForOwnPages(pools, known));
        pages.freeze(pools, pool -> new AutoLayoutRecipePage(pool, helper, descriptions), descriptions, known);
        registrationDescriptions = descriptions;
        for (var page : pages.pages()) registration.addRecipeCategories(page);
        if (!declarationEventPosted && postDeclaration) {
            declarationEventPosted = true;
            MinecraftForge.EVENT_BUS.post(new ClientRecipeDeclarationRegistrationEvent(ClientRecipeDeclarations.INSTANCE, registrationSnapshot, displays));
        }
    }

    private static void registerStructures(IModRegistry registration) {
        var structures = new ObjectArrayList<StructurePreviewJeiRecipe>();
        for (var machine : MachineTypeRegistry.INSTANCE.types()) {
            var controllers = new ObjectArrayList<ItemStack>();
            for (var block : MachineControllerBlocks.all()) {
                if (block.type() != machine) continue;
                var stack = new ItemStack(block);
                if (!stack.isEmpty()) {
                    controllers.add(stack);
                    registration.addRecipeCatalyst(stack.copy(), StructurePreviewJeiCategory.UID);
                }
            }
            structures.add(new StructurePreviewJeiRecipe(machine, controllers));
        }
        registration.addRecipes(structures, StructurePreviewJeiCategory.UID);
    }

    private static void registerPoolRecipes(IModRegistry registration) {
        requireClientOwner();
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var displays = registrationDisplays;
        var descriptions = Objects.requireNonNull(registrationDescriptions, "JEI category descriptions are not captured");
        ingredients = registration.getIngredientRegistry();
        var token = new RecipePoolJeiRecipe.DisplayToken();
        var allWrappers = new ObjectArrayList<RecipePoolJeiRecipe>();
        var converters = IngredientConverters.getConverters();
        var allDisplays = new ObjectArrayList<JeiRecipeDisplay>();
        for (var page : pages.pages()) {
            for (var recipe : page.pool().recipes()) {
                allDisplays.add(descriptions.describe(recipe));
            }
        }
        // Finish the global identity audit before registering any pool's wrappers.
        var identities = JeiQueryIdentityIndex.capture(converters, registration.getIngredientRegistry(), allDisplays);

        for (var page : pages.pages()) {
            if (HeiRecipeGeneration.categoryDisabled(page.getUid())) continue;
            var wrappers = new ObjectArrayList<>();
            var transfer = registration.getRecipeTransferRegistry();
            var helper = registration.getJeiHelpers().recipeTransferHandlerHelper();
            transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPatternEncodingTerm.class, helper), page.getUid());
            transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPEATerm.class, helper), page.getUid());
            for (var recipe : page.pool().recipes()) {
                var display = descriptions.describe(recipe);
                var ingredients = new JeiRecipeIngredients(display, converters, registration.getIngredientRegistry(), identities);
                var wrapper = Objects.requireNonNull(page.createRecipe(recipe, display, ingredients), "JEI page returned null wrapper");
                validateWrapper(wrapper, page, page.pool(), recipe, display, ingredients);
                wrapper.bindDisplayToken(token);
                allWrappers.add(wrapper);
                wrappers.add(wrapper);
            }
            registration.addRecipes(wrappers, page.getUid());
            for (var catalyst : pages.catalysts(page.pool().id())) {
                registration.addRecipeCatalyst(catalyst, page.getUid());
            }
            for (var machine : page.pool().machines()) {
                for (var block : MachineControllerBlocks.all()) {
                    if (block.type() != machine) continue;
                    var controller = new ItemStack(block);
                    if (!controller.isEmpty()) {
                        registration.addRecipeCatalyst(controller, page.getUid());
                    }
                }
            }
        }
        generation = new Generation(registrationSnapshot, displays.frozenCopy(), descriptions,
            identities, ObjectLists.unmodifiable(allWrappers), token);
        pageDataCurrent = true;
        registerExternalCatalysts(registration);
    }

    private static void registerExternalCatalysts(IModRegistry registration) {
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var seen = new Object2ObjectOpenHashMap<String, ReferenceOpenHashSet<MachineType>>();
        for (var binding : pages.externalPages().entrySet()) {
            var pool = registrationSnapshot.pools().get(binding.getKey());
            var uid = binding.getValue();
            for (var catalyst : pages.catalysts(pool.id())) registration.addRecipeCatalyst(catalyst, uid);
            if (StructurePreviewJeiCategory.UID.equals(uid)) continue;
            var machines = seen.computeIfAbsent(uid, _ -> new ReferenceOpenHashSet<>());
            for (var machine : pool.machines()) {
                if (!machines.add(machine)) continue;
                for (var block : MachineControllerBlocks.all()) {
                    if (block.type() != machine) continue;
                    var stack = new ItemStack(block);
                    if (!stack.isEmpty()) registration.addRecipeCatalyst(stack, uid);
                }
            }
        }
    }
}
