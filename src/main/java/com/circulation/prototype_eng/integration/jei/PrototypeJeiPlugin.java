package com.circulation.prototype_eng.integration.jei;

import ae2.api.integrations.hei.IngredientConverters;
import ae2.container.me.patternencode.ContainerPEATerm;
import ae2.container.me.patternencode.ContainerPatternEncodingTerm;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.JeiEnergyIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiQueryIdentityIndex;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistry;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.block.MachineControllerBlocks;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;
import mezz.jei.api.ingredients.IIngredientRegistry;
import mezz.jei.api.ingredients.IModIngredientRegistration;
import mezz.jei.api.recipe.IRecipeCategoryRegistration;
import mezz.jei.config.Config;
import mezz.jei.gui.recipes.RecipesGui;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * HEI 4.35.1 discovers this entry on the client; common mod initialization never references it.
 */
@JEIPlugin
@SideOnly(Side.CLIENT)
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("unused") // Discovered by HEI's @JEIPlugin scan on the client.
public class PrototypeJeiPlugin implements IModPlugin {
    @Nullable
    private static IJeiRuntime runtime;
    @Nullable
    private static IIngredientRegistry ingredients;
    @Nullable
    private static Generation generation;
    @Nullable
    private static Supplier<Definitions> desiredResolved;
    @Nullable
    private static Runnable runtimeReady;
    @Nullable
    private static Runnable runtimeUnavailable;
    private static RecipeRegistry.Snapshot registrationSnapshot;
    private static RecipeJeiDisplays registrationDisplays;
    private static RecipeJeiDisplays.Snapshot registrationDescriptions;
    private static ComputerRecipeJeiRegistration computerRecipes;
    private static boolean pageDataCurrent;
    @Nullable
    private static Definitions localStaticBaseline;
    private static boolean declarationEventPosted;
    private static boolean applying;
    private static boolean withdrawing;
    private static long invalidationEpoch;
    private static String unreadyReason = "HEI runtime is not available";
    private boolean deferPoolRegistration;
    private boolean postInitialDeclaration;

