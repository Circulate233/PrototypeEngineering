package com.circulation.prototype_eng.structure.json;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.json.StructureBlockResolver;
import com.circulation.prototype_eng.api.structure.json.StructureJsonException;
import com.circulation.prototype_eng.api.structure.json.StructureJsonParameters;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistry;
import com.circulation.prototype_eng.structure.SliceStructure;
import com.circulation.prototype_eng.structure.TemplateStructure;
import com.circulation.prototype_eng.util.BlockPos2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Strict PreInit DTO capture, followed by explicit PostInit resolution of one complete candidate.
 */
public final class StructureJsonLoader {
    private final List<Data> definitions;

    private StructureJsonLoader(List<Data> definitions) {
        this.definitions = List.copyOf(definitions);
    }

    public static StructureJsonLoader empty() {
        return new StructureJsonLoader(List.of());
    }

    /**
     * Empty directories are a valid pure library; no sample machines or registry calls occur here.
     */
    public static StructureJsonLoader readDirectory(Path directory) {
        if (Files.notExists(directory)) return empty();
        if (!Files.isDirectory(directory))
            throw new StructureJsonException(directory.toString(), "$", "Structure root is not an accessible directory");
        var sources = new Object2ObjectLinkedOpenHashMap<String, String>();
        try {
            var files = new ObjectArrayList<Path>();
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public @NotNull FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attributes) {
                    if (Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                        files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.comparing(path -> directory.relativize(path).toString().replace('\\', '/')));
            for (int index8 = 0; index8 < files.size(); index8++) {
                var file = files.get(index8);
                String source = directory.relativize(file).toString().replace('\\', '/');
                try {
                    sources.put(source, Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException failure) {
                    throw new StructureJsonException(source, "$", "Cannot read structure file", failure);
                }
            }
        } catch (IOException failure) {
            throw new StructureJsonException(directory.toString(), "$", "Cannot enumerate structure directory", failure);
        }
        return readSources(sources);
    }

    /**
     * Stable source ordering also supports Java callers supplying detached JSON text.
     */
    public static StructureJsonLoader readSources(Map<String, String> sources) {
        var definitions = new Object2ObjectLinkedOpenHashMap<ResourceLocation, Data>(sources.size());
        var orderedSources = new ObjectArrayList<>(sources.keySet());
        orderedSources.sort(Comparator.naturalOrder());
        for (int index9 = 0; index9 < orderedSources.size(); index9++) {
            var source = orderedSources.get(index9);
            var root = StructureJsonParameters.read(source, new StringReader(Objects.requireNonNull(sources.get(source))));
            var definition = data(root);
            var prior = definitions.putIfAbsent(definition.id, definition);
            if (prior != null)
                throw root.at("id").error("Duplicate structure ID " + definition.id + "; first declared in " + prior.location.source());
        }
        return new StructureJsonLoader(new ObjectArrayList<>(definitions.values()));
    }

    private static Data data(StructureJsonParameters root) {
        root.fields("id", "translationKey", "type", "offset", "hideWorldBlocks", "pattern", "validators", "children", "minCount", "maxCount", "sliceOffset");
        var id = root.id("id");
        String type = root.string("type");
        if (!type.equals("template") && !type.equals("slice"))
            throw root.at("type").error("Expected template or slice");
        int min = 0, max = 0;
        BlockPos sliceOffset = BlockPos.ORIGIN;
        if (type.equals("slice")) {
            min = root.at("minCount").integer();
            max = root.at("maxCount").integer();
            if (min < 0) throw root.at("minCount").error("Slice minimum must be nonnegative");
            if (max < min) throw root.at("maxCount").error("Slice maximum must be at least the minimum");
            sliceOffset = root.position("sliceOffset", new BlockPos(0, 1, 0));
        } else {

            var elements4 = List.of("minCount", "maxCount", "sliceOffset");
            for (int index4 = 0; index4 < elements4.size(); index4++) {
                String field = elements4.get(index4);
                if (root.has(field)) throw root.at(field).error("Slice field is not valid for a template");
            }

        }
        var elements = new ObjectArrayList<Cell>();
        var positions = new ObjectLinkedOpenHashSet<BlockPos>();

        var elements7 = root.array("pattern");
        for (int index7 = 0; index7 < elements7.size(); index7++) {
            var entry = elements7.get(index7);
            var cell = cell(entry);
            if (!positions.add(cell.position))
                throw entry.at("pos").error("Duplicate pattern coordinate " + cell.position);
            elements.add(cell);
        }

        var children = new ObjectArrayList<Reference>();
        if (root.has("children")) {
            var elements2 = root.array("children");
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var child = elements2.get(index2);
                children.add(new Reference(child.id(), child));
            }
        }
        return new Data(id, root.has("translationKey") ? root.nonBlank("translationKey") : null, type,
            root.position("offset", BlockPos.ORIGIN), root.bool("hideWorldBlocks", false), List.copyOf(elements),
            specs(root, "validators"), List.copyOf(children), min, max, sliceOffset, root);
    }

