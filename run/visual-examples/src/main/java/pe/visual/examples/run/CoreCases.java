package pe.visual.examples.run;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.*;
import com.circulation.prototype_eng.api.machine.component.MachineScheduling;
import com.circulation.prototype_eng.api.machine.lifecycle.*;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.*;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.scanning.*;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.integration.crafttweaker.ZenMachineFactory;
import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentData;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.ZenHatchConfiguration;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.*;
import com.circulation.prototype_eng.integration.crafttweaker.structure.*;
import crafttweaker.annotations.ZenRegister;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.*;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

import pe.visual.examples.ExampleText;

/**
 * Run-only executable script fixtures. These detached owners do not claim World/save/sync coverage.
 */
@ZenRegister
@ZenClass("mods.pe_run_tests.CoreCases")
@SuppressWarnings("unused")
public final class CoreCases {
    static final RunKeyType MATERIAL_TYPE = new RunKeyType("core_material_type");
    static final RunKeyType SECONDARY_TYPE = new RunKeyType("core_secondary_type");
    static final RunKey MATERIAL = new RunKey(MATERIAL_TYPE, "core_material");
    static final RunKey PRODUCT = new RunKey(MATERIAL_TYPE, "core_product");
    static final RunKey SECONDARY = new RunKey(SECONDARY_TYPE, "core_secondary");
    static final Object2ObjectLinkedOpenHashMap<String, ZenRecipe> RECIPES = new Object2ObjectLinkedOpenHashMap<>();
    static final Object2ObjectLinkedOpenHashMap<String, ZenOverlayModifier> OVERLAYS = new Object2ObjectLinkedOpenHashMap<>();
    static final ObjectArrayList<ZenRecipePool> POOLS = new ObjectArrayList<>();
    private static final ObjectArrayList<ZenRecipe> HISTORY = new ObjectArrayList<>();
    static final ObjectArrayList<ZenHatchConfiguration> HATCHES = new ObjectArrayList<>();
    private static ZenRecipeBindings names;
    static ZenStructure shape;
    static ZenStructureQuery query;
    static ZenStructurePredicate predicate;
    static Thread structureOwner;
    private static int visits;
    private static int customPoolLookups;

    static {
        ae2.api.stacks.AEKeyTypes.register(MATERIAL_TYPE);
        ae2.api.stacks.AEKeyTypes.register(SECONDARY_TYPE);
    }

    private CoreCases() {
    }

    static ResourceLocation id(String path) {
        return new ResourceLocation("pe_run_tests", path);
    }

    @ZenMethod
    public static ZenResource material(long amount) {
        return new ZenResource(MATERIAL, amount);
    }

    @ZenMethod
    public static ZenResource product(long amount) {
        return new ZenResource(PRODUCT, amount);
    }

    @ZenMethod
    public static ZenResource secondary(long amount) {
        return new ZenResource(SECONDARY, amount);
    }

    @ZenMethod
    public static void bindings(ZenRecipeBindings value) {
        names = Objects.requireNonNull(value);
    }

    @ZenMethod
    public static void checkBindings(ZenRecipeBindings value) {
        NativeChecks.check("core_cross_loader_scope", value == names);
    }

    /**
     * SDK718 reserves has; this run-only alias calls the existing actual data API.
     */
    @ZenMethod
    public static boolean dataContains(ZenComponentData value, String key) {
        return Objects.requireNonNull(value).has(key);
    }

    @ZenMethod
    public static void matchers(ZenMatcher old, ZenMatcher next) {
        NativeChecks.check("core_frozen_matcher", old.value().matches(MATERIAL, MATERIAL)
            && !next.value().matches(MATERIAL, MATERIAL));
    }

    @ZenMethod
    public static void capture(String name, ZenRecipe recipe) {
        if (RECIPES.putIfAbsent(name, recipe) != null)
            throw new IllegalArgumentException("Duplicate core case: " + name);
    }

    @ZenMethod
    public static void overlay(String name, ZenOverlayModifier value) {
        OVERLAYS.put(name, value);
    }

