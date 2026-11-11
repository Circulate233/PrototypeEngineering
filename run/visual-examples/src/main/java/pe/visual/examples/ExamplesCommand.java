package pe.visual.examples;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import pe.visual.examples.run.NativeExamples;
import pe.visual.examples.run.RunNetworkRender;
import pe.visual.examples.run.CoreCases;
import pe.visual.examples.run.ExternalHatchExamples;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import ae2.api.orientation.BlockOrientation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.Objects;
import java.util.List;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.Nullable;

/** Explicit player-invoked development helpers; build preflights every location and only fills air. */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class ExamplesCommand extends CommandBase {
    @Override public String getName() { return "pe_examples"; }
    @Override public String getUsage(ICommandSender sender) {
        return "/pe_examples kit [boundary|composite] | build <machine> <x> <y> <z> [orientation:NORTH_UP] [repeats:1-4] | structure-check <x> <y> <z> <repeats> <matched> | charge/drain <x> <y> <z> [FE] | stock <x> <y> <z> <recipe> [runs:1-16] [candidate:1-64]";
    }
    @Override public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] arguments) throws CommandException {
        if (arguments.length == 2 && arguments[0].equals("kit") && arguments[1].equals("composite")) {
            var player = getCommandSenderAsPlayer(sender);
            for (var name : List.of(CompositeStructureExamples.NESTED, CompositeStructureExamples.EXPANDABLE))
                give(player, item(CompositeStructureExamples.id(name + "_controller")), 1);
            for (var name : List.of("input_hatch_4", "output_hatch_4", "energy_input_hatch_4"))
                give(player, item(new ResourceLocation("prototype_eng", name)), 2);
            for (var block : List.of(Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.GLASS, Blocks.QUARTZ_BLOCK,
                Blocks.LAPIS_BLOCK, Blocks.EMERALD_BLOCK, Blocks.REDSTONE_BLOCK)) give(player, Item.getItemFromBlock(block), 64);
            give(player, Item.getItemFromBlock(Blocks.SAND), 16);
            player.inventoryContainer.detectAndSendChanges();
            player.sendMessage(ExampleText.CompositeKit.text());
            return;
        }
        if (arguments.length == 6 && arguments[0].equals("structure-check")) {
            var position = parseBlockPos(sender, arguments, 1, false);
            CompositeStructureExamples.check(sender.getEntityWorld(), position, parseInt(arguments[4], 0, 5), parseBoolean(arguments[5]));
            sender.sendMessage(new net.minecraft.util.text.TextComponentString("PASS composite actual World, frozen match, block count and occurrence tree; inspect pe_run_tests log."));
            return;
        }
        if (arguments.length == 2 && arguments[0].equals("kit") && arguments[1].equals("boundary")) {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            for (var scenario : BoundaryExamples.SCENARIOS) give(player, item(VisualExamples.id(scenario.name() + "_controller")), 1);
            give(player, item(new ResourceLocation("prototype_eng", "input_hatch_10")), BoundaryExamples.SCENARIOS.size());
            give(player, item(new ResourceLocation("prototype_eng", "output_hatch_10")), BoundaryExamples.SCENARIOS.size());
            give(player, item(new ResourceLocation("prototype_eng", "mixed_hatch_10")), 2);
            give(player, item(new ResourceLocation("prototype_eng", "energy_input_hatch_4")), BoundaryExamples.SCENARIOS.size());
            give(player, item(new ResourceLocation("prototype_eng", "energy_output_hatch_4")), 2);
            give(player, item(new ResourceLocation("prototype_eng", "energy_mixed_hatch_4")), 2);
            give(player, Item.getItemFromBlock(Blocks.IRON_BLOCK), 128);
            give(player, Item.getItemFromBlock(Blocks.GLASS), 128);
            player.inventoryContainer.detectAndSendChanges();
            player.sendMessage(ExampleText.BoundaryKit.text());
            return;
        }
        if (arguments.length >= 5 && arguments.length <= 7 && arguments[0].equals("build")) {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            BlockOrientation orientation = arguments.length >= 6 ? orientation(arguments[5]) : BlockOrientation.NORTH_UP;
            if (arguments.length == 7 && !CompositeStructureExamples.name(arguments[1]).equals(CompositeStructureExamples.EXPANDABLE))
                throw new WrongUsageException("Only expandable_structure accepts a repeat count");
            int repeats = arguments.length == 7 ? parseInt(arguments[6], 1, 4) : 1;
            var blocks = CompositeStructureExamples.placement(arguments[1], repeats);
            if (blocks == null) blocks = NativeExamples.structure(arguments[1]);
            if (blocks == null) blocks = ExternalHatchExamples.placement(arguments[1]);
            if (blocks == null && (arguments[1].equals("core_world") || arguments[1].equals("pe_run_tests:core_world"))) blocks = CoreCases.worldPlacement();
            if (blocks == null) blocks = RunNetworkRender.placement(arguments[1]);
            if (blocks == null) blocks = BoundaryExamples.structure(arguments[1]);
            if (blocks == null) { sender.sendMessage(ExampleText.UnknownMachine.text(arguments[1])); return; }
            var rotated = new it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap<BlockPos, net.minecraft.block.Block>();
            var rotation = StructureTransform.of(orientation);
            for (var entry : blocks.entrySet()) if (!entry.getKey().equals(BlockPos.ORIGIN)) rotated.put(rotation.apply(entry.getKey()), entry.getValue());
            rotated.put(BlockPos.ORIGIN, Objects.requireNonNull(blocks.get(BlockPos.ORIGIN)));
            var origin = parseBlockPos(sender, arguments, 2, false);
            var world = sender.getEntityWorld();
            for (var offset : rotated.keySet()) {
                var position = origin.add(offset);
                if (position.getY() < 0 || position.getY() >= world.getHeight() || !world.getWorldBorder().contains(position)
                    || !world.isBlockLoaded(position) || !world.isAirBlock(position) || !world.isBlockModifiable(player, position)) {
                    sender.sendMessage(ExampleText.BuildBlocked.text(position.getX(), position.getY(), position.getZ()));
                    return;
                }
            }
            int placed = 0;
            for (var entry : rotated.entrySet()) {
                var position = origin.add(entry.getKey());
                if (!world.setBlockState(position, entry.getValue().getDefaultState(), 3)) {
                    sender.sendMessage(ExampleText.BuildFailed.text(placed, position.getX(), position.getY(), position.getZ()));
                    return;
                }
                placed++;
                if (world.getTileEntity(position) instanceof MachineControllerHost host) {
                    host.placedBy(player);
                    if (orientation != BlockOrientation.NORTH_UP) host.transformOrientation(_ -> orientation);
                }
            }
            sender.sendMessage(ExampleText.Built.text(arguments[1], placed, origin.getX(), origin.getY(), origin.getZ()));
            return;
        }
        if (arguments.length == 1 && arguments[0].equals("kit")) {
            EntityPlayerMP player = getCommandSenderAsPlayer(sender);
            give(player, item(VisualExamples.id("press_controller")), 1);
            give(player, item(VisualExamples.id("mixer_controller")), 1);
            give(player, item(VisualExamples.id("sorter_controller")), 1);
            give(player, item(new ResourceLocation("prototype_eng", "input_hatch_4")), 3);
            give(player, item(new ResourceLocation("prototype_eng", "output_hatch_4")), 3);
            give(player, item(new ResourceLocation("prototype_eng", "mixed_hatch_4")), 2);
            give(player, item(new ResourceLocation("prototype_eng", "energy_input_hatch_4")), 2);
            give(player, Item.getItemFromBlock(Blocks.IRON_BLOCK), 3);
            give(player, Item.getItemFromBlock(Blocks.COBBLESTONE), 16);
            give(player, Item.getItemFromBlock(Blocks.LOG), 4);
            give(player, Item.getItemFromBlock(Blocks.SAND), 16);
            give(player, Item.getItemFromBlock(Blocks.GRAVEL), 16);
            give(player, Items.IRON_INGOT, 8);
            give(player, Items.FLINT, 8);
            give(player, Items.WATER_BUCKET, 1);
            give(player, Items.LAVA_BUCKET, 1);
            player.inventoryContainer.detectAndSendChanges();
            player.sendMessage(ExampleText.Kit.text());
            return;
        }
        if (arguments.length >= 5 && arguments.length <= 7 && arguments[0].equals("stock")) {
            var position = parseBlockPos(sender, arguments, 1, false);
            var world = sender.getEntityWorld();
            if (!world.isBlockLoaded(position)) { sender.sendMessage(ExampleText.NoResourceInput.text()); return; }
            var hatch = HatchCapabilities.findResource(world.getTileEntity(position), null);
            var input = hatch == null ? null : hatch.input();
            if (input == null) { sender.sendMessage(ExampleText.NoResourceInput.text()); return; }
            String path = arguments[4].startsWith(VisualExamples.MOD_ID + ":")
                ? arguments[4].substring(VisualExamples.MOD_ID.length() + 1) : arguments[4];
            var recipe = BoundaryExamples.findRecipe(path);
            if (recipe == null) { sender.sendMessage(ExampleText.UnknownRecipe.text(arguments[4])); return; }
            int runs = arguments.length >= 6 ? parseInt(arguments[5], 1, 16) : 1;
            int candidate = arguments.length == 7 ? parseInt(arguments[6], 1, 64) : 1;
            var amounts = BoundaryExamples.stock(recipe, runs, candidate);
            int complete = 0;
            for (var entry : amounts.object2LongEntrySet()) {
                long inserted = input.insert(entry.getKey(), entry.getLongValue(), Actionable.MODULATE);
                if (inserted == entry.getLongValue()) complete++;
                else sender.sendMessage(ExampleText.StockPartial.text(entry.getKey().getDisplayName(), inserted, entry.getLongValue()));
            }
            sender.sendMessage(ExampleText.Stocked.text(recipe.id(), runs, complete, amounts.size()));
            return;
        }
        if (arguments.length >= 4 && arguments.length <= 5 && (arguments[0].equals("charge") || arguments[0].equals("drain"))) {
            boolean drain = arguments[0].equals("drain");
            var position = parseBlockPos(sender, arguments, 1, false);
            var world = sender.getEntityWorld();
            if (!world.isBlockLoaded(position)) { sender.sendMessage((drain ? ExampleText.NoEnergyOutput : ExampleText.NoEnergy).text()); return; }
            var tile = world.getTileEntity(position);
            var energy = tile == null ? null : tile.getCapability(CapabilityEnergy.ENERGY, null);
            if (energy == null || (drain ? !energy.canExtract() : !energy.canReceive())) {
                sender.sendMessage((drain ? ExampleText.NoEnergyOutput : ExampleText.NoEnergy).text()); return;
            }
            int requested = arguments.length == 5 ? parseInt(arguments[4], 1, 1_000_000) : 100_000;
            int added = 0;
            for (int operation = 0; operation < 4096 && added < requested; operation++) {
                int received = drain ? energy.extractEnergy(requested - added, false) : energy.receiveEnergy(requested - added, false);
                if (received == 0) break;
                added = Math.addExact(added, received);
            }
            sender.sendMessage((drain ? ExampleText.Drained : ExampleText.Charged).text(added));
            return;
        }
        throw new WrongUsageException(getUsage(sender));
    }

    private static Item item(ResourceLocation id) {
        return Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(id), "Missing development item " + id);
    }
    private static BlockOrientation orientation(String name) throws CommandException {
        for (var orientation : BlockOrientation.values()) if (orientation.name().equals(name)) return orientation;
        throw new CommandException("Unknown block orientation " + name);
    }
    private static void give(EntityPlayerMP player, Item item, int amount) {
        while (amount > 0) {
            var stack = new ItemStack(item);
            stack.setCount(Math.min(amount, stack.getMaxStackSize()));
            amount -= stack.getCount();
            if (!player.inventory.addItemStackToInventory(stack) && !stack.isEmpty()) player.dropItem(stack, false);
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] arguments, @Nullable BlockPos targetPos) {
        if (arguments.length == 1) return getListOfStringsMatchingLastWord(arguments, "kit", "build", "stock", "charge", "drain", "structure-check");
        if (arguments.length == 2 && arguments[0].equals("kit")) return getListOfStringsMatchingLastWord(arguments, "boundary", "composite");
        if (arguments.length == 2 && arguments[0].equals("build")) {
            var names = new ObjectArrayList<String>(List.of("press", "mixer", "sorter"));
            names.addAll(List.of("pe_run_tests:nested_structure", "pe_run_tests:expandable_structure"));
            for (var scenario : BoundaryExamples.SCENARIOS) names.add(scenario.name());
            names.add("pe_run_tests:native_data_fixed");
            names.add("pe_run_tests:native_data_cross");
            names.add("pe_run_tests:core_world");
            names.add("pe_run_tests:external_filter");
            names.addAll(List.of("pe_run_tests:net_center", "pe_run_tests:net_computer", "pe_run_tests:net_station", "pe_run_tests:net_database", "pe_run_tests:net_device",
                "pe_run_tests:render_default", "pe_run_tests:render_transparent", "pe_run_tests:render_bloom", "pe_run_tests:render_bloom_transparent", "pe_run_tests:render_whole"));
            return getListOfStringsMatchingLastWord(arguments, names);
        }
        if (arguments.length == 6 && arguments[0].equals("build")) {
            var names = new ObjectArrayList<String>();
            for (var orientation : BlockOrientation.values()) names.add(orientation.name());
            return getListOfStringsMatchingLastWord(arguments, names);
        }
        if (arguments.length == 7 && arguments[0].equals("build") && CompositeStructureExamples.name(arguments[1]).equals(CompositeStructureExamples.EXPANDABLE))
            return getListOfStringsMatchingLastWord(arguments, "1", "2", "3", "4");
        if (arguments.length == 5 && arguments[0].equals("stock")) {
            var names = new ObjectArrayList<String>();
            for (var pool : RecipeRegistry.INSTANCE.pools()) {
                if (pool.id().getNamespace().equals(VisualExamples.MOD_ID) || pool.id().getNamespace().equals("pe_run_tests")) {
                    for (var recipe : pool.recipes()) names.add(recipe.id().toString());
                }
            }
            return getListOfStringsMatchingLastWord(arguments, names);
        }
        return List.of();
    }
}