    private static Cell cell(StructureJsonParameters entry) {
        entry.fields("pos", "blockId", "meta", "nbt", "properties", "alternatives", "predicates", "display");
        entry.at("pos");
        var options = new ObjectArrayList<Option>();
        if (entry.has("blockId")) options.add(option(entry, false));
        else if (entry.has("meta") || entry.has("nbt") || entry.has("properties"))
            throw entry.error("meta/nbt/properties require blockId");
        if (entry.has("alternatives")) {
            var elements3 = entry.array("alternatives");
            for (int index3 = 0; index3 < elements3.size(); index3++) {
                var alternative = elements3.get(index3);
                options.add(option(alternative, true));
            }
        }
        var predicates = specs(entry, "predicates");
        if (options.isEmpty() && predicates.isEmpty())
            throw entry.error("Pattern cell needs a block option or predicate");
        Display display = null;
        if (entry.has("display")) {
            var spec = entry.at("display");
            spec.fields("key", "blocks", "blockIdRegex", "tileNbt");
            var blocks = new ObjectArrayList<Option>();
            if (spec.has("blocks")) {
                var elements1 = spec.array("blocks");
                for (int index = 0; index < elements1.size(); index++) {
                    var block = elements1.get(index);
                    blocks.add(option(block, true));
                }
            }
            var regex = new ObjectArrayList<StructureJsonParameters>();
            if (spec.has("blockIdRegex")) {
                var elements6 = spec.array("blockIdRegex");
                for (int index6 = 0; index6 < elements6.size(); index6++) {
                    var expression = elements6.get(index6);
                    if (expression.string().isBlank()) throw expression.error("Empty display regex");
                    regex.add(expression);
                }
            }
            if (blocks.isEmpty() && regex.isEmpty()) throw spec.error("Display needs blocks or blockIdRegex");
            display = new Display(spec.has("key") ? spec.nonBlank("key") : null, List.copyOf(blocks), List.copyOf(regex),
                spec.has("tileNbt") ? spec.nonBlank("tileNbt") : null, spec);
        }
        return new Cell(entry.position("pos", BlockPos.ORIGIN), List.copyOf(options), predicates, display);
    }

    private static Option option(StructureJsonParameters entry, boolean checkFields) {
        if (checkFields) entry.fields("blockId", "meta", "nbt", "properties");
        return new Option(entry.id("blockId"), entry.meta(), entry.stringMap("nbt"), entry.stringMap("properties"), entry);
    }

