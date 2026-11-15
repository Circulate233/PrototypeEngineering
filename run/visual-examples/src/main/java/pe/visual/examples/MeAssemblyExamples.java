package pe.visual.examples;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import ae2.api.crafting.PatternDetailsHelper;
import ae2.api.orientation.BlockOrientation;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import ae2.core.definitions.AEBlocks;
import ae2.core.definitions.AEItems;
import ae2.core.definitions.AEParts;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.ParallelController;
import com.circulation.prototype_eng.api.machine.ParallelControllerRegistrationEvent;
import com.circulation.prototype_eng.api.machine.ParallelControllerType;
import com.circulation.prototype_eng.api.machine.ParallelControllers;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatchGrouping;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.structure.tool.StructureBuildPlan;
import com.circulation.prototype_eng.block.DecorativeBlocks;
import com.circulation.prototype_eng.block.MEAssemblyBlocks;
import com.circulation.prototype_eng.storage.MEPatternAssemblyBlockEntity;
import com.circulation.prototype_eng.storage.MEResourceHatchBlockEntity;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.items.CapabilityItemHandler;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Native run helpers. ME inputs are never seeded through a recipe-side storage shortcut.
 */
@Mod.EventBusSubscriber(modid = VisualExamples.MOD_ID)
public final class MEAssemblyExamples {
    public static final List<String> NAMES = List.of("me_assemblies", "me_interface", "me_pattern", "me_colors", "me_parallel", "me_wide");

    public enum Text implements LocalizationEnum {
        JavaParallel, Kit, Patterns, Invalid, Edited, MeOnly, Resources, Pattern, Parallel, Groups, Group, Process,
        Check, GalleryEntry, GalleryReady;

        @Override
        public String getTranslationKey() {
            return "pe_run_tests.me." + name().toLowerCase(Locale.ROOT);
        }
    }

    private MEAssemblyExamples() {
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation("pe_run_tests", path);
    }

    @SubscribeEvent
    public static void parallelType(ParallelControllerRegistrationEvent event) {
        event.registry().register(new ParallelControllerType(VisualExamples.id("parallel_java"), Text.JavaParallel, 24, null));
    }