    public static void configureDefinitions(Supplier<Definitions> resolved, Runnable available) {
        configureDefinitions(resolved, available, () -> {
        });
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
            if (capturedRuntime.getRecipesGui() instanceof RecipesGui gui)
                HeiRecipeGeneration.closeLayout(Minecraft.getMinecraft(), gui);
            if (capturedRuntime.getRecipeRegistry() instanceof mezz.jei.recipes.RecipeRegistry registry)
                HeiRecipeGeneration.hideAll(new HeiRecipeGeneration.NativeAccess(registry), capturedGeneration.wrappers, List.of());
        } finally {
            withdrawing = false;
        }
    }

    /**
     * A complete display generation is prepared before the first native mutation.
     */
    public static ApplyResult applyDefinitions(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
        return apply(snapshot, displays, false);
    }

    /**
     * Local legacy view only; this does not establish a matching remote declaration.
     */
    public static ApplyResult showLocalStaticDefinitions() {
        var baseline = localStaticBaseline;
        if (baseline == null)
            return new ApplyResult(false, runtime == null, "Local static recipe baseline is not captured");
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
        if (applying || withdrawing)
            return new ApplyResult(false, false, "A JEI definition update is already applying or withdrawing");
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

            var elements5 = RecipePoolJeiRegistry.INSTANCE.ownedRecipes(snapshot.orderedPools());
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                var recipe = elements5.get(index5);
                allDisplays.add(descriptions.describe(recipe));
            }

            allDisplays.addAll(computerRecipes.displays());
            var converters = IngredientConverters.getConverters();
            var identities = JeiQueryIdentityIndex.capture(converters, capturedIngredients, allDisplays);
            for (int index12 = 0; index12 < updates.size(); index12++) {
                var update = updates.get(index12);
                boolean disabled = HeiRecipeGeneration.categoryDisabled(update.page().getUid());
                var category = registry.getRecipeCategory(update.page().getUid());
                if (category != update.page() && !(disabled && category == null))
                    throw new IllegalStateException("HEI category identity changed: " + update.page().getUid());
                if (disabled) continue;
                var elements18 = update.pool().recipes();
                for (int index18 = 0; index18 < elements18.size(); index18++) {
                    var recipe = elements18.get(index18);
                    var display = descriptions.describe(recipe);
                    var recipeIngredients = new JeiRecipeIngredients(display, converters, capturedIngredients, identities);
                    var wrapper = Objects.requireNonNull(update.createRecipe(recipe, display, recipeIngredients), "JEI page returned null wrapper");
                    validateWrapper(wrapper, update.page(), update.pool(), recipe, display, recipeIngredients);
                    wrapper.bindDisplayToken(token);
                    wrappers.add(wrapper);
                }
            }
            wrappers.addAll(computerRecipes.createWrappers(converters, capturedIngredients, identities, token));
            boolean preparedDesired = staticInitial || matchesDesired(snapshot, displays);
            if (!preparedDesired || desiredResolved != capturedDesiredSource || invalidationEpoch != capturedInvalidation || runtime != capturedRuntime || ingredients != capturedIngredients)
                return rejectGeneration("HEI runtime or desired declaration changed during preparation", capturedInvalidation, token, previous, wrappers, registry, gui);
            if (previous != null) previous.token.revoke();
            JeiRecipeInputs.reset();
            HeiRecipeGeneration.closeLayout(Minecraft.getMinecraft(), gui);
            var access = new HeiRecipeGeneration.NativeAccess(registry);
            if (!HeiRecipeGeneration.install(access, previous == null ? List.of() : previous.wrappers, wrappers)) {
                token.revoke();
                if (invalidationEpoch == capturedInvalidation)
                    unreadyReason = "HEI did not confirm all category, focus and recipe ID mappings";
                return status();
            }
            pageDataCurrent = false;
            for (int index = 0; index < updates.size(); index++) {
                var update = updates.get(index);
                update.apply();
            }
            for (int index7 = 0; index7 < updates.size(); index7++) {
                var update = updates.get(index7);
                if (update.page().pool() != update.pool())
                    throw new IllegalStateException("JEI page did not install its prepared pool");
            }
            for (int index8 = 0; index8 < wrappers.size(); index8++) {
                var wrapper = wrappers.get(index8);
                if (!access.confirmed(wrapper, false))
                    throw new IllegalStateException("HEI category or focus mappings changed after page data installation");
            }
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
        } catch (RuntimeException | Error cleanupFailure) {
            unreadyReason += "; cleanup failed: " + cleanupFailure;
        }
        return status();
    }

    /**
     * Default pages remeasure the selected snapshot; unchanged opaque static pages retain their original factory.
     */
    static @Nullable List<RecipePoolJeiPage.PreparedUpdate> prepareStaticPages(List<RecipePoolJeiPage> pages,
                                                                               RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays.Snapshot descriptions,
                                                                               @Nullable RecipeJeiDisplays.Snapshot measured, boolean currentProviders) {
        var updates = new ObjectArrayList<RecipePoolJeiPage.PreparedUpdate>(pages.size());
        for (int index19 = 0; index19 < pages.size(); index19++) {
            var page = pages.get(index19);
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

    static Definitions registrationDefinition(@Nullable Definitions resolved, Definitions baseline) {
        return resolved == null ? baseline : resolved;
    }

    private static void requireClientOwner() {
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread())
            throw new IllegalStateException("JEI definition updates require the client owner");
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

    private static void bindPoolCategories(IRecipeCategoryRegistration registration, List<RecipePool> pools,
                                           RecipeJeiDisplays displays, Set<String> categoryIds, boolean postDeclaration) {
        requireClientOwner();
        var known = new ObjectOpenHashSet<>(categoryIds);
        for (int index14 = 0; index14 < pools.size(); index14++) {
            var pool = pools.get(index14);
            var target = pool.existingJeiPageId();
            if (pool.hasExistingJeiPage() && target != null && !target.isBlank()
                && Config.isRecipeCategoryDisabled(target)) known.add(target);
        }
        var pages = RecipePoolJeiRegistry.INSTANCE;
        var helper = registration.getJeiHelpers().getGuiHelper();
        var descriptions = displays.capture(RecipePoolJeiRegistry.recipesForOwnPages(pools, known));
        pages.freeze(pools, pool -> pool instanceof SimpleRecipePool simple
            ? new SingleInputRecipePage(simple, helper, descriptions, pages.renderOptions(pool.id()))
            : new AutoLayoutRecipePage(pool, helper, descriptions, pages.renderOptions(pool.id())), descriptions, known);
        registrationDescriptions = descriptions;

        var elements = pages.pages();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var page = elements.get(index2);
            registration.addRecipeCategories(page);
        }

        if (!declarationEventPosted && postDeclaration) {
            declarationEventPosted = true;
            MinecraftForge.EVENT_BUS.post(new ClientRecipeDeclarationRegistrationEvent(ClientRecipeDeclarations.INSTANCE, registrationSnapshot, displays));
        }
    }

    private static void registerStructures(IModRegistry registration) {
        var transfer = registration.getRecipeTransferRegistry();
        var helper = registration.getJeiHelpers().recipeTransferHandlerHelper();
        transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPatternEncodingTerm.class, helper), StructurePreviewJeiCategory.UID);
        transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPEATerm.class, helper), StructurePreviewJeiCategory.UID);
        var structures = new ObjectArrayList<StructurePreviewJeiRecipe>();
        var elements20 = MachineTypeRegistry.INSTANCE.types();
        for (int index20 = 0; index20 < elements20.size(); index20++) {
            var machine = elements20.get(index20);
            var controllers = new ObjectArrayList<ItemStack>();

            var elements11 = MachineControllerBlocks.all();
            for (int index11 = 0; index11 < elements11.size(); index11++) {
                var block = elements11.get(index11);
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

        var elements6 = pages.pages();
        for (int index6 = 0; index6 < elements6.size(); index6++) {
            var page = elements6.get(index6);
            var elements1 = page.pool().recipes();
            for (int index1 = 0; index1 < elements1.size(); index1++) {
                var recipe = elements1.get(index1);
                allDisplays.add(descriptions.describe(recipe));
            }
        }

        allDisplays.addAll(computerRecipes.displays());
        // Finish the global identity audit before registering any pool's wrappers.
        var identities = JeiQueryIdentityIndex.capture(converters, registration.getIngredientRegistry(), allDisplays);


        var elements13 = pages.pages();
        for (int index13 = 0; index13 < elements13.size(); index13++) {
            var page = elements13.get(index13);
            if (HeiRecipeGeneration.categoryDisabled(page.getUid())) continue;
            var wrappers = new ObjectArrayList<>();
            var transfer = registration.getRecipeTransferRegistry();
            var helper = registration.getJeiHelpers().recipeTransferHandlerHelper();
            transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPatternEncodingTerm.class, helper), page.getUid());
            transfer.addRecipeTransferHandler(new PatternRecipeTransfer<>(ContainerPEATerm.class, helper), page.getUid());
            var elements17 = page.pool().recipes();
            for (int index17 = 0; index17 < elements17.size(); index17++) {
                var recipe = elements17.get(index17);
                var display = descriptions.describe(recipe);
                var ingredients = new JeiRecipeIngredients(display, converters, registration.getIngredientRegistry(), identities);
                var wrapper = Objects.requireNonNull(page.createRecipe(recipe, display, ingredients), "JEI page returned null wrapper");
                validateWrapper(wrapper, page, page.pool(), recipe, display, ingredients);
                wrapper.bindDisplayToken(token);
                allWrappers.add(wrapper);
                wrappers.add(wrapper);
            }
            registration.addRecipes(wrappers, page.getUid());

            var elements4 = pages.catalysts(page.pool().id());
            for (int index4 = 0; index4 < elements4.size(); index4++) {
                var catalyst = elements4.get(index4);
                registration.addRecipeCatalyst(catalyst, page.getUid());
            }

            var elements16 = page.pool().machines();
            for (int index16 = 0; index16 < elements16.size(); index16++) {
                var machine = elements16.get(index16);
                var elements10 = MachineControllerBlocks.all();
                for (int index10 = 0; index10 < elements10.size(); index10++) {
                    var block = elements10.get(index10);
                    if (block.type() != machine) continue;
                    var controller = new ItemStack(block);
                    if (!controller.isEmpty()) {
                        registration.addRecipeCatalyst(controller, page.getUid());
                    }
                }
            }
        }

        allWrappers.addAll(computerRecipes.registerRecipes(registration, converters, identities, token));
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

            var elements3 = pages.catalysts(pool.id());
            for (int index3 = 0; index3 < elements3.size(); index3++) {
                var catalyst = elements3.get(index3);
                registration.addRecipeCatalyst(catalyst, uid);
            }

            if (StructurePreviewJeiCategory.UID.equals(uid)) continue;
            var machines = seen.computeIfAbsent(uid, _ -> new ReferenceOpenHashSet<>());
            var elements15 = pool.machines();
            for (int index15 = 0; index15 < elements15.size(); index15++) {
                var machine = elements15.get(index15);
                if (!machines.add(machine)) continue;

                var elements9 = MachineControllerBlocks.all();
                for (int index9 = 0; index9 < elements9.size(); index9++) {
                    var block = elements9.get(index9);
                    if (block.type() != machine) continue;
                    var stack = new ItemStack(block);
                    if (!stack.isEmpty()) registration.addRecipeCatalyst(stack, uid);
                }
            }
        }
    }

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
        if (!deferPoolRegistration)
            bindPoolCategories(registration, pools, categoryDisplays, Set.of(), postInitialDeclaration);
        computerRecipes = new ComputerRecipeJeiRegistration(registration, categoryDisplays);
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
                if (registrationSnapshot == expected && registrationDisplays == expectedDisplays)
                    registerPoolRecipes(registration);
            });
        } else registerPoolRecipes(registration);
    }

    public record Definitions(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays) {
        public Definitions {
            Objects.requireNonNull(snapshot);
            Objects.requireNonNull(displays);
        }
    }

    public record ApplyResult(boolean ready, boolean pendingRuntime, String reason) {
    }

    private record StaticPageUpdate(RecipePoolJeiPage page,
                                    RecipePool pool) implements RecipePoolJeiPage.PreparedUpdate {
        @Override
        public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            return page.createRecipe(recipe, display, ingredients);
        }

        @Override
        public void apply() {
            if (page.pool() != pool)
                throw new IllegalStateException("Static JEI page data changed before installation");
        }
    }

    private record Generation(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays displays,
                              RecipeJeiDisplays.Snapshot descriptions,
                              JeiQueryIdentityIndex identities, List<RecipePoolJeiRecipe> wrappers,
                              RecipePoolJeiRecipe.DisplayToken token) {
    }
}