    @ZenMethod
    public static void pool(ZenRecipePool value) {
        POOLS.add(value);
    }

    @ZenMethod
    public static void historical(ZenRecipe value) {
        HISTORY.add(value);
    }

    @ZenMethod
    public static void registerAll() {
        Objects.requireNonNull(names).registerPools(collectedPools(), HISTORY.toArray(ZenRecipe[]::new));
    }

    public static ZenRecipePool[] collectedPools() {
        return POOLS.toArray(ZenRecipePool[]::new);
    }

    public static ZenRecipe[] collectedRecipes() {
        return RECIPES.values().toArray(ZenRecipe[]::new);
    }

    @ZenMethod
    public static ZenRecipe[] allRecipes() {
        return collectedRecipes();
    }

    @ZenMethod
    public static void hatch(ZenHatchConfiguration value) {
        HATCHES.add(value);
    }

    @ZenMethod
    public static void shape(ZenStructure value) {
        shape = value;
    }

    @ZenMethod
    public static void query(ZenStructureQuery value) {
        query = value;
    }

    @ZenMethod
    public static void predicate(ZenStructurePredicate value) {
        predicate = value;
    }

    @ZenMethod
    public static void worldQuery(ZenStructureQuery value) {
        CoreWorldChecks.query = value;
    }

    @ZenMethod
    public static void worldShape(ZenStructure value) {
        CoreWorldChecks.shape = value;
    }

    @ZenMethod
    public static void worldPredicate(ZenStructurePredicate value) {
        CoreWorldChecks.predicate = value;
    }

    @ZenMethod
    public static void observeWorld(crafttweaker.api.world.IWorld world, crafttweaker.api.world.IBlockPos position, int light) {
        CoreWorldChecks.observe(world, position, light);
    }

    @ZenMethod
    public static void observeWorldValidation(ZenStructureFacts facts) {
        CoreWorldChecks.validation(facts);
    }

    public static String worldSample(World world, BlockPos position, BlockOrientation orientation, boolean expectedMatch) {
        return CoreWorldChecks.run(world, position, orientation, expectedMatch);
    }

    public static String worldStats() {
        return CoreWorldChecks.stats();
    }

    public static BlockPos2ObjectMap<net.minecraft.block.Block> worldPlacement() {
        return CoreWorldChecks.placement();
    }

    @ZenMethod
    public static void customVisited() {
        if (Thread.currentThread() == structureOwner) throw new IllegalStateException("Validator ran on capture owner");
        visits++;
    }

    @ZenMethod
    public static int customVisits() {
        return visits;
    }

    static ZenRecipe recipe(String name) {
        return Objects.requireNonNull(RECIPES.get(name), "Missing core script case " + name);
    }

    @ZenMethod
    public static ZenMachineFactory factory() {
        return new ZenMachineFactory(AddonOwner::new);
    }

    @ZenMethod
    public static ZenRequirement externalRequirement() {
        return CoreRecipeChecks.externalRequirement();
    }

    @ZenMethod
    public static ZenRequirementOverlay externalOverlay() {
        return CoreRecipeChecks.externalOverlay();
    }

    @ZenMethod
    public static ZenRecipe externalRecipe() {
        return CoreRecipeChecks.externalRecipe();
    }

    @ZenMethod
    public static ZenRecipePool customPool() {
        var recipe = externalRecipe();
        return ZenRecipePool.of(new AddonPool(List.of(recipe.recipe())), new ZenRecipe[]{recipe});
    }

    /**
     * Invoked only after normal runtime publication (or the standalone explicit registration fixture).
     */
    public static void runRegisteredChecks() {
        CoreRecipeChecks.run();
        try {
            CoreStructureChecks.run();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Core frozen-structure check interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException |
                 java.io.IOException failure) {
            throw new IllegalStateException("Core frozen-structure check failed", failure);
        }
        owners();
        hatchIsolation();
    }