    /**
     * Geometry and admissible materials come from the actual script-registered structure.
     */
    public static @Nullable BlockPos2ObjectMap<Block> placement(String requested) {
        var id = new ResourceLocation(requested.contains(":") ? requested : "pe_run_tests:" + requested);
        if (!id.getNamespace().equals("pe_run_tests") || !NAMES.contains(id.getPath())) return null;
        var type = Objects.requireNonNull(MachineTypeRegistry.INSTANCE.get(id), "13 ME preinit script has not loaded");
        var shape = MachineTypeRegistry.INSTANCE.structure(type);
        var view = StructureCatalogView.capture(new StructureCatalog(List.of(new StructureDefinition(shape))), new UUID(0, 24), 0);
        var plan = StructureBuildPlan.expand(view, type.structureId(), BlockOrientation.NORTH_UP,
            it.unimi.dsi.fastutil.objects.Object2IntMaps.emptyMap());
        var blocks = new BlockPos2ObjectMap<Block>(plan.cells().size() + 1);
        for (var cell : plan.cells()) {
            var display = Objects.requireNonNull(cell.display(), "ME example has unknown material");
            if (!display.complete()) throw new IllegalStateException("ME example has partial material choices");
            var selected = display.options().getFirst().blockId();
            for (var option : display.options()) {
                if (option.blockId().equals(new ResourceLocation("prototype_eng:parallel_controller_normal"))
                    || option.blockId().equals(new ResourceLocation("prototype_eng:block_casing_plain"))
                    || option.blockId().equals(new ResourceLocation("prototype_eng:me_input_assembly"))
                    || option.blockId().equals(new ResourceLocation("prototype_eng:me_output_assembly")))
                    selected = option.blockId();
            }
            blocks.put(cell.position(), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(selected), "Missing ME example block " + selected));
        }
        blocks.put(BlockPos.ORIGIN, VisualExamples.block(id + "_controller"));
        return blocks;
    }

    public static void giveKit(EntityPlayerMP player) {
        for (var name : NAMES) give(player, new ItemStack(VisualExamples.block(id(name) + "_controller")));
        give(player, new ItemStack(VisualExamples.block(CtmGalleryExamples.TYPE + "_controller")));
        for (var block : DecorativeBlocks.all()) give(player, new ItemStack(block));
        for (var block : MEAssemblyBlocks.resources()) give(player, new ItemStack(block, 3));
        give(player, new ItemStack(MEAssemblyBlocks.pattern(), 2));
        for (var tier : List.of("normal", "reinforced", "elite", "super", "ultimate"))
            give(player, new ItemStack(VisualExamples.block("prototype_eng:parallel_controller_" + tier)));
        give(player, new ItemStack(VisualExamples.block(VisualExamples.id("parallel_java").toString())));
        give(player, new ItemStack(VisualExamples.block(id("parallel_zs").toString())));
        for (var name : List.of("input_hatch_4", "output_hatch_4"))
            give(player, new ItemStack(VisualExamples.block("prototype_eng:" + name), 4));
        give(player, AEBlocks.CONTROLLER.stack());
        give(player, AEBlocks.CREATIVE_ENERGY_CELL.stack());
        give(player, AEBlocks.DRIVE.stack());
        give(player, AEItems.ITEM_CELL_64K.stack());
        give(player, AEItems.FLUID_CELL_64K.stack());
        give(player, AEParts.GLASS_CABLE.stack(AEColor.TRANSPARENT, 64));
        give(player, AEParts.TERMINAL.stack());
        give(player, AEParts.PATTERN_ACCESS_TERMINAL.stack());
        give(player, AEParts.PATTERN_ENCODING_TERMINAL.stack());
        give(player, new ItemStack(Items.IRON_INGOT, 64));
        give(player, new ItemStack(Items.GOLD_INGOT, 16));
        give(player, new ItemStack(Items.REDSTONE, 64));
        give(player, new ItemStack(Items.COAL, 1));
        give(player, new ItemStack(Blocks.SAND, 8));
        give(player, new ItemStack(Items.WATER_BUCKET));
        player.inventoryContainer.detectAndSendChanges();
        player.sendMessage(Text.Kit.text());
    }

    private static void give(EntityPlayerMP player, ItemStack stack) {
        if (stack.isEmpty()) throw new IllegalStateException("Example kit item is missing");
        if (!player.inventory.addItemStackToInventory(stack) && !stack.isEmpty()) player.dropItem(stack, false);
    }

    private static void patterns(EntityPlayerMP player) {
        for (var name : List.of("me_pattern_iron", "me_pattern_gold", "me_pattern_pair")) {
            var recipe = Objects.requireNonNull(BoundaryExamples.findRecipe(id(name).toString()), "24 ME recipe script has not loaded");
            var inputs = new ObjectArrayList<GenericStack>();
            var outputs = new ObjectArrayList<GenericStack>();
            var redstone = AEItemKey.of(Items.REDSTONE);
            for (var requirement : recipe.orderedRequirements()) {
                if (!(requirement.payload() instanceof ResourceRequirement resource))
                    throw new IllegalStateException("Unexpected ME example requirement");
                for (var input : resource.inputs()) if (!input.what().equals(redstone)) inputs.add(input);
                outputs.addAll(resource.outputs());
            }
            give(player, PatternDetailsHelper.encodeProcessingPattern(inputs, outputs));
        }
        player.inventoryContainer.detectAndSendChanges();
        player.sendMessage(Text.Patterns.text());
    }

    private static void inspect(ICommandSender sender, TileEntity tile) {
        if (tile instanceof MEResourceHatchBlockEntity resource) {
            var storage = resource.storage();
            var output = storage.outputView();
            var node = resource.getActionableNode();
            sender.sendMessage(Text.Resources.text(resource.mode(), resource.resourceColor(), storage.inputSlots(), output.stacks().size(),
                node != null && node.isActive()));
            sender.sendMessage(Text.MeOnly.text(!tile.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)
                && !tile.hasCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null)));
        } else if (tile instanceof MEPatternAssemblyBlockEntity pattern) {
            var node = pattern.getActionableNode();
            sender.sendMessage(Text.Pattern.text(pattern.mode(), pattern.resourceColor(), pattern.patternPages(), pattern.patterns().size(),
                pattern.getAvailablePatterns().size(), pattern.inputView().stacks().size(), pattern.catalystView().stacks().size(),
                pattern.outputView().stacks().size(), node != null && node.isActive(), pattern.isBusy()));
        } else if (tile instanceof ParallelController parallel) {
            var state = parallel.snapshot();
            sender.sendMessage(Text.Parallel.text(state.selected(), state.maximum(), state.revision(), state.valid()));
        } else if (tile instanceof MachineControllerHost host) {
            boundary(host, sender, machine -> {
                var capture = MachineHatches.get(machine).inputGroups();
                sender.sendMessage(Text.Groups.text(capture.groups().size(), ParallelControllers.limit(machine)));
                for (int index = 0; index < Math.min(64, capture.groups().size()); index++) {
                    var group = capture.groups().get(index);
                    sender.sendMessage(Text.Group.text(group.id().color(), group.id().partition(), group.hatches().resources().size()));
                }
                for (var work : machine.recipeHosts())
                    for (var process : work.activeProcesses())
                        sender.sendMessage(Text.Process.text(process.recipe().id(), process.identity(), process.selectedInputGroup(), process.state()));
            });
        } else sender.sendMessage(Text.Invalid.text());
    }

    private static void boundary(MachineControllerHost host, ICommandSender sender, Consumer<MachineInstance> action) {
        host.readGuiAtBoundary(() -> {
            var machine = host.machine();
            if (!host.formed() || machine == null) {
                sender.sendMessage(Text.Invalid.text());
                return;
            }
            action.accept(machine);
        });
    }

    private static AEKey key(String name) throws CommandException {
        if (name.equals("water")) return AEFluidKey.of(FluidRegistry.WATER);
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(name));
        if (item == null || item == Items.AIR) throw new CommandException("Unknown item %s", name);
        return AEItemKey.of(item);
    }

    public static final class Command extends CommandBase {
        @Override
        public String getName() {
            return "pe_me_examples";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return "/pe_me_examples kit|patterns | inspect x y z | color x y z TRANSPARENT|RED|BLUE | mark x y z slot key|clear amount | "
                + "assert-parallel/assert-groups x y z expected | gallery-list/gallery-check x y z";
        }

        @Override
        public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
            if (args.length == 1 && args[0].equals("kit")) {
                giveKit(getCommandSenderAsPlayer(sender));
                return;
            }
            if (args.length == 1 && args[0].equals("patterns")) {
                patterns(getCommandSenderAsPlayer(sender));
                return;
            }
            if (args.length < 4) throw new WrongUsageException(getUsage(sender));
            var world = sender.getEntityWorld();
            var position = parseBlockPos(sender, args, 1, false);
            if (!world.isBlockLoaded(position, false)) throw new CommandException("Example target chunk is not loaded");
            var tile = world.getTileEntity(position);
            if (tile == null) throw new CommandException("No example tile at %s", position);
            if (args[0].equals("inspect") && args.length == 4) {
                inspect(sender, tile);
                return;
            }
            if (args[0].equals("gallery-list") || args[0].equals("gallery-check")) {
                if (args.length != 4 || !(tile instanceof MachineControllerHost host) || !host.type().id().equals(CtmGalleryExamples.TYPE))
                    throw new CommandException("Expected the CTM gallery controller");
                var rotation = StructureTransform.of(host.orientation());
                if (args[0].equals("gallery-list")) {
                    for (var panel : CtmGalleryExamples.panels())
                        sender.sendMessage(Text.GalleryEntry.text(panel.block(), position.add(rotation.apply(panel.origin()))));
                } else {
                    var expected = Objects.requireNonNull(CtmGalleryExamples.placement(CtmGalleryExamples.NAME));
                    for (var entries = expected.fastIterator(); entries.hasNext(); ) {
                        var entry = entries.next();
                        var target = position.add(rotation.apply(entry.getLongKey()));
                        if (!world.isBlockLoaded(target, false) || world.getBlockState(target).getBlock() != entry.getValue())
                            throw new CommandException("Gallery block is missing or unloaded at %s", target);
                    }
                    sender.sendMessage(Text.GalleryReady.text(CtmGalleryExamples.panels().size(), expected.size()));
                }
                return;
            }
            if (args[0].equals("color") && args.length == 5) {
                var player = getCommandSenderAsPlayer(sender);
                if (!world.isBlockModifiable(player, position) || !(tile instanceof ResourceHatchGrouping grouped))
                    throw new CommandException("Target is not an editable resource chamber");
                AEColor color = null;
                for (var candidate : AEColor.VALUES) if (candidate.name().equalsIgnoreCase(args[4])) color = candidate;
                if (color == null)
                    throw new CommandException("Expected TRANSPARENT or one of the 16 resource group colors");
                grouped.setResourceColor(color);
                sender.sendMessage(Text.Edited.text(grouped.resourceColor()));
                return;
            }
            if (args[0].equals("mark") && args.length == 7) {
                var player = getCommandSenderAsPlayer(sender);
                if (!world.isBlockModifiable(player, position) || !(tile instanceof MEResourceHatchBlockEntity resource))
                    throw new CommandException("Expected an editable ME input assembly/interface");
                var storage = resource.storage();
                if (storage.inputSlots() == 0) throw new CommandException("ME output assembly has no markers");
                int slot = parseInt(args[4], 0, storage.inputSlots() - 1);
                long amount = parseLong(args[6], args[5].equals("clear") ? 0 : 1, Long.MAX_VALUE);
                var mark = args[5].equals("clear") ? null : new GenericStack(key(args[5]), amount);
                boolean changed = resource.compareInputMark(slot, storage.inputView().revision(), mark);
                sender.sendMessage(Text.Check.text(changed ? "PASS" : "REJECTED", "marker", slot));
                return;
            }
            if ((args[0].equals("assert-parallel") || args[0].equals("assert-groups")) && args.length == 5 && tile instanceof MachineControllerHost host) {
                int expected = parseInt(args[4], 0, Integer.MAX_VALUE);
                boundary(host, sender, machine -> {
                    int actual = args[0].equals("assert-parallel") ? ParallelControllers.limit(machine) : MachineHatches.get(machine).inputGroups().groups().size();
                    sender.sendMessage(Text.Check.text(actual == expected ? "PASS" : "FAIL", args[0] + " expected=" + expected, actual));
                });
                return;
            }
            throw new WrongUsageException(getUsage(sender));
        }

        @Override
        public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos target) {
            if (args.length == 1)
                return getListOfStringsMatchingLastWord(args, "kit", "patterns", "inspect", "color", "mark", "assert-parallel", "assert-groups", "gallery-list", "gallery-check");
            if (args.length == 5 && args[0].equals("color")) {
                var colors = new ObjectArrayList<String>(AEColor.VALUES.length);
                for (var color : AEColor.VALUES) colors.add(color.name());
                return getListOfStringsMatchingLastWord(args, colors);
            }
            if (args.length == 6 && args[0].equals("mark")) return getListOfStringsMatchingLastWord(args,
                "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:coal", "minecraft:sand", "water", "clear");
            return List.of();
        }
    }
}