    private static List<Spec> specs(StructureJsonParameters parent, String field) {
        var specs = new ObjectArrayList<Spec>();
        if (parent.has(field)) {
            var elements5 = parent.array(field);
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                var entry = elements5.get(index5);
                entry.fields("id", "params");
                entry.id("id");
                specs.add(new Spec(entry.at("id"), entry.parameters("params")));
            }
        }
        return List.copyOf(specs);
    }

    private static StructureDefinition build(Data data, List<MachineStructure> children,
                                             StructurePredicateRegistry factories, StructureBlockResolver blocks) {
        var pattern = new BlockPos2ObjectMap<StructurePredicate>(data.pattern.size());
        var displays = new BlockPos2ObjectMap<StructureDisplay>(data.pattern.size());
        for (int index17 = 0; index17 < data.pattern.size(); index17++) {
            var cell = data.pattern.get(index17);
            var parts = new ObjectArrayList<StructurePredicate>(cell.options.size());
            var alternatives = new ObjectArrayList<StructurePredicate>(cell.options.size());
            for (int index16 = 0; index16 < cell.options.size(); index16++) {
                var option = cell.options.get(index16);
                try {
                    alternatives.add(Objects.requireNonNull(blocks.resolveState(option.id, option.meta, option.nbt, option.properties)));
                } catch (RuntimeException failure) {
                    throw option.location.at("blockId").error("Cannot resolve block state " + option.id, failure);
                }
            }
            if (!alternatives.isEmpty()) parts.add(StructurePredicates.any(alternatives));
            for (int index11 = 0; index11 < cell.predicates.size(); index11++) {
                var spec = cell.predicates.get(index11);
                parts.add(factories.create(spec.identifier, spec.parameters, blocks));
            }
            pattern.put(cell.position, StructurePredicates.all(parts));
            String defaultKey = data.id + "@" + cell.position.getX() + "," + cell.position.getY() + "," + cell.position.getZ();
            if (cell.display != null) displays.put(cell.position, display(cell.display, defaultKey, blocks));
        }
        var validators = new ObjectArrayList<StructurePredicate>();
        for (int index12 = 0; index12 < data.validators.size(); index12++) {
            var spec = data.validators.get(index12);
            validators.add(factories.create(spec.identifier, spec.parameters, blocks));
        }
        var orientation = BlockOrientation.get(EnumFacing.NORTH, EnumFacing.UP);
        MachineStructure shape = data.type.equals("template")
            ? TemplateStructure.create(data.id, orientation, data.offset, data.hide, new StructurePattern(pattern), validators, children)
            : SliceStructure.create(data.id, orientation, data.offset, data.hide, new StructurePattern(pattern), data.min, data.max, data.sliceOffset, validators, children);
        return new StructureDefinition(shape, data.translationKey, displays);
    }

    private static StructureDisplay display(Display data, String defaultKey, StructureBlockResolver blocks) {
        var choices = new ObjectLinkedOpenHashSet<StructureDisplay.BlockOption>(data.blocks.size());
        for (int index15 = 0; index15 < data.blocks.size(); index15++) {
            var option = data.blocks.get(index15);
            try {
                choices.add(Objects.requireNonNull(blocks.displayState(option.id, option.meta, option.nbt, option.properties)));
            } catch (RuntimeException failure) {
                throw option.location.at("blockId").error("Cannot resolve display state " + option.id, failure);
            }
        }
        for (int index14 = 0; index14 < data.regex.size(); index14++) {
            var regex = data.regex.get(index14);
            try {
                var elements10 = blocks.displayRegex(regex.string());
                for (int index10 = 0; index10 < elements10.size(); index10++) {
                    var option = elements10.get(index10);
                    choices.add(Objects.requireNonNull(option));
                }
            } catch (RuntimeException failure) {
                throw regex.error("Cannot resolve display regex", failure);
            }
        }
        if (choices.isEmpty()) throw data.location.error("Display has no registered block choices");
        if (data.tileNbt != null) {
            try {
                JsonToNBT.getTagFromJson(data.tileNbt);
            } catch (NBTException failure) {
                throw data.location.at("tileNbt").error("Invalid preview tile SNBT", failure);
            }
        }
        return new StructureDisplay(data.key == null ? defaultKey : data.key, new ObjectArrayList<>(choices), data.tileNbt);
    }

    /**
     * Resolves JSON/Java references together, never consulting the already published catalog.
     */
    public StructureCatalog resolve(StructureCatalog javaDefinitions, StructurePredicateRegistry factories, StructureBlockResolver blocks) {
        Objects.requireNonNull(factories);
        Objects.requireNonNull(blocks);
        var pending = new Object2ObjectLinkedOpenHashMap<ResourceLocation, Data>(definitions.size());
        var resolved = new Object2ObjectLinkedOpenHashMap<>(javaDefinitions.definitions());
        for (int index13 = 0; index13 < definitions.size(); index13++) {
            var definition = definitions.get(index13);
            if (resolved.containsKey(definition.id))
                throw definition.location.at("id").error("JSON/Java structure ID conflict: " + definition.id);
            pending.put(definition.id, definition);
        }
        // Explicit DFS frames preserve reference identity without imposing Java recursion depth on DAGs.
        for (int index18 = 0; index18 < definitions.size(); index18++) {
            var definition = definitions.get(index18);
            if (resolved.containsKey(definition.id)) continue;
            var stack = new ObjectArrayList<Frame>();
            var visiting = new ObjectLinkedOpenHashSet<ResourceLocation>();
            stack.add(new Frame(definition));
            visiting.add(definition.id);
            while (!stack.isEmpty()) {
                var frame = stack.getLast();
                if (frame.nextChild < frame.data.children.size()) {
                    var reference = frame.data.children.get(frame.nextChild);
                    var child = resolved.get(reference.id);
                    if (child != null) {
                        frame.children.add(child.shape());
                        frame.nextChild++;
                    } else {
                        var childData = pending.get(reference.id);
                        if (childData == null)
                            throw reference.location.error("Unknown child structure: " + reference.id);
                        if (!visiting.add(reference.id)) {
                            var cycle = new StringBuilder();
                            for (var visited : visiting) {
                                if (!cycle.isEmpty()) cycle.append(" -> ");
                                cycle.append(visited);
                            }
                            cycle.append(" -> ").append(reference.id);
                            throw reference.location.error("Structure reference cycle: " + cycle);
                        }
                        stack.add(new Frame(childData));
                    }
                } else {
                    resolved.put(frame.data.id, build(frame.data, frame.children, factories, blocks));
                    visiting.remove(frame.data.id);
                    stack.removeLast();
                }
            }
        }
        var ordered = new ObjectArrayList<>(javaDefinitions.definitions().values());
        for (int index1 = 0; index1 < definitions.size(); index1++) {
            var definition = definitions.get(index1);
            ordered.add(resolved.get(definition.id));
        }
        return new StructureCatalog(ordered);
    }

    private record Data(ResourceLocation id, @Nullable String translationKey, String type, BlockPos offset,
                        boolean hide,
                        List<Cell> pattern, List<Spec> validators, List<Reference> children,
                        int min, int max, BlockPos sliceOffset, StructureJsonParameters location) {
    }

    private record Cell(BlockPos position, List<Option> options, List<Spec> predicates, @Nullable Display display) {
    }

    private record Option(ResourceLocation id, int meta, Map<String, String> nbt, Map<String, String> properties,
                          StructureJsonParameters location) {
    }

    private record Spec(StructureJsonParameters identifier, StructureJsonParameters parameters) {
    }

    private record Reference(ResourceLocation id, StructureJsonParameters location) {
    }

    private record Display(@Nullable String key, List<Option> blocks, List<StructureJsonParameters> regex,
                           @Nullable String tileNbt, StructureJsonParameters location) {
    }

    private static final class Frame {
        private final Data data;
        private final ObjectArrayList<MachineStructure> children = new ObjectArrayList<>();
        private int nextChild;

        private Frame(Data data) {
            this.data = data;
        }
    }
}