    private static void owners() {
        var registry = MachineTypeRegistry.INSTANCE;
        for (var path : List.of("core_thread", "core_cross", "core_addon", "core_fixed")) {
            var type = Objects.requireNonNull(registry.get(id(path)));
            var assembly = registry.assembly(type);
            var owner = assembly.create(new Host(type), new MachineLifecycleRegistry());
            var buffers = CoreRecipeChecks.buffers(100, 100);
            Objects.requireNonNull(owner.lifecycle()).form(buffers.hatches());
            var other = assembly.create(new Host(type), new MachineLifecycleRegistry());
            Objects.requireNonNull(other.lifecycle()).form(CoreRecipeChecks.buffers(100, 100).hatches());
            NativeChecks.check("core_two_owners_" + path, owner != other && owner.recipeScanners().getFirst() != other.recipeScanners().getFirst());
            var scan = owner.recipeScanners().getFirst().beginScan(new ScanLimits(8, 8));
            int steps = 0;
            RecipeScanner.Result scanResult = null;
            while (!scan.finished() && steps++ < 10000) scanResult = advanceScan(scan);
            var work = Objects.requireNonNull(owner.components().get(MachineWorkSlots.TYPE));
            org.apache.logging.log4j.LogManager.getLogger("pe_run_tests").info("CHECK core_owner_scan_{} finished={} steps={} result={} active={} input={} energy={}",
                path, scan.finished(), steps, scanResult, work.activeProcesses().size(), buffers.input().input().amount(MATERIAL), buffers.energyIn().storedEnergy());
            NativeChecks.check("core_owner_scan_" + path, scan.finished() && steps > 1 && !work.activeProcesses().isEmpty());
            var process = work.activeProcesses().getFirst();
            NativeChecks.check("core_scan_probe_no_payment_" + path, buffers.input().input().amount(MATERIAL) == 100
                && buffers.energyIn().storedEnergy() == 100);
            if (path.equals("core_addon")) {
                NativeChecks.check("core_addon_factory_custom_pool", owner instanceof AddonOwner addon && addon.creations > 0 && customPoolLookups > 0);
                NativeChecks.check("core_addon_execution", Objects.requireNonNull(owner.components().get(MachineScheduling.TYPE)).mode() == ExecutionMode.MAIN_THREAD);
            } else {
                NativeChecks.check("core_factory_" + path, path.equals("core_cross") ? owner instanceof CrossRecipeController : owner instanceof MultithreadController);
                int expected = path.equals("core_fixed") ? 2 : 5;
                NativeChecks.check("core_parallelism_final_" + path, RecipeParallelism.effective(process) == expected);
                NativeChecks.check("core_parallelism_payment_" + path, process.tick() == com.circulation.prototype_eng.api.recipe.process.ProcessResult.SUCCESS
                    && buffers.input().input().amount(MATERIAL) == 100 - expected
                    && buffers.energyIn().storedEnergy() == (path.equals("core_fixed") ? 100 : 90));
                NativeChecks.check("core_owner_data_" + path, owner.components().get(DataComponents.MACHINE) != other.components().get(DataComponents.MACHINE)
                    && process.components().get(DataComponents.PROCESS) != null);
            }
            var saved = assembly.encode(owner);
            var restored = assembly.restore(new Host(type), saved, new MachineLifecycleRegistry());
            var retained = Objects.requireNonNull(restored.components().get(MachineWorkSlots.TYPE)).activeProcesses().getFirst();
            NativeChecks.check("core_detached_codec_restore_" + path, restored.getClass() == owner.getClass()
                && retained != process && retained.recipe() == process.recipe() && RecipeParallelism.effective(retained) == RecipeParallelism.effective(process));
            if (!path.equals("core_addon")) {
                int expected = path.equals("core_fixed") ? 2 : 5;
                boolean completed = true;
                for (int tick = 1; tick < 10; tick++)
                    completed &= process.tick() == com.circulation.prototype_eng.api.recipe.process.ProcessResult.SUCCESS;
                NativeChecks.check("core_parallelism_end_output_" + path, completed && process.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED
                    && buffers.output().output().amount(PRODUCT) == expected);
                var restoredBuffers = CoreRecipeChecks.buffers(0, 0);
                Objects.requireNonNull(restored.lifecycle()).form(restoredBuffers.hatches());
                boolean restoredCompleted = true;
                for (int tick = 1; tick < 10; tick++)
                    restoredCompleted &= retained.tick() == com.circulation.prototype_eng.api.recipe.process.ProcessResult.SUCCESS;
                NativeChecks.check("core_restored_end_output_" + path, restoredCompleted && retained.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED
                    && restoredBuffers.output().output().amount(PRODUCT) == expected);
            }
        }
        // Final selection can exceed baseline but still needs full START costs.
        var type = Objects.requireNonNull(registry.get(id("core_thread")));
        var owner = registry.assembly(type).create(new Host(type), new MachineLifecycleRegistry());
        var insufficient = CoreRecipeChecks.buffers(4, 100);
        Objects.requireNonNull(owner.lifecycle()).form(insufficient.hatches());
        var scan = owner.recipeScanners().getFirst().beginScan(new ScanLimits(8, 8));
        int steps = 0;
        while (!scan.finished() && steps++ < 10000) advanceScan(scan);
        NativeChecks.check("core_parallelism_reject_unpaid", scan.finished()
            && Objects.requireNonNull(owner.components().get(MachineWorkSlots.TYPE)).activeProcesses().isEmpty()
            && insufficient.input().input().amount(MATERIAL) == 4 && insufficient.energyIn().storedEnergy() == 100);
    }

