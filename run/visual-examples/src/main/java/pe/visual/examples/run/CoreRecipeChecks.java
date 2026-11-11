package pe.visual.examples.run;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.*;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.*;
import com.circulation.prototype_eng.api.recipe.requirement.*;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static pe.visual.examples.run.CoreCases.*;

/** Exercises the script-created canonical requirements through real stage transactions. */
final class CoreRecipeChecks {
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("pe_run_tests");
    private static final RecipeRequirementType<Integer> NUMBER = new RecipeRequirementType<>(id("core_number"), new RecipeRequirementSystem<>() {
        @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Integer> requirement) {
            var data = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
            var before = data.get("number");
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() { data.set("number", new NBTTagInt(requirement.payload())); }
                @Override public void rollback() { data.set("number", before); }
            };
        }
    });
    private static final RecipeRequirement<Integer> TARGET = NUMBER.requirement(id("core_external_target"), 10);
    private static final RecipeOverlayType<Integer, Integer> ADD = new RecipeOverlayType<>(id("core_external_add"), NUMBER, Math::addExact);
    private static final NbtCodec<Integer> INTEGER_CODEC = new NbtCodec<>() {
        @Override public NBTTagCompound encode(Integer value) { var tag = new NBTTagCompound(); tag.setInteger("value", value); return tag; }
        @Override public Integer decode(NBTTagCompound tag) { return tag.getInteger("value"); }
    };
    private static ZenRequirementOverlay currentOverlay = new ZenRequirementOverlay(TARGET, ADD, 5);
    private static ZenRecipe currentRecipe = new ZenRecipe(RecipeProcessNbt.builder(
        new MachineRecipe(id("core_external"), 2, List.of(TARGET)), "run-v1")
        .component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC).overlay(ADD, INTEGER_CODEC)
        .validator(process -> {
            int expected = RecipeOverlays.getOrCreate(process).resolve(TARGET).payload();
            if (Objects.requireNonNull(process.components().get(DataComponents.PROCESS)).get("number") instanceof NBTTagInt value && value.getInt() != expected)
                throw new IllegalArgumentException("External value differs from exact overlay");
        }).build());
    private CoreRecipeChecks() {}
    static ZenRequirement externalRequirement() { return new ZenRequirement(TARGET); }
    static ZenRequirementOverlay externalOverlay() { return currentOverlay; }
    static ZenRecipe externalRecipe() { return currentRecipe; }

    static Buffers buffers(long material, long energy) {
        var input = new BufferedResourceHatch(HatchMode.INPUT, 2, Map.of(MATERIAL_TYPE, 1000L, SECONDARY_TYPE, 2000L), () -> {});
        var output = new BufferedResourceHatch(HatchMode.OUTPUT, 2, Map.of(MATERIAL_TYPE, 1000L, SECONDARY_TYPE, 2000L), () -> {});
        if (material > 0) Objects.requireNonNull(input.input()).insert(MATERIAL, material, Actionable.MODULATE);
        var in = new EnergyBuffer(1000, () -> {});
        var out = new EnergyBuffer(1000, () -> {});
        in.receiveEnergy(energy, Actionable.MODULATE);
        return new Buffers(input, output, in, out, new MachineHatches(List.of(input, output), List.of(in), List.of(out)));
    }
    record Buffers(BufferedResourceHatch input, BufferedResourceHatch output, EnergyBuffer energyIn, EnergyBuffer energyOut, MachineHatches hatches) {}
    private static MachineInstance owner(MachineHatches hatches) {
        var owner = new MachineInstance(); owner.components().put(MachineHatches.TYPE, hatches); return owner;
    }
    private static RecipeProcess process(String name, Buffers buffers) {
        var process = new RecipeProcess(owner(buffers.hatches()), recipe(name).recipe(), 17);
        process.components().create(DataComponents.PROCESS, process);
        return process;
    }
    private static void complete(String id, RecipeProcess process, int ticks) {
        boolean successful = true;
        for (int i = 0; i < ticks; i++) successful &= process.tick() == ProcessResult.SUCCESS;
        NativeChecks.check(id, successful && process.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED);
    }

    static void run() {
        var stage = buffers(100, 100);
        var process = process("stages", stage);
        NativeChecks.check("core_stage_first_tick", process.tick() == ProcessResult.SUCCESS
            && stage.input().input().amount(MATERIAL) == 88 && stage.output().output().amount(PRODUCT) == 3
            && stage.energyIn().storedEnergy() == 88 && stage.energyOut().storedEnergy() == 2);
        NativeChecks.check("core_stage_end", process.tick() == ProcessResult.SUCCESS
            && process.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED
            && stage.input().input().amount(MATERIAL) == 86 && stage.output().output().amount(PRODUCT) == 10
            && stage.energyIn().storedEnergy() == 81 && stage.energyOut().storedEnergy() == 15);

        for (var name : List.of("weighted", "chance_zero", "chance_attribute", "attributes")) {
            var buffers = buffers(100, 100);
            var test = process(name, buffers);
            complete("core_" + name + "_completed", test, 1);
            // Fixed END outputs and sampled outputs add together: 99*2 + (2+3)*2 = 208.
            long expected = name.equals("weighted") ? 208 : name.equals("attributes") ? 6 : name.equals("chance_attribute") ? 3 : 0;
            var output = buffers.output().output();
            var keys = new StringBuilder();
            output.snapshot().available().forEachEntry((key, amount) -> keys.append(key.getId()).append('=').append(amount).append(' '));
            LOG.info("CHECK core_{}_output expected={} actual={} seed={} state={} outputKeys={}", name,
                expected, output.amount(PRODUCT), test.seed(), test.state(), keys);
            NativeChecks.check("core_" + name + "_output", output.amount(PRODUCT) == expected);
            if (name.equals("weighted")) {
                LOG.info("CHECK core_weighted_zero_weight expected=0 actual={} seed={}", output.amount(SECONDARY), test.seed());
                NativeChecks.check("core_weighted_zero_weight", output.amount(SECONDARY) == 0);
            }
            if (name.equals("attributes")) NativeChecks.check("core_attribute_end_removal", RecipeParallelism.effective(test) == 1);
        }
        for (var name : List.of("partial", "selective")) {
            var buffers = buffers(100, 100);
            var test = process(name, buffers);
            if (name.equals("partial")) RecipeOverlayPipeline.apply(test, List.of(OVERLAYS.get("partial").value()));
            complete("core_" + name + "_completed", test, 1);
            // A selective modifier commits after START acquisition; it does not refund paid input.
            long remaining = name.equals("selective") ? 88 : 98;
            NativeChecks.check("core_" + name + "_preserve_clear", buffers.input().input().amount(MATERIAL) == remaining
                && buffers.output().output().amount(PRODUCT) == 3 && buffers.energyIn().storedEnergy() == 95 && buffers.energyOut().storedEnergy() == 13);
            var data = Objects.requireNonNull(test.components().get(DataComponents.PROCESS)).snapshot().data();
            NativeChecks.check("core_" + name + "_process_data", data.getBoolean(name.equals("partial") ? "overlaySeen" : "selectionSeen"));
        }
        allocation();
        for (var name : List.of("fuzzy", "dynamic")) {
            var buffers = buffers(20, 0);
            complete("core_" + name + "_completed", process(name, buffers), 1);
            NativeChecks.check("core_" + name + "_payment", buffers.input().input().amount(MATERIAL) == 10);
        }
        var checkpoint = buffers(0, 0);
        var combined = buffers(100, 0);
        complete("core_combined_completed", process("combined", combined), 1);
        NativeChecks.check("core_combined_payment", combined.input().input().amount(MATERIAL) == 75 && combined.output().output().amount(PRODUCT) == 2);
        for (var name : List.of("excess_reject", "void")) {
            var output = new BufferedResourceHatch(HatchMode.OUTPUT, 1, Map.of(MATERIAL_TYPE, 1L), () -> {});
            var energy = new EnergyBuffer(2, () -> {});
            var test = new RecipeProcess(owner(new MachineHatches(List.of(output), List.of(), List.of(energy))), recipe(name).recipe(), 17);
            var result = test.tick();
            NativeChecks.check("core_" + name + "_output_capacity", name.equals("void")
                ? result == ProcessResult.SUCCESS && output.output().amount(PRODUCT) == 1 && energy.storedEnergy() == 2
                : result != ProcessResult.SUCCESS && output.output().amount(PRODUCT) == 0 && energy.storedEnergy() == 0);
        }
        complete("core_checkpoint_completed", process("checkpoint", checkpoint), 2);
        NativeChecks.check("core_checkpoint_once", checkpoint.output().output().amount(PRODUCT) == 2);
        var longInput = new BufferedResourceHatch(HatchMode.INPUT, 1, Map.of(MATERIAL_TYPE, 2147483650L), () -> {});
        var longOutput = new BufferedResourceHatch(HatchMode.OUTPUT, 1, Map.of(MATERIAL_TYPE, 2147483650L), () -> {});
        longInput.input().insert(MATERIAL, 2147483649L, Actionable.MODULATE);
        var large = new RecipeProcess(owner(new MachineHatches(List.of(longInput, longOutput), List.of(), List.of())), recipe("long").recipe(), 17);
        NativeChecks.check("core_long_resource_payment", large.tick() == ProcessResult.SUCCESS
            && longInput.input().amount(MATERIAL) == 1 && longOutput.output().amount(PRODUCT) == 2147483650L);
        external();
    }

    private static void allocation() {
        var first = buffers(9, 6);
        var second = buffers(9, 6);
        var hatches = new MachineHatches(List.of(first.input(), first.input(), second.input()), List.of(first.energyIn(), second.energyIn()), List.of());
        var machine = owner(hatches);
        var valid = new RecipeProcess(machine, recipe("allocated").recipe(), 17);
        NativeChecks.check("core_custom_allocation", valid.tick() == ProcessResult.SUCCESS
            && first.input().input().amount(MATERIAL) == 2 && second.input().input().amount(MATERIAL) == 6
            && first.energyIn().storedEnergy() == 6 && second.energyIn().storedEnergy() == 3);
        first.input().input().insert(MATERIAL, 7, Actionable.MODULATE);
        second.input().input().insert(MATERIAL, 3, Actionable.MODULATE);
        boolean rejected = false;
        try { new RecipeProcess(machine, recipe("duplicate").recipe(), 17).tick(); }
        catch (IllegalArgumentException expected) { rejected = true; }
        NativeChecks.check("core_custom_duplicate_preflight", rejected && first.input().input().amount(MATERIAL) == 9
            && second.input().input().amount(MATERIAL) == 9 && first.input().input().availableAmount(MATERIAL) == 9
            && second.input().input().availableAmount(MATERIAL) == 9);
        var single = new RecipeProcess(machine, recipe("single").recipe(), 17);
        NativeChecks.check("core_single_source_reject_split", single.tick() != ProcessResult.SUCCESS
            && first.input().input().amount(MATERIAL) == 9 && second.input().input().amount(MATERIAL) == 9);
        first.input().input().insert(MATERIAL, 1, Actionable.MODULATE);
        NativeChecks.check("core_single_source_retry", single.tick() == ProcessResult.SUCCESS && first.input().input().amount(MATERIAL) == 0 && second.input().input().amount(MATERIAL) == 9);
    }

    private static void external() {
        // Later Java handles have the same names but cannot rebind the script's captured codec/overlay.
        currentOverlay = new ZenRequirementOverlay(TARGET, new RecipeOverlayType<>(ADD.id(), NUMBER, Math::multiplyExact), 10);
        currentRecipe = ZenRecipe.create("pe_run_tests:core_external", "later", 2, new ZenRequirement[]{});
        var scripted = process("external_script", buffers(0, 0));
        complete("core_external_script_completed", scripted, 1);
        NativeChecks.check("core_external_java_requirement", Objects.requireNonNull(scripted.components().get(DataComponents.PROCESS)).snapshot().data().getInteger("number") == 10);
        var exact = process("external", buffers(0, 0));
        RecipeOverlayPipeline.apply(exact, List.of(OVERLAYS.get("external").value()));
        NativeChecks.check("core_external_overlay_tick", exact.tick() == ProcessResult.SUCCESS
            && Objects.requireNonNull(exact.components().get(DataComponents.PROCESS)).snapshot().data().getInteger("number") == 15);
        var codec = recipe("external").codec();
        var restored = codec.decode(exact.owner(), codec.encode(exact));
        NativeChecks.check("core_external_exact_codec", restored.recipe() == exact.recipe() && restored.tick() == ProcessResult.SUCCESS
            && Objects.requireNonNull(restored.components().get(DataComponents.PROCESS)).snapshot().data().getInteger("number") == 15);
    }
}
