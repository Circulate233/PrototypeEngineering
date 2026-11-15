package pe.visual.examples.run;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.JeiSlot;
import com.circulation.prototype_eng.api.client.jei.JeiVisuals;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistry;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import com.circulation.prototype_eng.api.client.render.GeckoStructureBinding;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.render.MachineRenderPass;
import com.circulation.prototype_eng.api.client.render.MachineRenderRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.client.render.ClientMachineRenderSnapshots;
import com.circulation.prototype_eng.client.render.StructureRenderAnchors;
import com.circulation.prototype_eng.client.render.gecko.GeckoGeoModelInstances;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import com.circulation.prototype_eng.integration.crafttweaker.jei.ScriptJeiEvents;
import com.circulation.prototype_eng.machine.MachineControllerBlockEntity;
import com.circulation.prototype_eng.machine.data.MachineDataSnapshot;
import com.circulation.prototype_eng.proxy.ClientProxy;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.floats.FloatLists;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import ae2.api.stacks.AEItemKey;
import net.minecraft.util.EnumFacing;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import pe.visual.examples.ExampleText;
import pe.visual.examples.VisualExamples;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import software.bernie.geckolib3.geo.render.built.GeoBone;

/**
 * Actual client event/page/attachment probes. Nothing here launches a client or creates a world.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID, value = Side.CLIENT)
public final class RunRenderClient {
    private static MachineRenderBindings installedBindings;
    private static RunPage independent;
    private static ClientMachineRenderSnapshots.View retainedView;
    private static MachineDataSnapshot retainedData;
    private static boolean commandInstalled;

    private RunRenderClient() {
    }

    @SubscribeEvent
    public static void diskModels(ModelRegistryEvent event) {
        for (var name : List.of("research_disk", "research_low_disk")) {
            var id = RunNetworkRender.id(name);
            ModelLoader.setCustomModelResourceLocation(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(id)), 0, new ModelResourceLocation(id, "inventory"));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void renderRegistry(MachineRenderRegistrationEvent event) {
        installedBindings = event.bindings();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void registerNamedPage(RecipePoolJeiRegistrationEvent event) {
        if (independent != null) return;
        for (var pool : event.pools())
            if (pool.id().equals(RunNetworkRender.id("client_custom_pool"))) {
                independent = new RunPage(pool, event.guiHelper().createBlankDrawable(174, 108));
                ScriptJeiEvents.page("pe_run_tests:client_independent_page", independent);
            }
    }

    @SubscribeEvent
    public static void installCommand(TickEvent.ClientTickEvent event) {
        if (!commandInstalled && event.phase == TickEvent.Phase.END) {
            ClientCommandHandler.instance.registerCommand(new ClientRunCommand());
            commandInstalled = true;
        }
    }

    /**
     * A separate public-interface page. Its wrappers, native ingredient layout and updates use exact pools.
     */
    public static final class RunPage implements RecipePoolJeiPage {
        private RecipePool pool;
        private final IDrawable background;
        private final List<JeiSlot> inputs = slots(8), outputs = slots(118);
        private int updates;

        private RunPage(RecipePool pool, IDrawable background) {
            this.pool = pool;
            this.background = background;
        }

        private static List<JeiSlot> slots(int x) {
            var slots = new ObjectArrayList<JeiSlot>(4);
            for (int index = 0; index < 4; index++)
                slots.add(new JeiSlot(x + index % 2 * 20, 23 + index / 2 * 20, 18, 18, 1, JeiVisuals.AUTOMATIC));
            return slots;
        }

        @Override
        public RecipePool pool() {
            return pool;
        }

        @Override
        public IDrawable getBackground() {
            return background;
        }

        @Override
        public String getModName() {
            return "PE Run Tests";
        }

        @Override
        public void drawExtras(Minecraft minecraft) {
            minecraft.fontRenderer.drawString(ExampleText.Input.getLocal(), 8, 5, 0xff263747);
            minecraft.fontRenderer.drawString(ExampleText.Output.getLocal(), 118, 5, 0xff263747);
            minecraft.fontRenderer.drawString(ExampleText.CustomPage.getLocal(), 8, 87, 0xff526372);
        }

        @Override
        public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            return new RunWrapper(this, pool, recipe, display, ingredients);
        }

        @Override
        public void setRecipe(IRecipeLayout layout, RecipePoolJeiRecipe wrapper, IIngredients ingredients) {
            NativeChecks.check("client_named_page_wrapper", wrapper instanceof RunWrapper && wrapper.page() == this && wrapper.pool() == pool);
            var settings = RecipePoolJeiRegistry.INSTANCE.renderOptions(pool.id());
            wrapper.ingredients().initialize(layout, inputs, outputs, settings);
            var recipe = (RunWrapper) wrapper;
            wrapper.ingredients().showGroups(layout, recipe.inputs, recipe.outputs, inputs.size(), outputs.size());
            layout.setRecipeId(pool.id().getNamespace(), wrapper.recipe().id());
        }

        @Override
        public PreparedUpdate prepareUpdate(RecipePool target, RecipeJeiDisplays.Snapshot displays) {
            NativeChecks.check("client_page_target_identity", target.id().equals(pool.id()));
            var recipes = target.recipes();
            for (int recipeIndex = 0; recipeIndex < recipes.size(); recipeIndex++) {
                var recipe = recipes.get(recipeIndex);
                var display = displays.describe(recipe);
                int inputCount = 0, outputCount = 0;
                var resources = display.resources();
                for (int index = 0; index < resources.size(); index++) {
                    if (resources.get(index).role() == JeiRecipeDisplay.Role.INPUT) inputCount++;
                    else if (resources.get(index).role() == JeiRecipeDisplay.Role.OUTPUT) outputCount++;
                }
                NativeChecks.check("client_page_native_slot_capacity", inputCount <= inputs.size() && outputCount <= outputs.size());
            }
            var expected = pool;
            return new PreparedUpdate() {
                private boolean applied;

                @Override
                public RecipePoolJeiPage page() {
                    return RunPage.this;
                }

                @Override
                public RecipePool pool() {
                    return target;
                }

                @Override
                public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
                    return new RunWrapper(RunPage.this, target, recipe, display, ingredients);
                }

                @Override
                public void apply() {
                    NativeChecks.check("client_page_exact_update", !applied && RunPage.this.pool == expected);
                    RunPage.this.pool = target;
                    applied = true;
                    updates = Math.incrementExact(updates);
                }
            };
        }
    }

    private static final class RunWrapper extends RecipePoolJeiRecipe {
        private final ObjectArrayList<List<JeiRecipeDisplay.Resource>> inputs = new ObjectArrayList<>(), outputs = new ObjectArrayList<>();

        private RunWrapper(RunPage page, RecipePool pool, MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            super(page, pool, recipe, display, ingredients);
            var resources = display.resources();
            for (int index = 0; index < resources.size(); index++) {
                var resource = resources.get(index);
                if (resource.role() == JeiRecipeDisplay.Role.INPUT) inputs.add(List.of(resource));
                else if (resource.role() == JeiRecipeDisplay.Role.OUTPUT) outputs.add(List.of(resource));
            }
        }

        @Override
        public void drawInfo(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
            super.drawInfo(minecraft, width, height, mouseX, mouseY);
            if (!isCurrent()) return;
            var page = (RunPage) page();
            var options = RecipePoolJeiRegistry.INSTANCE.renderOptions(pool().id());
            fallback(minecraft, inputs, page.inputs, options);
            fallback(minecraft, outputs, page.outputs, options);
            minecraft.fontRenderer.drawString(recipe().durationTicks() + " t", 73, 39, 0xff263747);
        }

        private void fallback(Minecraft minecraft, List<List<JeiRecipeDisplay.Resource>> rows, List<JeiSlot> slots, JeiRenderOptions options) {
            for (int i = 0; i < rows.size(); i++)
                if (!ingredients().supports(rows.get(i).getFirst()))
                    JeiRecipeIngredients.renderFallback(minecraft, slots.get(i), rows.get(i).getFirst(), options);
        }

        @Override
        public List<String> getTooltipStrings(int x, int y) {
            if (!isCurrent()) return List.of();
            var rows = display().resources();
            var result = new ObjectArrayList<String>();
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index);
                if (x >= (row.role() == JeiRecipeDisplay.Role.INPUT ? 8 : 118) && y >= 23 && y < 64)
                    result.addAll(JeiRecipeIngredients.tooltip(row));
            }
            return result;
        }
    }

    private static final class ClientRunCommand extends CommandBase {
        @Override
        public String getName() {
            return "pe_run_tests_client";
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/pe_run_tests_client bindings | manifest <v1/v2> | anchors <x> <y> <z> <count> <value> <layersCSV> | capture <x> <y> <z> | reject-old <x> <y> <z> | offset <x> <y> <z> <dx> <dy> <dz>";
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return true;
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            var mc = Minecraft.getMinecraft();
            var client = (ClientProxy) PrototypeEngineering.proxy;
            if (args.length == 1 && args[0].equals("bindings")) {
                NativeChecks.check("render_installed_same_registry", installedBindings != null && installedBindings == client.renderBindings());
                var view = installedBindings.view();
                for (var pass : MachineRenderPass.VALUES) {
                    var machine = RunNetworkRender.id("render_" + pass.name().toLowerCase(java.util.Locale.ROOT));
                    var model = view.machines().get(machine);
                    NativeChecks.check("render_all_passes", model != null && model.pass() == pass && model.forceGlobalRenderer());
                    NativeChecks.check("render_actual_resource_bindings", model.geo().equals(new ResourceLocation("pe_visual_examples:geo/run_tests_machine.json"))
                        && model.layers().equals(List.of("idle", "overlay")));
                    NativeChecks.check("render_slice_binding", view.structures().get(new MachineRenderBindings.StructureKey(machine, RunNetworkRender.id("render_per_slice"))).sliceMode() == GeckoStructureBinding.SliceMode.PER_SLICE);
                    NativeChecks.check("render_whole_binding", view.structures().get(new MachineRenderBindings.StructureKey(machine, RunNetworkRender.id("render_whole"))).sliceMode() == GeckoStructureBinding.SliceMode.STRUCTURE_ONLY);
                    try (var geo = mc.getResourceManager().getResource(model.geo()); var texture = mc.getResourceManager().getResource(model.texture());
                         var animation = mc.getResourceManager().getResource(model.animation())) {
                        NativeChecks.check("render_real_pack_resources", geo.getInputStream().read() >= 0 && texture.getInputStream().read() >= 0 && animation.getInputStream().read() >= 0);
                    } catch (java.io.IOException error) {
                        throw new CommandException("PE run resources unavailable: " + error.getMessage());
                    }
                }
                pass(sender, "render_installed_same_registry", "all four passes, machine/component/whole/per-slice, real pack resources");
                return;
            }
            if (args.length == 2 && args[0].equals("manifest")) {
                var definitions = client.recipeDefinitions();
                var applied = definitions.applied();
                var key = new RecipeDeclarationKey(RunNetworkRender.id("directory"), args[1]);
                var local = ClientRecipeDeclarations.INSTANCE.get(key);
                NativeChecks.check("client_private_complete_manifest", applied != null && applied.declaration().equals(key) && local != null
                    && definitions.resolved() == local && applied.matches(local.snapshot()) && local.snapshot() != RecipeRegistry.INSTANCE.snapshot());
                NativeChecks.check("client_actual_named_page", independent != null && independent.pool() == local.snapshot().pools().get(RunNetworkRender.id("client_custom_pool"))
                    && RecipePoolJeiRegistry.INSTANCE.pages().stream().anyMatch(page -> page == independent));
                var options = RecipePoolJeiRegistry.INSTANCE.renderOptions(RunNetworkRender.id("client_custom_pool"));
                NativeChecks.check("client_global_and_pool_options", !options.chanceOverlay() && options.candidates() == JeiRenderOptions.CandidateMode.EXPANDED
                    && JeiRenderOptions.defaults().chanceOverlay() && JeiRenderOptions.defaults().candidates() == JeiRenderOptions.CandidateMode.ALTERNATIVES);
                var recipe = local.snapshot().catalog().recipes().stream().filter(r -> r.id().equals(RunNetworkRender.id("client_update"))).findFirst().orElseThrow();
                var display = local.displays().describe(recipe);
                var outputs = display.resources().stream().filter(row -> row.role() == JeiRecipeDisplay.Role.OUTPUT).toList();
                boolean v2 = args[1].equals("v2");
                NativeChecks.check("client_actual_version_rows", recipe.durationTicks() == (v2 ? 40 : 120) && outputs.size() == 1
                    && outputs.getFirst().stack().what().equals(AEItemKey.of(new ItemStack(v2 ? Items.DIAMOND : Items.EMERALD)))
                    && outputs.getFirst().stack().amount() == (v2 ? 2 : 1) && outputs.getFirst().chance() != null && outputs.getFirst().chance().percent() == (v2 ? 75 : 25));
                var external = RunNetworkRender.id("client_external_pool");
                NativeChecks.check("client_existing_native_category_only", "minecraft.crafting".equals(RecipePoolJeiRegistry.INSTANCE.externalPages().get(external))
                    && RecipePoolJeiRegistry.INSTANCE.pages().stream().noneMatch(page -> page.pool().id().equals(external))
                    && RecipePoolJeiRegistry.INSTANCE.catalysts(external).stream().anyMatch(stack -> RunNetworkRender.id("render_component_controller").equals(stack.getItem().getRegistryName())));
                pass(sender, "client_private_complete_manifest", "version=" + args[1] + " serverGeneration=" + applied.generation() + " pools=" + applied.pools().size() + " independentPageUpdates=" + independent.updates);
                return;
            }
            if (args.length >= 4) {
                var pos = parseBlockPos(sender, args, 1, false);
                NativeChecks.check("render_actual_client_tile", mc.world != null && mc.world.getTileEntity(pos) instanceof MachineControllerBlockEntity);
                var tile = (MachineControllerBlockEntity) mc.world.getTileEntity(pos);
                var view = client.renderSnapshots().view(mc.world, tile, pos);
                var data = tile.dataSnapshot();
                if (args.length == 5 && args[0].equals("data")) {
                    NativeChecks.check("client_ordinary_owner_full_sync", view != null && data != null && data.machine() != null
                        && data.generation() == view.snapshot().display().generation()
                        && data.machine().data().getString("last_tick_nonce").equals(args[4]));
                    pass(sender, "client_ordinary_owner_full_sync", "nonce=" + args[4] + " generation=" + data.generation() + " hosts=" + data.hosts());
                    return;
                }
                if (args.length == 5 && args[0].equals("migration")) {
                    NativeChecks.check("client_old_process_data_full_sync", view != null && data != null && data.generation() == view.snapshot().display().generation()
                        && data.hosts().stream().flatMap(host -> host.slots().stream()).anyMatch(slot -> slot.process() != null && slot.process().recipe().equals(RunNetworkRender.id("client_migration"))
                        && slot.process().data() != null && slot.process().data().data().getString("migration_nonce").equals(args[4])));
                    pass(sender, "client_old_process_data_full_sync", "nonce=" + args[4] + " generation=" + data.generation() + " actual hosts=" + data.hosts());
                    return;
                }
                if (args.length == 4 && args[0].equals("capture")) {
                    NativeChecks.check("render_capture_actual_current", view != null && data != null);
                    retainedView = view;
                    retainedData = data;
                    pass(sender, "render_capture_actual_current", "pos=" + pos);
                    return;
                }
                if (args.length == 4 && args[0].equals("reject-old")) {
                    NativeChecks.check("client_old_attachment_rejected", retainedView != null && retainedData != null
                        && !client.renderSnapshots().current(retainedView) && tile.dataSnapshot() != retainedData);
                    NativeChecks.check("client_successor_actual_current", view != null && data != null);
                    pass(sender, "client_old_attachment_rejected", "new actual world/connection/attachment");
                    return;
                }
                NativeChecks.check("render_actual_data_generation", view != null && data != null && data.machine() != null
                    && data.generation() == view.snapshot().display().generation());
                var anchors = StructureRenderAnchors.select(view.snapshot(), view.bindings(), data.machine());
                if (args.length == 5 && args[0].equals("frames")) {
                    int expected = parseInt(args[4], 1);
                    NativeChecks.check("render_native_frame_anchor_count", anchors.size() == expected);
                    var resources = mc.getResourceManager();
                    var requests = anchors.stream().map(anchor -> GeckoRenderRequest.from(new GeckoRenderRequest.Owner(), new GeckoRenderRequest.Epoch(), new GeckoRenderRequest.Epoch(),
                        anchor, pos, GeckoModelBaker.BakeMode.ANIMATED_ONLY, new GeckoRenderRequest.Sample(10, 1))).toList();
                    CompletableFuture.supplyAsync(() -> {
                        var result = new ObjectArrayList<String>();
                        for (var first : requests) {
                            var instance = new GeckoGeoModelInstances.Instance(first, resources, new GeckoModelBaker());
                            var second = new GeckoRenderRequest(first.owner(), first.part(), first.bindingEpoch(), first.resourceEpoch(), first.binding(), first.layers(), first.mode(),
                                first.position(), first.origin(), first.orientation(), first.localOffset(), new GeckoRenderRequest.Sample(19, 1));
                            try {
                                var before = instance.bake(first, (model, baker, index) -> frame(model, baker));
                                var after = instance.bake(second, (model, baker, index) -> frame(model, baker));
                                NativeChecks.check("render_native_worker_frame", before.vertices > 0 && before.vertices == after.vertices && !before.pose.equals(after.pose));
                                result.add(first.part() + " vertices=" + after.vertices + " layers=" + first.layers());
                            } catch (Exception failure) {
                                throw new CompletionException(failure);
                            }
                        }
                        return result;
                    }).whenComplete((result, failure) -> mc.addScheduledTask(() -> {
                        NativeChecks.check("render_native_worker_completed", failure == null);
                        NativeChecks.check("render_native_frame_current_owner", client.renderSnapshots().current(view) && tile.dataSnapshot() == data);
                        pass(sender, "render_native_worker_frame", result.toString());
                    }));
                    return;
                }
                if (args.length == 4 && args[0].equals("components")) {
                    NativeChecks.check("render_actual_component_anchors", view.snapshot().components().contains(RunNetworkRender.id("render_marker"))
                        && anchors.size() == 2 && anchors.stream().allMatch(anchor -> anchor.origin().equals(pos))
                        && anchors.stream().anyMatch(anchor -> anchor.part().kind() == StructureRenderAnchors.Kind.MACHINE && anchor.part().id().equals(RunNetworkRender.id("render_component")))
                        && anchors.stream().anyMatch(anchor -> anchor.part().kind() == StructureRenderAnchors.Kind.COMPONENT && anchor.part().id().equals(RunNetworkRender.id("render_marker"))));
                    pass(sender, "render_actual_component_anchors", anchors.toString());
                    return;
                }
                if (args.length == 7 && args[0].equals("anchors")) {
                    int count = parseInt(args[4], 1), value = parseInt(args[5]);
                    var layers = List.of(args[6].split(","));
                    NativeChecks.check("render_actual_anchors_layers", anchors.size() == count && data.machine().data().getInteger("run_owner_value") == value
                        && anchors.stream().allMatch(anchor -> anchor.layers().equals(layers)));
                    if (count == 3) {
                        var up = view.snapshot().display().orientation().rotate(EnumFacing.UP);
                        var origins = List.of(pos, pos.add(up.getXOffset(), up.getYOffset(), up.getZOffset()), pos.add(up.getXOffset() * 2, up.getYOffset() * 2, up.getZOffset() * 2));
                        NativeChecks.check("render_exact_native_occurrence_anchors", anchors.stream().map(StructureRenderAnchors.Anchor::origin).toList().equals(origins)
                            && anchors.stream().allMatch(anchor -> anchor.part().kind() == StructureRenderAnchors.Kind.STRUCTURE)
                            && anchors.get(0).part().id().equals(RunNetworkRender.id("render_whole")) && anchors.get(0).part().slice() == -1 && anchors.get(0).part().occurrencePath().isEmpty()
                            && anchors.get(1).part().id().equals(RunNetworkRender.id("render_per_slice")) && anchors.get(1).part().slice() == 0 && anchors.get(1).part().occurrencePath().equals(List.of(0))
                            && anchors.get(2).part().id().equals(RunNetworkRender.id("render_per_slice")) && anchors.get(2).part().slice() == 1 && anchors.get(2).part().occurrencePath().equals(List.of(0)));
                    }
                    pass(sender, "render_actual_anchors_layers", "count=" + anchors.size() + " layers=" + layers + " positions=" + anchors.stream().map(StructureRenderAnchors.Anchor::origin).toList());
                    return;
                }
                if (args.length == 7 && args[0].equals("offset")) {
                    double x = parseDouble(args[4]), y = parseDouble(args[5]), z = parseDouble(args[6]);
                    NativeChecks.check("render_front_top_world_y_offset", !anchors.isEmpty() && anchors.stream().allMatch(anchor ->
                        Math.abs(anchor.modelOffset().x - x) < 0.00001 && Math.abs(anchor.modelOffset().y - y) < 0.00001 && Math.abs(anchor.modelOffset().z - z) < 0.00001));
                    pass(sender, "render_front_top_world_y_offset", anchors.getFirst().modelOffset().toString());
                    return;
                }
            }
            throw new WrongUsageException(getUsage(sender));
        }

        private static void pass(ICommandSender sender, String id, String detail) {
            sender.sendMessage(new TextComponentString("PE_RUN_TESTS PASS " + id + " " + detail));
        }

        private static Frame frame(software.bernie.geckolib3.geo.render.built.GeoModel model, GeckoModelBaker baker) {
            var pose = new FloatArrayList();
            for (var root : model.topLevelBones) pose(root, pose);
            int total = 0;
            for (int vertices : baker.estimateRoutedFilteredVertexCounts(model, Set.of(), GeckoModelBaker.BakeMode.ALL, Set.of()))
                total = Math.addExact(total, vertices);
            return new Frame(total, FloatLists.unmodifiable(pose));
        }

        private static void pose(GeoBone bone, FloatArrayList values) {
            values.add(bone.getRotationX());
            values.add(bone.getRotationY());
            values.add(bone.getRotationZ());
            values.add(bone.getPositionX());
            values.add(bone.getPositionY());
            values.add(bone.getPositionZ());
            values.add(bone.getScaleX());
            values.add(bone.getScaleY());
            values.add(bone.getScaleZ());
            for (var child : bone.childBones) pose(child, values);
        }

        private record Frame(int vertices, FloatList pose) {
        }
    }
}