    private static RecipeScanner.Result advanceScan(RecipeScanner.ScanAttempt scan) {
        var budget = new RecipeWorkBudget(1);
        for (var source : scan.sources()) {
            if (scan.resolvingSources()) scan.finishSourceResolution(source, source.resolveWithinBudget(budget));
            else if (source.state() == com.circulation.prototype_eng.api.storage.ResourceBatch.SourceState.RESERVED)
                source.applyWithinBudget(budget);
        }
        var computation = scan.computation();
        if (computation != null && computation.advance(budget)) scan.finishComputation(computation);
        return scan.advance(budget, () -> true);
    }

    private static void hatchIsolation() {
        var resources = (com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration) HATCHES.get(0).value().configuration();
        var buffer = new com.circulation.prototype_eng.storage.BufferedResourceHatch(resources, () -> {
        });
        long first = Objects.requireNonNull(buffer.input()).insert(MATERIAL, 150, ae2.api.config.Actionable.MODULATE);
        long second = Objects.requireNonNull(buffer.output()).insert(MATERIAL, 350, ae2.api.config.Actionable.MODULATE);
        NativeChecks.check("core_hatch_independent_sides", first == 150 && second == 350 && buffer.input().amount(MATERIAL) == 150 && buffer.output().amount(MATERIAL) == 350);
        NativeChecks.check("core_hatch_multiple_types", buffer.output().insert(SECONDARY, 500, ae2.api.config.Actionable.MODULATE) == 400
            && buffer.output().amount(SECONDARY) == 400 && buffer.output().amount(MATERIAL) == 350 && buffer.input().amount(SECONDARY) == 0);
        var energy = (com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration) HATCHES.get(1).value().configuration();
        var in = new com.circulation.prototype_eng.energy.EnergyBuffer(energy.input().capacity(), energy.input().transfer(), energy.input().transfer(), () -> {
        });
        var out = new com.circulation.prototype_eng.energy.EnergyBuffer(energy.output().capacity(), energy.output().transfer(), energy.output().transfer(), () -> {
        });
        NativeChecks.check("core_hatch_fe_transfer", in.receiveEnergy(1500, ae2.api.config.Actionable.MODULATE) == 100 && out.receiveEnergy(2500, ae2.api.config.Actionable.MODULATE) == 200);
        for (int i = 0; i < 9; i++) {
            in.receiveEnergy(1500, ae2.api.config.Actionable.MODULATE);
            out.receiveEnergy(2500, ae2.api.config.Actionable.MODULATE);
        }
        NativeChecks.check("core_hatch_fe_capacities", in.storedEnergy() == 1000 && out.storedEnergy() == 2000
            && in.receiveEnergy(1, ae2.api.config.Actionable.MODULATE) == 0 && out.receiveEnergy(1, ae2.api.config.Actionable.MODULATE) == 0);
    }

