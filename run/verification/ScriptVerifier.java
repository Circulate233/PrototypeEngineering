package pe.run.verification;

import com.circulation.prototype_eng.api.machine.*;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.recipe.*;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.api.structure.json.*;
import com.circulation.prototype_eng.integration.crafttweaker.*;
import com.circulation.prototype_eng.integration.crafttweaker.hatch.*;
import com.circulation.prototype_eng.integration.crafttweaker.jei.*;
import com.circulation.prototype_eng.integration.crafttweaker.network.*;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.*;
import com.circulation.prototype_eng.integration.crafttweaker.render.*;
import com.circulation.prototype_eng.integration.crafttweaker.research.*;
import com.circulation.prototype_eng.integration.crafttweaker.resource.*;
import com.circulation.prototype_eng.integration.crafttweaker.structure.*;
import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import com.google.gson.GsonBuilder;
import crafttweaker.api.data.IData;
import crafttweaker.api.item.IItemStack;
import crafttweaker.api.liquid.ILiquidStack;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.world.IWorld;
import crafttweaker.api.world.IBlockPos;
import crafttweaker.zenscript.expand.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import pe.visual.examples.run.CoreCases;
import pe.visual.examples.ExampleText;
import pe.visual.examples.run.NativeChecks;
import pe.visual.examples.run.RunNetworkRender;
import stanhebben.zenscript.*;
import stanhebben.zenscript.compiler.IEnvironmentGlobal;
import stanhebben.zenscript.expression.ExpressionCallStatic;
import stanhebben.zenscript.expression.ExpressionString;
import stanhebben.zenscript.expression.partial.IPartialExpression;
import stanhebben.zenscript.impl.*;
import stanhebben.zenscript.parser.Token;
import stanhebben.zenscript.symbols.IZenSymbol;
import stanhebben.zenscript.symbols.SymbolType;
import stanhebben.zenscript.type.ZenTypeNative;
import stanhebben.zenscript.type.natives.JavaMethod;
import stanhebben.zenscript.util.ZenPosition;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A separate process: SDK Parser/Compiler and actual core API behavior, without Minecraft/FML bootstrap.
 */
public final class ScriptVerifier {
    private final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    private final GenericCompileEnvironment environment = new GenericCompileEnvironment();
    private final GenericRegistry registry = new GenericRegistry(environment,
        new GenericErrorLogger(new PrintStream(diagnostics, true, StandardCharsets.UTF_8)));

