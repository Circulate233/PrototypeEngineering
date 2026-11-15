package pe.visual.examples.run;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import com.circulation.prototype_eng.util.ExternalEnumValues;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.energy.EnergyHatch;
import com.circulation.prototype_eng.api.gui.EnergyHatchGuiHost;
import com.circulation.prototype_eng.api.gui.ResourceHatchGuiHost;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.storage.ResourceHatchSettings;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ReconfigurableHatch;
import com.circulation.prototype_eng.api.storage.ResourceHatchConfiguration;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.block.Block;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.nbt.NBTTagLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/**
 * User-triggered setup and assertions against loaded tiles and their actual public ports.
 */
public final class RunTestsCommand extends CommandBase {
    private static final Logger LOG = LogManager.getLogger("pe_run_tests");
    private final Object2ObjectLinkedOpenHashMap<String, CapturedExternal> externalCaptures = new Object2ObjectLinkedOpenHashMap<>();

    @Override
    public String getName() {
        return "pe_run_tests";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/pe_run_tests registered | gallery <x> <y> <z> | inspect/hatch-check/cancel <x> <y> <z> | "
            + "assert-resource <x> <y> <z> <input|output> <item-id|water|lava> <amount> [meta] | "
            + "assert-energy <x> <y> <z> <input|output> <FE> | assert-active <x> <y> <z> <min> <max> [recipe-id] | "
            + "data-arm <x> <y> <z> <nonce> | data-verify <x> <y> <z> <nonce> | "
            + "checkpoint/restore <x> <y> <z> <label> | assert-owner <x> <y> <z> <uuid> | "
            + "auto <x> <y> <z> <input:boolean> <output:boolean> | fe-rate <x> <y> <z> <input:-1..> <output:-1..>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 1 && args[0].equals("registered")) {
            CoreCases.runRegisteredChecks();
            tell(sender, "Registered checks returned successfully. Review pe_run_tests PASS/FAIL log entries.");
            return;
        }
        if (RunNetworkRender.worldAction(sender, args)) return;
        if ((args.length == 2 || args.length == 3) && args[0].equals("external-invalid")) {
            var captured = externalCaptures.get(args[1]);
            if (captured == null) throw new CommandException("No captured external hatch for " + args[1]);
            var input = captured.hatch().input();
            var output = captured.hatch().output();
            var iron = key("minecraft:iron_ingot", 0);
            var gold = key("minecraft:gold_nugget", 0);
            boolean retired = !captured.hatch().isValid() && !input.isValid() && !output.isValid()
                && input.insert(iron, 1, Actionable.MODULATE) == 0 && input.extract(iron, 1, Actionable.MODULATE) == 0
                && output.insert(gold, 1, Actionable.MODULATE) == 0 && output.extract(gold, 1, Actionable.MODULATE) == 0
                && input.reserveInsert(captured.inputSnapshot(), iron, 1).status() == ReservationResult.Status.INVALID_STORAGE
                && output.reserveInsert(captured.outputSnapshot(), gold, 1).status() == ReservationResult.Status.INVALID_STORAGE
                && input.snapshot().available().isEmpty() && output.snapshot().available().isEmpty();
            var world = sender.getEntityWorld();
            boolean sameWorld = world == captured.tile().getWorld();
            var replacement = sameWorld && world.isBlockLoaded(captured.tile().getPos()) ? world.getTileEntity(captured.tile().getPos()) : null;
            boolean replaced = replacement instanceof ExternalFilteredHatch && replacement != captured.tile();
            boolean fresh = false;
            if (replaced) {
                var next = HatchCapabilities.findResource(replacement, null);
                fresh = next != null && next.isValid() && next.input().context() != input.context() && next.output().context() != output.context();
            }
            String expected = args.length == 3 ? args[2] : "REPLACED";
            if (!expected.equals("REPLACED") && !expected.equals("REMOVED"))
                throw new CommandException("Expected REPLACED or REMOVED");
            result(sender, "external physical replacement invalidation", sameWorld && retired
                    && (expected.equals("REPLACED") ? fresh : replacement == null),
                "old tile=" + captured.tile() + " old contexts invalid=" + retired + " replacement=" + replacement + " fresh=" + fresh);
            if (retired && (fresh || replacement == null)) externalCaptures.remove(args[1]);
            return;
        }
        if (args.length == 4 && args[0].equals("gallery")) {
            gallery(sender, parseBlockPos(sender, args, 1, false));
            return;
        }
        if (args.length == 4 && args[0].equals("gallery-check")) {
            var origin = parseBlockPos(sender, args, 1, false);
            for (int tier = 1; tier <= 10; tier++)
                for (int mode = 0; mode < 3; mode++)
                    for (int energy = 0; energy < 2; energy++) {
                        var target = origin.add((tier - 1) * 3, 0, mode * 3 + (energy == 0 ? 0 : 10));
                        if (!sender.getEntityWorld().isBlockLoaded(target))
                            throw new CommandException("Gallery chunk not loaded at " + target);
                        var tile = sender.getEntityWorld().getTileEntity(target);
                        if (tile == null) throw new CommandException("Gallery hatch missing at " + target);
                        tell(sender, "Gallery target=" + target + " tier=" + tier + " mode=" + mode + " FE=" + (energy == 1));
                        inspectHatch(sender, tile);
                        hatchCheck(sender, tile);
                    }
            tell(sender, "All 60 gallery targets visited. Read every PASS/FAIL/PRECONDITION above; completion is not an aggregate PASS.");
            return;
        }
        if (args.length < 4) throw new WrongUsageException(getUsage(sender));
        var position = parseBlockPos(sender, args, 1, false);
        var world = sender.getEntityWorld();
        if (!world.isBlockLoaded(position)) throw new CommandException("Target chunk is not loaded");
        var tile = world.getTileEntity(position);
        if (tile == null) throw new CommandException("No tile entity at target");
        switch (args[0]) {
            case "world-query" -> {
                if (args.length != 5 && args.length != 6)
                    throw new WrongUsageException("world-query x y z MATCHED|MISMATCH [orientation]");
                if (!args[4].equals("MATCHED") && !args[4].equals("MISMATCH"))
                    throw new CommandException("Expected MATCHED or MISMATCH");
                var host = controller(tile);
                BlockOrientation expectedOrientation = args.length == 6 ? orientation(args[5]) : null;
                boundary(host, sender, _ -> {
                    if (expectedOrientation != null && host.orientation() != expectedOrientation) {
                        result(sender, "World query actual orientation", false, "actual=" + host.orientation() + " expected=" + expectedOrientation);
                        return;
                    }
                    tell(sender, CoreCases.worldSample(world, position, host.orientation(), args[4].equals("MATCHED")));
                });
            }
            case "external-check" -> {
                requireLength(args, 4);
                if (!(tile instanceof ExternalFilteredHatch external))
                    throw new CommandException("Target is not the external AE tile");
                externalCheck(sender, external);
            }
            case "external-capture" -> {
                requireLength(args, 5);
                if (!(tile instanceof ExternalFilteredHatch external))
                    throw new CommandException("Target is not the external AE tile");
                var hatch = Objects.requireNonNull(HatchCapabilities.findResource(external, null));
                externalCaptures.put(args[4], new CapturedExternal(external, hatch, hatch.input().snapshot(), hatch.output().snapshot()));
                tell(sender, "Captured actual external tile/capability/token contexts as " + args[4] + "; manually remove or replace this physical tile, then external-invalid.");
            }
            case "external-discovered" -> {
                requireLength(args, 7);
                var providerPos = parseBlockPos(sender, args, 4, false);
                if (!world.isBlockLoaded(providerPos))
                    throw new CommandException("External provider chunk is not loaded");
                var provider = world.getTileEntity(providerPos);
                if (!(provider instanceof ExternalFilteredHatch))
                    throw new CommandException("Provider is not the external AE tile");
                var actual = HatchCapabilities.findResource(provider, null);
                var host = controller(tile);
                boundary(host, sender, machine -> {
                    boolean discovered = false;
                    for (var resource : MachineHatches.get(machine).resources())
                        if (resource == actual) discovered = true;
                    result(sender, "actual structure discovers external capability", host.formed() && actual != null && discovered,
                        "formed=" + host.formed() + " provider=" + providerPos + " actual capability=" + actual + " discovered=" + discovered);
                });
            }
            case "inspect" -> {
                requireLength(args, 4);
                if (tile instanceof MachineControllerHost host) boundary(host, sender, machine -> {
                    tell(sender, "type=" + host.type().id() + " formed=" + host.formed() + " orientation=" + host.orientation()
                        + " identity=" + host.savedIdentity() + " mode=" + machine.executionMode());
                    var data = machine.components().get(DataComponents.MACHINE);
                    tell(sender, "ordinary machine data=" + (data == null ? "absent" : data.snapshot()));
                    for (var recipeHost : machine.recipeHosts())
                        for (var process : recipeHost.activeProcesses()) {
                            var processData = process.components().get(DataComponents.PROCESS);
                            tell(sender, "process=" + process.recipe().id() + " seed=" + process.seed() + " state=" + process.state()
                                + " ordinary process data=" + (processData == null ? "absent" : processData.snapshot()));
                        }
                });
                else inspectHatch(sender, tile);
            }
            case "assert-resource", "assert-resource-range" -> {
                boolean range = args[0].equals("assert-resource-range");
                if (args.length != (range ? 8 : 7) && args.length != (range ? 9 : 8))
                    throw new WrongUsageException(getUsage(sender));
                var storage = resource(tile, args[4]);
                long actual = storage.amount(key(args[5], args.length == (range ? 9 : 8) ? parseInt(args[range ? 8 : 7], 0, 32767) : 0));
                long expected = parseLong(args[6], 0, Long.MAX_VALUE);
                long maximum = range ? parseLong(args[7], expected, Long.MAX_VALUE) : expected;
                result(sender, "resource " + position + " " + args[4] + " " + args[5], actual >= expected && actual <= maximum, "actual=" + actual + " expected=" + expected + ".." + maximum);
            }
            case "assert-weighted" -> {
                requireLength(args, 4);
                var storage = resource(tile, "output");
                var actual = storage.snapshot().available();
                long flint = storage.amount(key("minecraft:flint", 0));
                long iron = storage.amount(key("minecraft:iron_nugget", 0));
                long gold = storage.amount(key("minecraft:gold_nugget", 0));
                long total = Math.addExact(Math.addExact(flint, iron), gold);
                result(sender, "weighted single output", total == 1 && actual.size() == 1, "actual=" + actual + " expected=one of flint/iron_nugget/gold_nugget x1");
            }
            case "assert-energy" -> {
                requireLength(args, 6);
                var storage = energy(tile, args[4]);
                long expected = parseLong(args[5], 0, Long.MAX_VALUE);
                result(sender, "energy " + position + " " + args[4], storage.storedEnergy() == expected,
                    "actual=" + storage.storedEnergy() + " expected=" + expected);
            }
            case "assert-active" -> {
                if (args.length != 6 && args.length != 7) throw new WrongUsageException(getUsage(sender));
                int minimum = parseInt(args[4], 0, 65536), maximum = parseInt(args[5], minimum, 65536);
                ResourceLocation recipe = args.length == 7 ? new ResourceLocation(args[6]) : null;
                boundary(controller(tile), sender, machine -> {
                    int count = 0;
                    boolean own = true;
                    for (var host : machine.recipeHosts())
                        for (var process : host.activeProcesses()) {
                            own &= process.owner() == machine && process.host() == host;
                            if (recipe == null || process.recipe().id().equals(recipe)) count++;
                        }
                    result(sender, "active roster", own && count >= minimum && count <= maximum,
                        "actual=" + count + " range=" + minimum + ".." + maximum + " exact-owner=" + own);
                });
            }
            case "cancel" -> {
                requireLength(args, 4);
                boundary(controller(tile), sender, machine -> {
                    int cancelled = 0;
                    for (var host : machine.recipeHosts()) cancelled = Math.addExact(cancelled, host.cancelAll());
                    tell(sender, "Cancelled=" + cancelled + "; use assert-active and exact output assertion after next owner boundary.");
                });
            }
            case "cancel-check" -> {
                requireLength(args, 4);
                var coal = key("minecraft:coal", 0);
                var water = key("water", 0);
                var nuggets = key("minecraft:iron_nugget", 0);
                var diamonds = key("minecraft:diamond", 0);
                boundary(controller(tile), sender, machine -> {
                    var data = machine.components().get(DataComponents.MACHINE);
                    if (data == null || NativeRunCases.number(data, "run_paused") != 1) {
                        tell(sender, "PRECONDITION pause a fresh native four-cycle round before cancel-check");
                        return;
                    }
                    long ticks = 0;
                    int active = 0;
                    for (var host : machine.recipeHosts())
                        for (var process : host.activeProcesses()) {
                            if (!process.recipe().id().getPath().startsWith("native_cycle_")) {
                                tell(sender, "PRECONDITION cancel-check accepts only native_cycle processes");
                                return;
                            }
                            ticks = Math.addExact(ticks, process.state().successfulTicks());
                            active++;
                        }
                    var hatches = MachineHatches.get(machine);
                    long beforeCoal = resourceAmount(hatches, coal, false), beforeWater = resourceAmount(hatches, water, false);
                    long beforeNuggets = resourceAmount(hatches, nuggets, true), beforeDiamonds = resourceAmount(hatches, diamonds, true);
                    long beforeEnergy = 0;
                    for (var input : hatches.energyInputs())
                        beforeEnergy = Math.addExact(beforeEnergy, input.storedEnergy());
                    boolean preflight = active > 0 && beforeNuggets == ticks && beforeDiamonds == 0;
                    if (!preflight) {
                        tell(sender, "PRECONDITION fresh unfinished round: active=" + active + " successful-ticks=" + ticks
                            + " nuggets=" + beforeNuggets + " diamonds=" + beforeDiamonds);
                        return;
                    }
                    int cancelled = 0;
                    for (var host : machine.recipeHosts()) cancelled = Math.addExact(cancelled, host.cancelAll());
                    long afterEnergy = 0;
                    for (var input : hatches.energyInputs())
                        afterEnergy = Math.addExact(afterEnergy, input.storedEnergy());
                    boolean retained = cancelled == active && resourceAmount(hatches, coal, false) == beforeCoal
                        && resourceAmount(hatches, water, false) == beforeWater && resourceAmount(hatches, nuggets, true) == beforeNuggets
                        && resourceAmount(hatches, diamonds, true) == 0 && beforeEnergy == afterEnergy;
                    result(sender, "cancel committed tick ledger", retained, "active=" + active + " cancelled=" + cancelled
                        + " ticks=" + ticks + " coal=" + beforeCoal + " water=" + beforeWater + " nuggets=" + beforeNuggets
                        + " diamonds=0 FE=" + beforeEnergy + " afterFE=" + afterEnergy);
                });
            }
            case "data-arm", "data-verify" -> {
                requireLength(args, 5);
                boundary(controller(tile), sender, machine -> {
                    var data = Objects.requireNonNull(machine.components().get(DataComponents.MACHINE), "Machine has no ordinary data declaration");
                    if (args[0].equals("data-arm")) {
                        data.set("last_tick_pending", new NBTTagString(args[4]));
                        tell(sender, "Armed nonce=" + args[4] + "; actual last_tick_nonce is written by next ordinary post callback.");
                    } else {
                        result(sender, "ordinary last tick", new NBTTagString(args[4]).equals(data.get("last_tick_nonce")),
                            "actual=" + data.get("last_tick_nonce") + " expected=" + args[4] + " revision=" + data.revision());
                    }
                });
            }
            case "pause", "resume" -> {
                requireLength(args, 4);
                boundary(controller(tile), sender, machine -> {
                    if (machine.components().get(NativeRunCases.MACHINE_TRACE) == null) {
                        tell(sender, "PRECONDITION pause/resume requires native data owner, not an ordinary legacy visual owner");
                        return;
                    }
                    var data = Objects.requireNonNull(machine.components().get(DataComponents.MACHINE));
                    data.set("run_paused", new NBTTagLong(args[0].equals("pause") ? 1 : 0));
                    tell(sender, "Recipe work gate=" + machine.recipeWorkAllowed() + "; ordinary data callbacks keep running.");
                });
            }
            case "assert-completed" -> {
                requireLength(args, 6);
                var recipe = new ResourceLocation(args[4]);
                long expected = parseLong(args[5], 0, Long.MAX_VALUE);
                boundary(controller(tile), sender, machine -> {
                    var data = Objects.requireNonNull(machine.components().get(DataComponents.MACHINE));
                    long actual = NativeRunCases.number(data, "completed:" + recipe);
                    result(sender, "completed " + recipe, actual == expected, "actual=" + actual + " expected=" + expected
                        + " last-successful-ticks=" + NativeRunCases.number(data, "last_completed_ticks"));
                });
            }
            case "checkpoint", "restore" -> {
                requireLength(args, 5);
                if (!args[4].matches("[a-zA-Z0-9_-]{1,64}"))
                    throw new CommandException("Use a label containing letters, numbers, _ or -");
                var host = controller(tile);
                boundary(host, sender, machine -> checkpoint(sender, host, machine, args[4], args[0].equals("restore")));
            }
            case "assert-owner" -> {
                requireLength(args, 5);
                var host = controller(tile);
                boundary(host, sender, _ -> {
                    var identity = host.savedIdentity();
                    result(sender, "physical owner", identity != null && identity.ownerId().toString().equals(args[4]), "actual=" + identity);
                });
            }
            case "hatch-check" -> {
                requireLength(args, 4);
                hatchCheck(sender, tile);
            }
            case "profile-resource" -> {
                requireLength(args, 8);
                if (!(tile instanceof ReconfigurableHatch target) || !(target.effectiveConfiguration() instanceof ResourceHatchConfiguration profile))
                    throw new CommandException("Target has no public resource profile");
                var previous = args[4].equals("input") ? profile.input() : args[4].equals("output") ? profile.output() : null;
                if (previous == null) throw new CommandException("Physical resource side does not exist");
                int slots = parseInt(args[5], 1, 1_000_000);
                long itemCapacity = parseLong(args[6], 1, Long.MAX_VALUE), fluidCapacity = parseLong(args[7], 1, Long.MAX_VALUE);
                var capacities = new Reference2LongArrayMap<>(previous.capacities());
                capacities.put(key("minecraft:iron_ingot", 0).getType(), itemCapacity);
                capacities.put(key("water", 0).getType(), fluidCapacity);
                var side = new ResourceHatchConfiguration.Side(slots, capacities);
                var request = new ResourceHatchConfiguration(args[4].equals("input") ? side : profile.input(), args[4].equals("output") ? side : profile.output());
                var status = target.requestConfiguration(request);
                tell(sender, "Actual profile request status=" + status + " request=" + request
                    + "; poll profile-status and assert-profile after owner boundary. Existing resources are retained.");
            }
            case "profile-status" -> {
                requireLength(args, 5);
                if (!(tile instanceof ReconfigurableHatch target))
                    throw new CommandException("Target has no public reconfiguration status");
                result(sender, "profile status", target.configurationStatus().name().equals(args[4]), "actual=" + target.configurationStatus() + " expected=" + args[4]);
            }
            case "assert-profile" -> {
                requireLength(args, 8);
                if (!(tile instanceof ResourceHatchGuiHost host))
                    throw new CommandException("Target has no public resource profile");
                var storage = args[4].equals("input") ? host.storage().input() : args[4].equals("output") ? host.storage().output() : null;
                if (storage == null) throw new CommandException("Physical resource side does not exist");
                int slots = parseInt(args[5], 1, 1_000_000);
                long item = parseLong(args[6], 1, Long.MAX_VALUE), fluid = parseLong(args[7], 1, Long.MAX_VALUE);
                long actualItem = storage.getCapacity(key("minecraft:iron_ingot", 0).getType()), actualFluid = storage.getCapacity(key("water", 0).getType());
                result(sender, "effective resource side profile", storage.size() == slots && actualItem == item && actualFluid == fluid,
                    "actual slots=" + storage.size() + " item=" + actualItem + " fluid=" + actualFluid + " expected=" + slots + "/" + item + "/" + fluid);
            }
            case "auto" -> {
                requireLength(args, 6);
                boolean input = parseBoolean(args[4]), output = parseBoolean(args[5]);
                if (tile instanceof ResourceHatchSettings settings) {
                    settings.setAutoInput(input);
                    settings.setAutoOutput(output);
                    result(sender, "resource auto switches", settings.autoInput() == input && settings.autoOutput() == output,
                        "actual=" + settings.autoInput() + "/" + settings.autoOutput());
                } else if (tile instanceof EnergyHatchGuiHost settings) {
                    settings.setAutoInput(input);
                    settings.setAutoOutput(output);
                    result(sender, "FE auto switches", settings.autoInput() == input && settings.autoOutput() == output,
                        "actual=" + settings.autoInput() + "/" + settings.autoOutput());
                } else throw new CommandException("Target has no public auto IO settings");
            }
            case "fe-rate" -> {
                requireLength(args, 6);
                if (!(tile instanceof EnergyHatchGuiHost settings))
                    throw new CommandException("Target has no FE settings");
                long input = parseLong(args[4], -1, Long.MAX_VALUE), output = parseLong(args[5], -1, Long.MAX_VALUE);
                settings.setMaxInputRate(input);
                settings.setMaxOutputRate(output);
                var port = tile.getCapability(CapabilityEnergy.ENERGY, null);
                boolean zero = port != null && (input != 0 || port.receiveEnergy(1, true) == 0)
                    && (output != 0 || port.extractEnergy(1, true) == 0);
                result(sender, "FE settings and zero port", settings.maxInputRate() == input && settings.maxOutputRate() == output && zero,
                    "actual=" + settings.maxInputRate() + "/" + settings.maxOutputRate() + " zero-port=" + zero);
            }
            default -> throw new WrongUsageException(getUsage(sender));
        }
    }

    private static void checkpoint(ICommandSender sender, MachineControllerHost host, MachineInstance machine, String label, boolean restore) {
        var file = Path.of("pe_run_tests", "evidence", label + ".properties");
        var data = Objects.requireNonNull(machine.components().get(DataComponents.MACHINE));
        var identity = Objects.requireNonNull(host.savedIdentity());
        var captured = new Properties();
        captured.setProperty("dimension", Integer.toString(sender.getEntityWorld().provider.getDimension()));
        captured.setProperty("position", host.blockEntity().getPos().toString());
        captured.setProperty("machine", identity.machineId().toString());
        captured.setProperty("owner", identity.ownerId().toString());
        captured.setProperty("type", host.type().id().toString());
        captured.setProperty("ticks", Long.toString(NativeRunCases.number(data, "machine_ticks")));
        captured.setProperty("nonce", Objects.toString(data.get("last_tick_nonce"), ""));
        for (var recipeHost : machine.recipeHosts())
            for (var process : recipeHost.activeProcesses()) {
                var processData = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
                String prefix = "process." + process.seed();
                captured.setProperty(prefix + ".recipe", process.recipe().id().toString());
                captured.setProperty(prefix + ".revision", Objects.requireNonNull(RecipeRegistry.INSTANCE.snapshot().revision(process.recipe()), "Process has no declared revision"));
                captured.setProperty(prefix + ".ticks", Long.toString(process.state().successfulTicks()));
                captured.setProperty(prefix + ".observed", Long.toString(NativeRunCases.number(processData, "observed_ticks")));
            }
        try {
            if (!restore) {
                Files.createDirectories(file.getParent());
                try (var out = Files.newOutputStream(file)) {
                    captured.store(out, "Actual joined PE owner checkpoint; user-triggered only");
                }
                tell(sender, "Recorded " + file.toAbsolutePath() + " " + captured + "; stop/rejoin or unload/reload, then restore same label.");
                return;
            }
            var expected = new Properties();
            try (var in = Files.newInputStream(file)) {
                expected.load(in);
            }
            boolean same = true;
            for (String key : List.of("dimension", "position", "machine", "owner", "type", "nonce"))
                same &= Objects.equals(expected.getProperty(key), captured.getProperty(key));
            same &= Long.parseLong(captured.getProperty("ticks")) >= Long.parseLong(expected.getProperty("ticks"));
            for (String key : expected.stringPropertyNames())
                if (key.startsWith("process.")) {
                    String actual = captured.getProperty(key);
                    if (key.endsWith(".recipe") || key.endsWith(".revision"))
                        same &= Objects.equals(expected.getProperty(key), actual);
                    else same &= actual != null && Long.parseLong(actual) >= Long.parseLong(expected.getProperty(key));
                }
            result(sender, "save/rejoin or unload/reload", same, "expected=" + expected + " actual=" + captured
                + " (keep checkpoint processes alive until verification)");
        } catch (IOException failure) {
            tell(sender, "FAIL checkpoint IO: " + failure.getMessage());
        }
    }

    private static void gallery(ICommandSender sender, BlockPos origin) throws CommandException {
        var player = getCommandSenderAsPlayer(sender);
        var world = sender.getEntityWorld();
        var placements = new BlockPos2ObjectMap<Block>();
        String[] modes = {"input", "output", "mixed"};
        for (int tier = 1; tier <= 10; tier++)
            for (int mode = 0; mode < 3; mode++) {
                String suffix = modes[mode] + "_hatch_" + tier;
                placements.put(origin.add((tier - 1) * 3, 0, mode * 3), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng", suffix))));
                placements.put(origin.add((tier - 1) * 3, 0, mode * 3 + 10), Objects.requireNonNull(ForgeRegistries.BLOCKS.getValue(new ResourceLocation("prototype_eng", "energy_" + suffix))));
            }
        for (var position : placements.keySet())
            if (position.getY() < 0 || position.getY() >= world.getHeight()
                || !world.getWorldBorder().contains(position) || !world.isBlockLoaded(position)
                || !world.isAirBlock(position) || !world.isBlockModifiable(player, position))
                throw new CommandException("Gallery requires loaded, modifiable air at " + position);
        int placed = 0;
        for (var entry : placements.entrySet()) {
            if (!world.setBlockState(entry.getKey(), entry.getValue().getDefaultState(), 3))
                throw new CommandException("Gallery placement refused after " + placed + " blocks");
            placed++;
        }
        tell(sender, "Placed " + placed + " real hatches: tiers x=0,3,...27; input/output/mixed z=0,3,6; FE rows z=10,13,16. No stock or charge.");
    }

    private static void inspectHatch(ICommandSender sender, TileEntity tile) throws CommandException {
        if (tile instanceof ResourceHatchGuiHost host) {
            tell(sender, "resource tier=" + host.tier().level() + " mode=" + host.mode());
            if (host.storage().input() != null) tell(sender, "input slots=" + host.storage().input().size()
                + " item capacity/slot=" + host.storage().input().getCapacity(key("minecraft:iron_ingot", 0).getType())
                + " fluid capacity/slot=" + host.storage().input().getCapacity(key("water", 0).getType())
                + " actual=" + host.storage().input().snapshot().available());
            if (host.storage().output() != null) tell(sender, "output slots=" + host.storage().output().size()
                + " item capacity/slot=" + host.storage().output().getCapacity(key("minecraft:iron_ingot", 0).getType())
                + " fluid capacity/slot=" + host.storage().output().getCapacity(key("water", 0).getType())
                + " actual=" + host.storage().output().snapshot().available());
        } else if (tile instanceof EnergyHatchGuiHost host) {
            tell(sender, "FE tier=" + host.tier().level() + " mode=" + host.mode() + " rates=" + host.maxInputRate() + "/" + host.maxOutputRate());
            if (host.inputStorage() != null)
                tell(sender, "input=" + host.inputStorage().storedEnergy() + " capacity=" + host.inputStorage().capacity());
            if (host.outputStorage() != null)
                tell(sender, "output=" + host.outputStorage().storedEnergy() + " capacity=" + host.outputStorage().capacity());
        } else throw new CommandException("Target is not a controller or public hatch GUI host");
    }

    private static void hatchCheck(ICommandSender sender, TileEntity tile) throws CommandException {
        if (tile instanceof ResourceHatchGuiHost host) {
            var input = host.storage().input();
            var output = host.storage().output();
            result(sender, "resource public direction", (input != null) == host.mode().hasInput() && (output != null) == host.mode().hasOutput(), host.mode().toString());
            if (input != null && output != null)
                result(sender, "mixed resource independent sides", input.context() != output.context(), "input=" + input.context() + " output=" + output.context());
            boolean empty = (input == null || input.snapshot().available().isEmpty()) && (output == null || output.snapshot().available().isEmpty());
            var items = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            var fluids = tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
            if (empty && items != null && fluids != null) {
                var iron = new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:iron_ingot"))));
                var remaining = items.insertItem(0, iron, true);
                int filled = fluids.fill(new FluidStack(FluidRegistry.WATER, 1), false);
                result(sender, "Forge resource simulation direction", (host.mode().hasInput() ? remaining.isEmpty() && filled == 1 : remaining.getCount() == 1 && filled == 0)
                        && (input == null || input.amount(key("minecraft:iron_ingot", 0)) == 0)
                        && (input == null || input.amount(key("water", 0)) == 0),
                    "item remainder=" + remaining.getCount() + " simulated water=" + filled + " real balance unchanged");
            } else
                tell(sender, "PRECONDITION external simulation check requires empty hatch with Forge item and fluid ports; reserve probes still run below.");
            for (String side : List.of("input", "output")) {
                var storage = side.equals("input") ? input : output;
                if (storage == null) continue;
                result(sender, side + " slot profile", storage.size() > 0, "actual slots=" + storage.size());
                rollbackProbe(sender, side + " item", storage, key("minecraft:iron_ingot", 0));
                rollbackProbe(sender, side + " fluid", storage, key("water", 0));
            }
        } else if (tile instanceof EnergyHatchGuiHost host) {
            var input = host.inputStorage();
            var output = host.outputStorage();
            result(sender, "FE public direction", (input != null) == host.mode().hasInput() && (output != null) == host.mode().hasOutput(), host.mode().toString());
            if (input != null && output != null)
                result(sender, "mixed FE independent sides", input.context() != output.context(), "input=" + input.context() + " output=" + output.context());
            if (input != null) rollbackEnergy(sender, "input", input);
            if (output != null) rollbackEnergy(sender, "output", output);
            var port = tile.getCapability(CapabilityEnergy.ENERGY, null);
            result(sender, "Forge FE physical direction", port != null && port.canReceive() == host.mode().hasInput()
                && port.canExtract() == host.mode().hasOutput(), "actual=" + port);
        } else throw new CommandException("Target is not a public hatch host");
    }

    private static void rollbackProbe(ICommandSender sender, String id, ResourceStorage storage, AEKey key) {
        var context = storage.context();
        context.lock();
        try {
            long before = storage.amount(key);
            var reservation = storage.reserveInsert(storage.snapshot(), key, 1);
            if (reservation.status() != ReservationResult.Status.ACCEPTED) {
                tell(sender, "PRECONDITION " + id + ": need free capacity/no active machine reservations; actual=" + reservation.status());
                return;
            }
            var token = Objects.requireNonNull(reservation.reservation());
            boolean applied = false;
            try {
                if (token.canApply()) {
                    token.apply();
                    applied = true;
                }
            } finally {
                token.rollback();
            }
            result(sender, id + " real reserve/apply/rollback", applied && storage.amount(key) == before
                    && token.resolution() == ResourceReservation.Resolution.ROLLED_BACK,
                "before=" + before + " after=" + storage.amount(key) + " resolution=" + token.resolution());
        } finally {
            context.unlock();
        }
    }

    private static void externalCheck(ICommandSender sender, ExternalFilteredHatch tile) throws CommandException {
        var hatch = Objects.requireNonNull(HatchCapabilities.findResource(tile, null));
        var input = hatch.input();
        var output = hatch.output();
        var iron = key("minecraft:iron_ingot", 0);
        var gold = key("minecraft:gold_nugget", 0);
        var diamond = key("minecraft:diamond", 0);
        var water = key("water", 0);
        var lava = key("lava", 0);
        if (!input.snapshot().available().isEmpty() || !output.snapshot().available().isEmpty()) {
            tell(sender, "PRECONDITION external-check needs both actual sides empty; finish/extract previous products first");
            return;
        }
        boolean rejected = input.insert(diamond, 1, Actionable.MODULATE) == 0 && input.insert(lava, 1, Actionable.MODULATE) == 0
            && output.insert(iron, 1, Actionable.MODULATE) == 0 && output.insert(lava, 1, Actionable.MODULATE) == 0
            && input.reserveInsert(input.snapshot(), diamond, 1).status() == ReservationResult.Status.INSUFFICIENT
            && output.reserveInsert(output.snapshot(), iron, 1).status() == ReservationResult.Status.INSUFFICIENT
            && !tile.storage().input().tryReplaceSlot(0, null, new ae2.api.stacks.GenericStack(diamond, 1));
        result(sender, "external actual admission filter", rejected, "diamond/lava input and iron/lava output denied; reserved and atomic-slot admissions denied");
        boolean capacity = input.insert(iron, 129, Actionable.SIMULATE) == 128 && input.insert(water, 2001, Actionable.SIMULATE) == 2000
            && output.insert(gold, 129, Actionable.SIMULATE) == 128 && output.insert(water, 2001, Actionable.SIMULATE) == 2000;
        result(sender, "external actual bounded capacity", capacity, "empty-side real simulation item max=128 water max=2000mB");
        var itemPort = tile.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        var fluidPort = tile.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, null);
        boolean externalPorts = itemPort != null && fluidPort != null
            && itemPort.insertItem(0, new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:diamond")))), true).getCount() == 1
            && itemPort.insertItem(0, new ItemStack(Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:iron_ingot")))), true).isEmpty()
            && fluidPort.fill(new FluidStack(FluidRegistry.LAVA, 1), false) == 0
            && fluidPort.fill(new FluidStack(FluidRegistry.WATER, 1), false) == 1;
        result(sender, "external Forge item/fluid filter", externalPorts, "actual rejected diamond/lava and accepted iron/water simulation");
        rollbackProbe(sender, "external input iron", input, iron);
        rollbackProbe(sender, "external input water", input, water);
        rollbackProbe(sender, "external output gold", output, gold);
        rollbackProbe(sender, "external output water", output, water);
        result(sender, "external probes retain both balances", input.snapshot().available().isEmpty() && output.snapshot().available().isEmpty()
            && input.context() != output.context(), "actual input=" + input.snapshot().available() + " output=" + output.snapshot().available());
    }

    private static void rollbackEnergy(ICommandSender sender, String id, EnergyHatch storage) {
        var context = storage.context();
        context.lock();
        try {
            long before = storage.storedEnergy();
            var reservation = storage.reserveReceiveEnergy(storage.energySnapshot(), 1);
            if (reservation.status() != ReservationResult.Status.ACCEPTED) {
                tell(sender, "PRECONDITION FE " + id + ": need free capacity and transfer rate >=1; actual=" + reservation.status());
                return;
            }
            var token = Objects.requireNonNull(reservation.reservation());
            boolean applied = false;
            try {
                if (token.canApply()) {
                    token.apply();
                    applied = true;
                }
            } finally {
                token.rollback();
            }
            result(sender, "FE " + id + " real reserve/apply/rollback", applied && storage.storedEnergy() == before
                    && token.resolution() == ResourceReservation.Resolution.ROLLED_BACK,
                "before=" + before + " after=" + storage.storedEnergy() + " resolution=" + token.resolution());
        } finally {
            context.unlock();
        }
    }

    private static ResourceStorage resource(TileEntity tile, String side) throws CommandException {
        var hatch = HatchCapabilities.findResource(tile, null);
        if (hatch == null) throw new CommandException("Target has no real resource capability");
        var storage = side.equals("input") ? hatch.input() : side.equals("output") ? hatch.output() : null;
        if (storage == null) throw new CommandException("Target has no " + side + " resource side");
        return storage;
    }

    private static long resourceAmount(MachineHatches hatches, AEKey key, boolean output) {
        long total = 0;
        for (var hatch : hatches.resources()) {
            var storage = output ? hatch.output() : hatch.input();
            if (storage != null) total = Math.addExact(total, storage.amount(key));
        }
        return total;
    }

    private static EnergyHatch energy(TileEntity tile, String side) throws CommandException {
        if (!(tile instanceof EnergyHatchGuiHost host)) throw new CommandException("Target has no public FE host");
        var storage = side.equals("input") ? host.inputStorage() : side.equals("output") ? host.outputStorage() : null;
        if (storage == null) throw new CommandException("Target has no " + side + " FE side");
        return storage;
    }

    private static AEKey key(String name, int meta) throws CommandException {
        if (name.equals("water") || name.equals("lava"))
            return AEFluidKey.of(name.equals("water") ? FluidRegistry.WATER : FluidRegistry.LAVA);
        var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(name));
        if (item == null) throw new CommandException("Unknown item " + name);
        return Objects.requireNonNull(AEItemKey.of(new ItemStack(item, 1, meta)));
    }

    private static MachineControllerHost controller(TileEntity tile) throws CommandException {
        if (!(tile instanceof MachineControllerHost host))
            throw new CommandException("Target is not a PE controller host");
        return host;
    }

    private static void boundary(MachineControllerHost host, ICommandSender sender, java.util.function.Consumer<MachineInstance> action) {
        host.readGuiAtBoundary(() -> {
            var machine = host.machine();
            if (machine == null) {
                tell(sender, "PRECONDITION controller has no attached runtime yet");
                return;
            }
            action.accept(machine);
        });
    }

    private static void requireLength(String[] args, int count) throws WrongUsageException {
        if (args.length != count) throw new WrongUsageException("Wrong number of pe_run_tests arguments");
    }

    private static BlockOrientation orientation(String name) throws CommandException {
        for (var orientation : ExternalEnumValues.BLOCK_ORIENTATIONS)
            if (orientation.name().equals(name)) return orientation;
        throw new CommandException("Unknown block orientation " + name);
    }

    private record CapturedExternal(ExternalFilteredHatch tile, ResourceHatch hatch,
                                    com.circulation.prototype_eng.api.storage.StorageSnapshot inputSnapshot,
                                    com.circulation.prototype_eng.api.storage.StorageSnapshot outputSnapshot) {
    }

    public static void tell(ICommandSender sender, String message) {
        LOG.info(message);
        sender.sendMessage(new TextComponentString("[pe_run_tests] " + message));
    }

    public static void result(ICommandSender sender, String id, boolean passed, String actual) {
        tell(sender, (passed ? "PASS " : "FAIL ") + id + ": " + actual);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1)
            return getListOfStringsMatchingLastWord(args, "registered", "gallery", "gallery-check", "inspect", "hatch-check", "assert-resource", "assert-resource-range", "assert-weighted", "assert-energy",
                "assert-active", "cancel", "cancel-check", "data-arm", "data-verify", "checkpoint", "restore", "assert-owner", "auto", "fe-rate", "pause", "resume", "assert-completed",
                "profile-resource", "profile-status", "assert-profile", "external-check", "external-capture", "external-invalid", "external-discovered", "world-query");
        if (args.length == 5 && (args[0].equals("assert-resource") || args[0].equals("assert-energy")))
            return getListOfStringsMatchingLastWord(args, "input", "output");
        return List.of();
    }
}
