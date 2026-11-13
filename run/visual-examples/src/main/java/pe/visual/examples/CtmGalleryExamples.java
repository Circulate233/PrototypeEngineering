package pe.visual.examples;

import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import ae2.api.orientation.BlockOrientation;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineRuntimeRegistrationEvent;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistrationEvent;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.structure.TemplateStructure;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Registry-derived CTM scene. One controller, three touching 3x3 faces per registered PE block.
 */
public final class CtmGalleryExamples {
    public static final String NAME = "ctm_gallery";
    public static final ResourceLocation TYPE = VisualExamples.id(NAME);
    public static final ResourceLocation SHAPE = VisualExamples.id(NAME + "_shape");
    public static final LocalizationEnum TITLE = () -> "pe_run_tests.ctm.gallery";
    private static BlockPos2ObjectMap<Block> cells = BlockPos2ObjectMap.emptyMap();
    private static List<Panel> panels = List.of();

    private CtmGalleryExamples() {
    }

    public record Panel(ResourceLocation block, BlockPos origin) {
    }

    /**
     * Published panel metadata is borrowed read-only for the registered gallery's lifetime.
     */
    public static List<Panel> panels() {
        return panels;
    }

    public static void declareMachines(MachineTypeRegistrationEvent event) {
        event.registry().register(MachineType.builder(TYPE, TITLE, SHAPE).build());
    }

    public static void registerRuntime(MachineRuntimeRegistrationEvent event) {
        var blocks = new ObjectArrayList<Block>();
        for (var block : ForgeRegistries.BLOCKS) {
            var id = block.getRegistryName();
            if (id != null && (id.getNamespace().equals(Tags.MOD_ID)
                || id.equals(VisualExamples.id("parallel_java")) || id.equals(MEAssemblyExamples.id("parallel_zs"))))
                blocks.add(block);
        }
        blocks.sort(Comparator.comparing(Block::getRegistryName));
        var layout = new BlockPos2ObjectMap<Block>();
        var declared = new ObjectArrayList<Panel>(blocks.size());
        int index = 0;
        for (var block : blocks) {
            var base = new BlockPos(3 + index % 8 * 5, 0, 3 + index / 8 * 5);
            declared.add(new Panel(block.getRegistryName(), base));
            for (int a = 0; a < 3; a++)
                for (int b = 0; b < 3; b++) {
                    layout.put(BlockPos2ObjectMap.toLong(base.getX() + a, 0, base.getZ() + b), block);       // floor
                    layout.put(BlockPos2ObjectMap.toLong(base.getX() + a, b + 1, base.getZ() + 2), block);   // rear wall
                    layout.put(BlockPos2ObjectMap.toLong(base.getX() + 3, b + 1, base.getZ() + a), block);   // return wall
                }
            index++;
        }
        // Registration transfers these completed collections; subsequent consumers only read them.
        cells = layout;
        panels = declared;
        var predicates = new BlockPos2ObjectMap<StructurePredicate>(layout.size());
        for (var entries = layout.fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            predicates.put(entry.getLongKey(), StructurePredicates.blocks(List.of(entry.getValue())));
        }
        event.definition(new StructureDefinition(TemplateStructure.create(SHAPE, BlockOrientation.NORTH_UP, BlockPos.ORIGIN,
            false, new StructurePattern(predicates), List.of(), List.of()), TITLE.getTranslationKey(), BlockPos2ObjectMap.emptyMap()));
        event.assembly(com.circulation.prototype_eng.api.machine.persistence.MachineAssembly
            .builder(event.registry().get(TYPE), "ctm-gallery-v1").build());
    }

    public static @Nullable BlockPos2ObjectMap<Block> placement(String name) {
        if (!name.equals(NAME) && !name.equals(TYPE.toString())) return null;
        if (cells.isEmpty()) throw new IllegalStateException("CTM gallery runtime has not registered");
        var result = new BlockPos2ObjectMap<>(cells);
        result.put(BlockPos.ORIGIN, VisualExamples.block(TYPE + "_controller"));
        return result;
    }
}