    private static final class AddonOwner extends MultithreadController {
        private int creations;

        private AddonOwner(MachineType type) {
            super(type);
        }

        @Override
        public RecipeScanner createRecipeScanner(MachineWorkSlots host, RecipeCatalog catalog, LongSupplier seeds) {
            return super.createRecipeScanner(host, catalog, seeds).decorateProcessFactory(delegate -> new RecipeProcessFactory() {
                @Override
                public RecipeProcess create(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                    throw new IllegalStateException("Cooperative addon scanner bypassed beginCreation");
                }

                @Override
                public Creation beginCreation(MachineInstance owner, MachineRecipe recipe, long seed, ProbabilityTuning tuning) {
                    creations++;
                    return delegate.beginCreation(owner, recipe, seed, tuning);
                }
            }, false);
        }
    }

    /**
     * Actual addon pool keeps canonical handles and runs its own input-selection lookup.
     */
    private record AddonPool(List<MachineRecipe> recipes, List<MachineType> machines) implements RecipePool {
        private AddonPool(List<MachineRecipe> recipes) {
            this(recipes, List.of());
        }

        @Override
        public ResourceLocation id() {
            return CoreCases.id("core_custom_pool");
        }

        @Override
        public String translationKey() {
            return ExampleText.CoreAddonPool.getTranslationKey();
        }

        @Override
        public RecipePool freeze(List<MachineRecipe> recipes, List<MachineType> machines) {
            return new AddonPool(recipes, machines);
        }

        @Override
        public RecipeCandidateIndex.Cursor beginCandidates(MachineInstance owner, java.util.Set<ResourceLocation> groups) {
            return ((RecipeCandidateIndex) (machine, requested, scope) -> {
                customPoolLookups++;
                return scope;
            }).beginLookup(owner, groups, recipes);
        }
    }

    private record Host(MachineType type) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type.id(), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new IllegalStateException("Detached core fixture must not access World");
        }

        @Override
        public void requireServerThread() {
        }
    }

    /**
     * Real run-only AE key types exercise identity and multiple type routing without MC bootstrap.
     */
    private static final class RunKeyType extends AEKeyType {
        private RunKeyType(String path) {
            super(id(path), RunKey.class, new TextComponentString(path));
        }

        @Override
        public AEKey readFromPacket(PacketBuffer input) {
            return new RunKey(this, input.readString(128));
        }

        @Override
        public AEKey loadKeyFromTag(NBTTagCompound tag) {
            return new RunKey(this, tag.getString("name"));
        }
    }

    private static final class RunKey extends AEKey {
        private final RunKeyType type;
        private final String name;

        private RunKey(RunKeyType type, String name) {
            this.type = type;
            this.name = name;
        }

        @Override
        public AEKeyType getType() {
            return type;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public Object getPrimaryKey() {
            return name;
        }

        @Override
        public ResourceLocation getId() {
            return id(name);
        }

        @Override
        public Object getReadOnlyStack() {
            return this;
        }

        @Override
        protected ITextComponent computeDisplayName() {
            return new TextComponentString(name);
        }

        @Override
        public NBTBase get(String componentId) {
            return null;
        }

        @Override
        public void writeToPacket(PacketBuffer data) {
            data.writeString(name);
        }

        @Override
        public NBTTagCompound toTag() {
            var tag = new NBTTagCompound();
            tag.setString("name", name);
            return tag;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof RunKey other && type == other.type && name.equals(other.name);
        }

        @Override
        public int hashCode() {
            return 31 * type.hashCode() + name.hashCode();
        }
    }
}