    private ScriptVerifier() {
        // Load the SDK's actual IData and five expansions before any primitive caster query.
        registry.registerNativeClass(IData.class);
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        // A standalone JVM has no Mixin transformer. This is the same production member helper.
        var dataType = new WritableDataType();
        registry.getTypes().getTypeMap().put(ZenComponentData.class, dataType);
        dataType.complete(registry.getTypes());
        registry.getRoot().put(dataType.getName(), new SymbolType(dataType), registry.getErrorLogger());
        for (var type : List.of(
            IItemStack.class, ILiquidStack.class, IWorld.class, IBlockPos.class,
            crafttweaker.api.player.IPlayer.class, crafttweaker.api.server.IServer.class,
            ZenMachineType.class, ZenMachineFactory.class, ZenMachineComponent.class, ZenParallelController.class,
            ZenResource.class, ZenResourceMatcher.class, ZenMatcher.class, ZenRequirement.class,
            ZenRecipe.class, ZenRecipePool.class, ZenRecipeBindings.class,
            ZenSelectiveModifier.class, ZenSelectiveContext.class, ZenSelectiveCallback.class,
            ZenOverlayModifier.class, ZenOverlayContext.class, ZenOverlayCallback.class, ZenRequirementOverlay.class,
            ZenInputAllocation.class, ZenAllocationCallback.class, ZenAllocationContext.class,
            ZenAllocationSource.class, ZenAllocationPortion.class, ZenParallelismSelection.class, ZenParallelismCallback.class,
            ZenRecipeUpdates.class, ZenRecipeUpdateTask.class, ZenRecipeUpdateResult.class, ZenRecipeUpdateCallback.class,
            ZenStructure.class, ZenStructurePattern.class, ZenStructurePredicate.class,
            ZenStructureFacts.class, ZenStructureQuery.class, ZenStructureCapture.class, ZenStructureValidator.class,
            ZenHatchConfiguration.class, ZenResourceHatchSide.class, ZenEnergyHatchSide.class, ZenHatchUpdate.class, ZenHatchReport.class,
            ZenJeiOptions.class, ZenJeiPage.class, ZenGeckoBinding.class, ZenRenderBindings.class,
            pe.visual.examples.run.RunResources.class, ZenComputingResource.class, ZenComputingResources.class, ZenComputingModifier.class, ZenComputerComponent.class, ZenComputerRecipe.class, ZenComputerInputRecipe.class,
            ZenNetworkEvents.class, ZenNetworkDeviceEvent.class, ZenNetworkDeviceCallback.class,
            ZenNetworkData.class, ZenNetworkDevice.class, ZenNetworkValues.class, ZenResourceMap.class,
            ZenComputerRecipeEvent.class, ZenComputerRecipeCallback.class,
            ZenNetworkConfiguration.class, ZenNetworkRequirements.class, ZenNetworkRecipes.class, ZenNetworkRecipeContext.class, ZenNetworkRecipeCallback.class,
            ZenResearch.class, ZenResearchStage.class, ZenResearchFormula.class, ZenResearchReward.class,
            ZenResearchRewardCallback.class, ZenResearchRewardEvent.class, ZenResearchStartCondition.class, ZenResearchStartContext.class,
            ZenResearchDownloads.class, ZenResearchDownloadCallback.class, ZenResearchDownloadContext.class,
            ZenResearchServer.class, ZenResearchPermissionCallback.class, ZenResearchPermissionContext.class,
            ExampleText.class, CoreCases.class, NativeChecks.class, RunNetworkRender.class))
            registry.registerNativeClass(type);
        registry.registerBracketHandler(new DeferredBrackets(registry));
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ScriptVerifier <project-root> <report.json>");
        var root = Path.of(args[0]).toAbsolutePath().normalize();
        var reportFile = Path.of(args[1]).toAbsolutePath().normalize();
        var scripts = readScripts(root.resolve("run/scripts"));
        var report = new Object2ObjectLinkedOpenHashMap<String, Object>();
        var fileReports = new ObjectArrayList<Map<String, Object>>();
        report.put("scope", "all real run scripts compiled; core files executed; World-dependent files pending native run");
        report.put("sdk", "CraftTweaker 4.1.20.718 actual Parser/Compiler and five SDK expansions");
        report.put("dataMemberFixture", "production ZenComponentDataMembers helper; actual Mixin injection remains unverified");
        report.put("brackets", "typed SDK symbols defer real Forge lookup; no fake item/fluid is returned; bracket targets not resolved headlessly");
        report.put("bootstrapStarted", false);
        report.put("files", fileReports);
        report.put("completed", false);
        for (var script : scripts) {
            var fileReport = new Object2ObjectLinkedOpenHashMap<String, Object>();
            fileReport.put("file", root.relativize(script.path()).toString());
            fileReport.put("loader", script.loader());
            fileReport.put("priority", script.priority());
            fileReport.put("compiler", "pending");
            fileReport.put("executed", false);
            fileReport.put("executionStatus", "pending native startup");
            fileReport.put("worldPending", true);
            fileReports.add(fileReport);
        }
        try {
            var verifier = new ScriptVerifier();
            for (int index = 0; index < scripts.size(); index++) {
                var script = scripts.get(index);
                var fileReport = fileReports.get(index);
                try {
                    verifier.compile(script, false);
                } catch (java.io.IOException | RuntimeException failure) {
                    fileReport.put("compiler", "failed");
                    fileReport.put("failure", failure.toString());
                    report.put("failureFile", script.path().getFileName().toString());
                    throw failure;
                }
                fileReport.put("compiler", "passed");
                System.out.println("COMPILED " + script.path().getFileName() + " loader=" + script.loader());
            }
            var preinit = require(scripts, "10_pe_run_core_preinit.zs");
            var ordinary = require(scripts, "20_pe_run_core_checks.zs");
            if (!preinit.loader().equals("preinit") || !ordinary.loader().equals("crafttweaker"))
                throw new IllegalStateException("Core loader phases do not match the actual registration contract");
            verifier.execute(preinit, fileReports.get(scripts.indexOf(preinit)));
            DefinitionRegistrations.dispatch(new MachineTypeRegistrationEvent(MachineTypeRegistry.INSTANCE));
            MachineTypeRegistry.INSTANCE.freeze();
            verifier.execute(ordinary, fileReports.get(scripts.indexOf(ordinary)));
            // The native 95 script has a real client variant with Items. Execute only the collected
            // core registration here, using the exact same public CoreCases/names.registerPools call.
            CoreCases.registerAll();
            DefinitionRegistrations.dispatch(new RecipeRegistrationEvent(RecipeRegistry.INSTANCE, new NetworkRecipeExtensions()));
            RecipeRegistry.INSTANCE.freeze(MachineTypeRegistry.INSTANCE.types());
            var predicates = new StructurePredicateRegistry();
            DefinitionRegistrations.dispatch(new StructurePredicateRegistrationEvent(predicates));
            predicates.freeze();
            var runtime = new MachineRuntimeRegistrationEvent(MachineTypeRegistry.INSTANCE);
            DefinitionRegistrations.dispatch(runtime);
            var json = StructureJsonLoader.readSources(Map.of("core_custom.json", Files.readString(root.resolve("run/scripts/fixtures/core_custom.json"))));
            var catalog = json.resolve(runtime.structureDefinitions(), predicates, new NoBlocks());
            MachineTypeRegistry.INSTANCE.resolveRuntime(catalog, runtime.assemblies());
            report.put("coreApiChecks", "running actual public API checks");
            CoreCases.runRegisteredChecks();
            report.put("coreApiChecks", "passed actual transactions, owner scanning, exact codecs and frozen JSON structure evaluation");
            report.put("nativePending", List.of("actual CT Mixin injection", "all non-core startup scripts with real Forge registries", "old visual addon catalog native startup", "World sampler/save/sync/network/research/GUI/HEI/render commands"));
            report.put("completed", true);
            System.out.println("CORE_API_CHECKS_RETURNED_SUCCESSFULLY");
        } catch (java.io.IOException | RuntimeException failure) {
            report.put("failure", failure.toString());
            throw failure;
        } finally {
            Files.createDirectories(reportFile.getParent());
            Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report));
        }
    }

    private void execute(Script script, Map<String, Object> fileReport) throws java.io.IOException {
        fileReport.put("executionStatus", "running");
        try {
            compile(script, true).getMain().run();
        } catch (java.io.IOException | RuntimeException failure) {
            fileReport.put("executionStatus", "failed");
            fileReport.put("executionFailure", failure.toString());
            throw failure;
        }
        fileReport.put("executed", true);
        fileReport.put("executionStatus", "completed");
        fileReport.put("worldPending", false);
        fileReport.put("nativeMixinAndWorldPending", true);
    }

    private ZenModule compile(Script script, boolean loadModule) throws java.io.IOException {
        diagnostics.reset();
        var bytes = new Object2ObjectLinkedOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(bytes);
        String name = script.path().getFileName().toString();
        ZenParsedFile parsed;
        try {
            parsed = new ZenParsedFile(name, "RunScript_" + name.replace('.', '_'),
                new ZenTokener(new StringReader(script.source()), environment, name, false), global);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("SDK Parser failed in " + name + ": " + failure.getMessage(), failure);
        }
        ZenModule.compileScripts(name, List.of(parsed), global, false);
        String output = diagnostics.toString(StandardCharsets.UTF_8);
        if (!output.isBlank()) throw new IllegalStateException("SDK Compiler diagnostics for " + name + ":\n" + output);
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        return loadModule ? new ZenModule(bytes, ScriptVerifier.class.getClassLoader()) : null;
    }

    private record Script(Path path, String loader, int priority, String source) {
    }

    private static List<Script> readScripts(Path directory) throws java.io.IOException {
        var scripts = new ObjectArrayList<Script>();
        try (var walk = Files.walk(directory)) {
            for (var path : walk.filter(file -> Files.isRegularFile(file) && file.toString().endsWith(".zs")).toList()) {
                String loader = "crafttweaker";
                int priority = 0;
                var body = new StringBuilder();
                for (var original : Files.readAllLines(path)) {
                    var line = original.startsWith("\ufeff") ? original.substring(1) : original;
                    var directive = line.strip();
                    if (directive.startsWith("#loader ")) {
                        loader = directive.substring(8).trim();
                        body.append('\n');
                    } else if (directive.startsWith("#priority ")) {
                        priority = Integer.parseInt(directive.substring(10).trim());
                        body.append('\n');
                    } else body.append(line).append('\n');
                }
                if (!loader.equals("preinit") && !loader.equals("crafttweaker"))
                    throw new IllegalArgumentException("Unsupported verifier loader " + loader + " in " + path);
                scripts.add(new Script(path, loader, priority, body.toString()));
            }
        }
        scripts.sort(Comparator.comparingInt((Script s) -> s.loader().equals("preinit") ? 0 : 1)
                               .thenComparing(Comparator.comparingInt(Script::priority).reversed()).thenComparing(s -> s.path().toString()));
        return scripts;
    }

    private static Script require(List<Script> scripts, String name) {
        return scripts.stream().filter(script -> script.path().getFileName().toString().equals(name)).findFirst()
                      .orElseThrow(() -> new IllegalArgumentException("Missing actual script: " + name));
    }

    private static final class WritableDataType extends ZenTypeNative {
        private WritableDataType() {
            super(ZenComponentData.class);
        }

        @Override
        public IPartialExpression getMember(ZenPosition position, IEnvironmentGlobal environment, IPartialExpression value, String name) {
            var member = ZenComponentDataMembers.member(this, position, environment, value, name);
            return member == null ? super.getMember(position, environment, value, name) : member;
        }
    }

    @BracketHandler(priority = 10)
    private static final class DeferredBrackets implements IBracketHandler {
        private final GenericRegistry registry;

        private DeferredBrackets(GenericRegistry registry) {
            this.registry = registry;
        }

        @Override
        public IZenSymbol resolve(IEnvironmentGlobal environment, List<Token> tokens) {
            var text = new StringBuilder();
            for (var token : tokens) text.append(token.getValue());
            String literal = text.toString();
            boolean fluid = literal.startsWith("liquid:");
            if (!fluid && !literal.matches("[a-z0-9_.-]+:[a-z0-9_./-]+(?::[0-9]+)?")) return null;
            var method = JavaMethod.get(registry.getTypes(), ScriptVerifier.class, fluid ? "fluid" : "item", String.class);
            return position -> new ExpressionCallStatic(position, environment, method, new ExpressionString(position, literal));
        }
    }

    /**
     * Real lookup target, intentionally never invoked in the no-bootstrap compilation layer.
     */
    public static IItemStack item(String literal) {
        var parts = literal.split(":");
        int meta = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        var name = new ResourceLocation(parts[0], parts[1]);
        var item = ForgeRegistries.ITEMS.getValue(name);
        if (item == null) throw new IllegalArgumentException("Unknown native item: " + name);
        return CraftTweakerMC.getIItemStack(new ItemStack(item, 1, meta));
    }

    public static ILiquidStack fluid(String literal) {
        var fluid = FluidRegistry.getFluid(literal.substring("liquid:".length()));
        if (fluid == null) throw new IllegalArgumentException("Unknown native fluid: " + literal);
        return CraftTweakerMC.getILiquidStack(new FluidStack(fluid, 1000));
    }

    private static final class NoBlocks implements StructureBlockResolver {
        private IllegalArgumentException unexpected() {
            return new IllegalArgumentException("Core JSON must not resolve native blocks/displays");
        }

        @Override
        public StructurePredicate block(ResourceLocation id) {
            throw unexpected();
        }

        @Override
        public StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt) {
            throw unexpected();
        }

        @Override
        public StructurePredicate regex(String expression) {
            throw unexpected();
        }

        @Override
        public StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt) {
            throw unexpected();
        }

        @Override
        public List<StructureDisplay.BlockOption> displayRegex(String expression) {
            throw unexpected();
        }
    }
}
