package pe.visual.examples;

import ae2.core.localization.LocalizationEnum;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenProperty;
import java.util.Locale;

@ZenRegister
@ZenClass("mods.pe_run_tests.Text")
public enum ExampleText implements LocalizationEnum {
    Press, Mixer, Sorter, CustomPage, Input, Output, Kit, Charged, NoEnergy, Drained, NoEnergyOutput,
    Catalog, Wide, Choices, Amounts, Tick, Energy, Parallel, Cross, Structure, LongText,
    BoundaryKit, NoResourceInput, UnknownRecipe, Stocked, StockPartial,
    UnknownMachine, BuildBlocked, Built, BuildFailed, CompositeKit,
    PoolPress("pe_visual_examples.pool.press"), PoolMixer("pe_visual_examples.pool.mixer"), PoolSorter("pe_visual_examples.pool.sorter"),
    @ZenProperty MeAssemblies("pe_run_tests.machine.me_assemblies"),
    @ZenProperty MeInterface("pe_run_tests.machine.me_interface"),
    @ZenProperty MePattern("pe_run_tests.machine.me_pattern"),
    @ZenProperty MeColors("pe_run_tests.machine.me_colors"),
    @ZenProperty MeParallel("pe_run_tests.machine.me_parallel"),
    @ZenProperty MeWide("pe_run_tests.machine.me_wide"),
    @ZenProperty MeResourcePool("pe_run_tests.pool.me_resources"),
    @ZenProperty MePatternPool("pe_run_tests.pool.me_pattern"),
    @ZenProperty MeColorPool("pe_run_tests.pool.me_colors"),
    @ZenProperty MeParallelPool("pe_run_tests.pool.me_parallel"),
    @ZenProperty MeWidePool("pe_run_tests.pool.me_wide"),
    @ZenProperty MeStructure("pe_run_tests.structure.me"),
    @ZenProperty MeParallelZs("pe_run_tests.parallel.zs"),
    @ZenProperty CoreThread("pe_run_tests.machine.core_thread"),
    @ZenProperty CoreCross("pe_run_tests.machine.core_cross"),
    @ZenProperty CoreAddon("pe_run_tests.machine.core_addon"),
    @ZenProperty CoreFixed("pe_run_tests.machine.core_fixed"),
    @ZenProperty CoreWorld("pe_run_tests.machine.core_world"),
    @ZenProperty CorePool("pe_run_tests.pool.core"),
    @ZenProperty CoreParallelPool("pe_run_tests.pool.core_parallel"),
    @ZenProperty CoreSimplePool("pe_run_tests.pool.core_simple"),
    CoreAddonPool("pe_run_tests.pool.core_addon"),
    @ZenProperty CoreWorldPool("pe_run_tests.pool.core_world"),
    @ZenProperty CoreStructure("pe_run_tests.structure.core"),
    @ZenProperty WorldStructure("pe_run_tests.structure.world"),
    @ZenProperty WorldBranch("pe_run_tests.structure.world_branch"),
    @ZenProperty WorldLeaf("pe_run_tests.structure.world_leaf"),
    @ZenProperty NetworkStructure("pe_run_tests.structure.network"),
    @ZenProperty RenderStructure("pe_run_tests.structure.render"),
    @ZenProperty RenderSlice("pe_run_tests.structure.render_slice"),
    @ZenProperty Nested("pe_run_tests.structure.nested"),
    @ZenProperty Expandable("pe_run_tests.structure.expandable"),
    @ZenProperty Marker("pe_run_tests.structure.marker"),
    @ZenProperty Pod("pe_run_tests.structure.pod"),
    @ZenProperty Left("pe_run_tests.structure.left"),
    @ZenProperty Right("pe_run_tests.structure.right"),
    @ZenProperty Body("pe_run_tests.structure.body"),
    @ZenProperty Cap("pe_run_tests.structure.cap"),
    @ZenProperty NestedPool("pe_run_tests.pool.nested"),
    @ZenProperty ExpandablePool("pe_run_tests.pool.expandable"),
    @ZenProperty NetCenter("pe_run_tests.machine.net_center"),
    @ZenProperty NetComputer("pe_run_tests.machine.net_computer"),
    @ZenProperty NetStation("pe_run_tests.machine.net_station"),
    @ZenProperty NetDatabase("pe_run_tests.machine.net_database"),
    @ZenProperty NetDevice("pe_run_tests.machine.net_device"),
    @ZenProperty NetSlots("pe_run_tests.machine.net_slots"),
    @ZenProperty RenderComponent("pe_run_tests.machine.render_component"),
    @ZenProperty NetworkPool("pe_run_tests.pool.network"),
    @ZenProperty ClientCustomPool("pe_run_tests.pool.client_custom"),
    @ZenProperty RenderDefaultPool("pe_run_tests.pool.render_default");

    private final String key;
    ExampleText() { key = "pe_visual_examples." + name().toLowerCase(Locale.ROOT); }
    ExampleText(String key) { this.key = key; }

    @Override
    @ZenGetter("key")
    public String getTranslationKey() {
        return key;
    }
}
