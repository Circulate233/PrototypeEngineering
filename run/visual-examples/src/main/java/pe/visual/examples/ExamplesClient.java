package pe.visual.examples;

import com.circulation.prototype_eng.api.client.jei.JeiRecipeDecorator;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.JeiSlot;
import com.circulation.prototype_eng.api.client.jei.JeiVisuals;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRecipe;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import mcp.MethodsReturnNonnullByDefault;
import mezz.jei.api.gui.IDrawable;
import mezz.jei.api.gui.IRecipeLayout;
import mezz.jei.api.ingredients.IIngredients;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewPath;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewScreens;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.client.preview.PreviewClientOpenQueue;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import java.util.Map;

/** Client classes are isolated from the common registration and dedicated-server paths. */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID, value = Side.CLIENT)
public final class ExamplesClient {
    private ExamplesClient() { }

    @SubscribeEvent
    public static void registerPage(RecipePoolJeiRegistrationEvent event) {
        ClientCommandHandler.instance.registerCommand(new CompositePreviewCommand());
        for (var pool : event.pools()) {
            if (pool.id().equals(VisualExamples.SORTER_POOL)) event.pages().register(pool.id(), new SorterPage(pool));
        }
    }

    /** An explicit count/orientation reaches the same production panel without changing any World block. */
    public static final class CompositePreviewCommand extends CommandBase {
        @Override public String getName() { return "pe_structure_example"; }
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public String getUsage(ICommandSender sender) { return "/pe_structure_example <nested_structure|expandable_structure> [count:1-4] [orientation:NORTH_UP]"; }
        @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length < 1 || args.length > 3 || !CompositeStructureExamples.known(args[0]))
                throw new CommandException(getUsage(sender));
            var name = CompositeStructureExamples.name(args[0]);
            int count = args.length >= 2 ? parseInt(args[1], 1, 4) : 1;
            if (name.equals(CompositeStructureExamples.NESTED) && count != 1) throw new CommandException("Nested example count is 1");
            var orientation = BlockOrientation.NORTH_UP;
            if (args.length == 3) {
                orientation = null;
                for (var candidate : BlockOrientation.values()) if (candidate.name().equals(args[2])) orientation = candidate;
                if (orientation == null) throw new CommandException("Unknown orientation %s", args[2]);
            }
            var type = MachineTypeRegistry.INSTANCE.get(CompositeStructureExamples.id(name));
            if (type == null) throw new CommandException("Composite preinit script has not registered this machine");
            var settings = new StructurePreviewSettings(orientation,
                name.equals(CompositeStructureExamples.EXPANDABLE) ? Map.of(StructurePreviewPath.ROOT.child(0), count) : Map.of(), 65536, 1048576);
            var mc = Minecraft.getMinecraft();
            var player = mc.player; var world = mc.world; var connection = mc.getConnection();
            var container = player == null ? null : player.openContainer;
            int window = container == null ? -1 : container.windowId;
            PreviewClientOpenQueue.INSTANCE.enqueue(() -> {
                if (player != null && container != null && mc.player == player && mc.world == world && mc.getConnection() == connection
                    && player.openContainer == container && container.windowId == window && mc.currentScreen == null)
                    StructurePreviewScreens.open(type, settings);
            });
        }
        @Override public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, net.minecraft.util.math.BlockPos target) {
            if (args.length == 1) return getListOfStringsMatchingLastWord(args, CompositeStructureExamples.NESTED, CompositeStructureExamples.EXPANDABLE);
            if (args.length == 2) return getListOfStringsMatchingLastWord(args, "1", "2", "3", "4");
            if (args.length == 3) return getListOfStringsMatchingLastWord(args, java.util.Arrays.stream(BlockOrientation.values()).map(Enum::name).toList());
            return List.of();
        }
    }

    /** Deliberately independent of AutoLayoutRecipePage; still uses the same native PE ingredient/decorator APIs. */
    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    public static final class SorterPage implements RecipePoolJeiPage {
        private final RecipePool pool;
        private final IDrawable background = new JeiVisuals.NineSlice(new JeiVisuals.Texture(JeiVisuals.texture("jei_base"), 184, 220),
            174, 154, 2, false, 0xffdde3e8);
        private final List<JeiSlot> inputs = slots(10);
        private final List<JeiSlot> outputs = slots(116);
        private final JeiRenderOptions options = new JeiRenderOptions(true, JeiRenderOptions.CandidateMode.ALTERNATIVES, 0);
        private final List<JeiRecipeDecorator.Placement> decorations = List.of(
            new JeiRecipeDecorator.Placement(74, 30, new JeiVisuals.Progress(null, JeiVisuals.Shape.CYCLE, JeiVisuals.Direction.CIRCULAR_CW,
                26, 26, 80, false, 0xff8c949c, 0xff398abb)),
            new JeiRecipeDecorator.Placement(2, 132, new JeiVisuals.Duration(null, 170, 10, JeiVisuals.Alignment.CENTER, .85f, 0xff263747, false)));

        private SorterPage(RecipePool pool) { this.pool = pool; }
        private static List<JeiSlot> slots(int x) {
            var result = new ObjectArrayList<JeiSlot>();
            for (int index = 0; index < 3; index++) result.add(new JeiSlot(x + index % 2 * 22, 28 + index / 2 * 22, 18, 18, 1, JeiVisuals.AUTOMATIC));
            return List.copyOf(result);
        }
        @Override public RecipePool pool() { return pool; }
        @Override public IDrawable getBackground() { return background; }
        @Override public String getModName() { return "PE Visual Examples"; }
        @Override public void drawExtras(Minecraft minecraft) {
            minecraft.fontRenderer.drawString(ExampleText.Input.getLocal(), 10, 9, 0xff263747);
            minecraft.fontRenderer.drawString(ExampleText.Output.getLocal(), 116, 9, 0xff263747);
        }
        @Override public RecipePoolJeiRecipe createRecipe(MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            return new SorterRecipe(this, recipe, display, ingredients);
        }
        @Override public void setRecipe(IRecipeLayout layout, RecipePoolJeiRecipe wrapper, IIngredients nativeIngredients) {
            var recipe = (SorterRecipe) wrapper;
            recipe.ingredients().initialize(layout, inputs, outputs, options);
            recipe.ingredients().showGroups(layout, recipe.inputs, recipe.outputs, inputs.size(), outputs.size());
            layout.setRecipeId(pool.id().getNamespace(), recipe.recipe().id());
        }
    }

    @ParametersAreNonnullByDefault
    @MethodsReturnNonnullByDefault
    public static final class SorterRecipe extends RecipePoolJeiRecipe {
        private final SorterPage page;
        private final List<List<JeiRecipeDisplay.Resource>> inputs;
        private final List<List<JeiRecipeDisplay.Resource>> outputs;
        private SorterRecipe(SorterPage page, MachineRecipe recipe, JeiRecipeDisplay display, JeiRecipeIngredients ingredients) {
            super(page, recipe, display, ingredients);
            this.page = page;
            inputs = rows(display, JeiRecipeDisplay.Role.INPUT, ingredients);
            outputs = rows(display, JeiRecipeDisplay.Role.OUTPUT, ingredients);
        }
        private static List<List<JeiRecipeDisplay.Resource>> rows(JeiRecipeDisplay display, JeiRecipeDisplay.Role role,
                                                                JeiRecipeIngredients ingredients) {
            var groups = new Reference2ObjectOpenHashMap<JeiRecipeDisplay.CandidateGroup, ObjectArrayList<JeiRecipeDisplay.Resource>>();
            for (var row : display.resources()) {
                if (row.role() == role && row.candidates() != null) groups.computeIfAbsent(row.candidates(), ignored -> new ObjectArrayList<>()).add(row);
            }
            var result = new ObjectArrayList<List<JeiRecipeDisplay.Resource>>();
            for (var row : display.resources()) {
                if (row.role() != role) continue;
                var candidates = row.candidates();
                var group = groups.get(candidates);
                if (candidates != null && group != null && ingredients.canCombine(group)) {
                    if (group.getFirst() == row) {
                        for (int pick = 0; pick < candidates.alternativeSlots(); pick++) result.add(List.copyOf(group));
                    }
                } else result.add(List.of(row));
            }
            return List.copyOf(result);
        }
        @Override public void drawInfo(Minecraft minecraft, int width, int height, int mouseX, int mouseY) {
            super.drawInfo(minecraft, width, height, mouseX, mouseY);
            for (var decoration : page.decorations) decoration.draw(minecraft, this);
            fallback(minecraft, inputs, page.inputs);
            fallback(minecraft, outputs, page.outputs);
            int y = 83;
            for (var text : display().information()) {
                if (y > 121) break;
                if (text.energy() != null) JeiVisuals.Energy.ONE_1.draw(minecraft, 6, y, 10, 10, text.energy().role(), 0);
                String label = text.local();
                minecraft.fontRenderer.drawString(minecraft.fontRenderer.trimStringToWidth(label, 151), 19, y, 0xff263747);
                y += 10;
            }
            minecraft.fontRenderer.drawString(minecraft.fontRenderer.trimStringToWidth(ExampleText.CustomPage.getLocal(), 166), 4, 143, 0xff526372);
        }
        private void fallback(Minecraft minecraft, List<List<JeiRecipeDisplay.Resource>> rows, List<JeiSlot> slots) {
            for (int index = 0; index < rows.size(); index++) {
                var row = rows.get(index).getFirst();
                if (!ingredients().supports(row)) JeiRecipeIngredients.renderFallback(minecraft, slots.get(index), row, page.options);
            }
        }
        @Override public List<String> getTooltipStrings(int mouseX, int mouseY) {
            var result = new ObjectArrayList<String>();
            tooltip(result, inputs, page.inputs, mouseX, mouseY);
            tooltip(result, outputs, page.outputs, mouseX, mouseY);
            if (mouseY >= 83 && mouseY < 124) for (var line : display().information()) result.add(line.local());
            if (mouseY >= 143 && mouseY < 154) result.add(ExampleText.CustomPage.getLocal());
            return result;
        }
        private void tooltip(List<String> target, List<List<JeiRecipeDisplay.Resource>> rows, List<JeiSlot> slots, int x, int y) {
            for (int index = 0; index < rows.size(); index++) {
                var slot = slots.get(index);
                if (x >= slot.x() && x < slot.x() + slot.width() && y >= slot.y() && y < slot.y() + slot.height()
                    && !ingredients().supports(rows.get(index).getFirst())) target.addAll(JeiRecipeIngredients.tooltip(rows.get(index).getFirst()));
            }
        }
    }
}
