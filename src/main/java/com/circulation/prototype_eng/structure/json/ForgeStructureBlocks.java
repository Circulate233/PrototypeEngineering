package com.circulation.prototype_eng.structure.json;

import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.json.StructureBlockResolver;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.regex.Pattern;

/** Construct and use only after Forge block registration. Caches belong to this candidate, not a static registry. */
public final class ForgeStructureBlocks implements StructureBlockResolver {
    private final Object2ObjectLinkedOpenHashMap<String, List<Block>> regexBlocks = new Object2ObjectLinkedOpenHashMap<>();

    @SuppressWarnings("deprecation")
    private static IBlockState resolveState(ResourceLocation id, int meta) {
        if (!Block.REGISTRY.containsKey(id)) throw new IllegalArgumentException("Unknown block ID: " + id);
        if (meta < 0 || meta > 15) throw new IllegalArgumentException("Block metadata must be in 0..15");
        var block = Block.REGISTRY.getObject(id);
        var state = block.getStateFromMeta(meta);
        if (block.getMetaFromState(state) != meta)
            throw new IllegalArgumentException("Metadata " + meta + " is not representable by block " + id);
        return state;
    }

    @Override
    public StructurePredicate block(ResourceLocation id) {
        if (!Block.REGISTRY.containsKey(id)) throw new IllegalArgumentException("Unknown block ID: " + id);
        return StructurePredicates.blocks(List.of(Block.REGISTRY.getObject(id)));
    }

    @Override
    public StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt) {
        return StructurePredicates.stateWithNbt(resolveState(id, meta), nbt);
    }

    private List<Block> expand(String expression) {
        var existing = regexBlocks.get(expression);
        if (existing != null) return existing;
        var regex = Pattern.compile(expression);
        var result = new ObjectArrayList<Block>();
        var ids = new ObjectArrayList<>(Block.REGISTRY.getKeys());
        ids.sort(Comparator.comparing(ResourceLocation::toString));
        for (var id : ids) {
            if (regex.matcher(id.toString()).matches()) {
                var block = Block.REGISTRY.getObject(id);
                if (block != Blocks.AIR) result.add(block);
            }
        }
        var frozen = List.copyOf(result);
        regexBlocks.put(expression, frozen);
        return frozen;
    }

    @Override
    public StructurePredicate regex(String expression) {
        return StructurePredicates.blocks(expand(expression));
    }

    @Override
    public StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt) {
        resolveState(id, meta);
        return new StructureDisplay.BlockOption(id, meta, nbt);
    }

    @Override
    public List<StructureDisplay.BlockOption> displayRegex(String expression) {
        var result = new ObjectArrayList<StructureDisplay.BlockOption>();
        // Preserve PM's bounded representative display; matcher membership always includes all matches.
        for (var block : expand(expression)) {
            if (result.size() == 32) break;
            result.add(displayState(block.getRegistryName(), 0, Map.of()));
        }
        return List.copyOf(result);
    }
}
