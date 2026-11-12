package com.circulation.prototype_eng.structure.json;

import java.nio.file.attribute.BasicFileAttributes;
import org.jetbrains.annotations.NotNull;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
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
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTException;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
                    if (Files.isRegularFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) files.add(file);
                    return FileVisitResult.CONTINUE;
                }
            });
            files.sort(Comparator.comparing(path -> directory.relativize(path).toString().replace('\\', '/')));
            for (var file : files) {
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
        for (var source : orderedSources) {
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
            for (String field : List.of("minCount", "maxCount", "sliceOffset"))
                if (root.has(field)) throw root.at(field).error("Slice field is not valid for a template");
        }
        var elements = new ObjectArrayList<Cell>();
        var positions = new ObjectLinkedOpenHashSet<BlockPos>();
        for (var entry : root.array("pattern")) {
            var cell = cell(entry);
            if (!positions.add(cell.position))
                throw entry.at("pos").error("Duplicate pattern coordinate " + cell.position);
            elements.add(cell);
        }
        var children = new ObjectArrayList<Reference>();
        if (root.has("children"))
            for (var child : root.array("children")) children.add(new Reference(child.id(), child));
        return new Data(id, root.has("translationKey") ? root.nonBlank("translationKey") : null, type,
            root.position("offset", BlockPos.ORIGIN), root.bool("hideWorldBlocks", false), List.copyOf(elements),
            specs(root, "validators"), List.copyOf(children), min, max, sliceOffset, root);
    }

    private static Cell cell(StructureJsonParameters entry) {
        entry.fields("pos", "blockId", "meta", "nbt", "properties", "alternatives", "predicates", "display");
        entry.at("pos");
        var options = new ObjectArrayList<Option>();
        if (entry.has("blockId")) options.add(option(entry, false));
        else if (entry.has("meta") || entry.has("nbt") || entry.has("properties")) throw entry.error("meta/nbt/properties require blockId");
        if (entry.has("alternatives"))
            for (var alternative : entry.array("alternatives")) options.add(option(alternative, true));
        var predicates = specs(entry, "predicates");
        if (options.isEmpty() && predicates.isEmpty())
            throw entry.error("Pattern cell needs a block option or predicate");
        Display display = null;
        if (entry.has("display")) {
            var spec = entry.at("display");
            spec.fields("key", "blocks", "blockIdRegex", "tileNbt");
            var blocks = new ObjectArrayList<Option>();
            if (spec.has("blocks")) for (var block : spec.array("blocks")) blocks.add(option(block, true));
            var regex = new ObjectArrayList<StructureJsonParameters>();
            if (spec.has("blockIdRegex")) for (var expression : spec.array("blockIdRegex")) {
                if (expression.string().isBlank()) throw expression.error("Empty display regex");
                regex.add(expression);
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
        if (parent.has(field)) for (var entry : parent.array(field)) {
            entry.fields("id", "params");
            entry.id("id");
            specs.add(new Spec(entry.at("id"), entry.parameters("params")));
        }
        return List.copyOf(specs);
    }

    private static StructureDefinition build(Data data, List<MachineStructure> children,
                                             StructurePredicateRegistry factories, StructureBlockResolver blocks) {
        var pattern = new Object2ObjectLinkedOpenHashMap<BlockPos, StructurePredicate>(data.pattern.size());
        var displays = new Object2ObjectLinkedOpenHashMap<BlockPos, StructureDisplay>(data.pattern.size());
        for (var cell : data.pattern) {
            var parts = new ObjectArrayList<StructurePredicate>(cell.options.size());
            var alternatives = new ObjectArrayList<StructurePredicate>(cell.options.size());
            for (var option : cell.options) {
                try {
                    alternatives.add(Objects.requireNonNull(blocks.resolveState(option.id, option.meta, option.nbt, option.properties)));
                } catch (RuntimeException failure) {
                    throw option.location.at("blockId").error("Cannot resolve block state " + option.id, failure);
                }
            }
            if (!alternatives.isEmpty()) parts.add(StructurePredicates.any(alternatives));
            for (var spec : cell.predicates) parts.add(factories.create(spec.identifier, spec.parameters, blocks));
            pattern.put(cell.position, StructurePredicates.all(parts));
            String defaultKey = data.id + "@" + cell.position.getX() + "," + cell.position.getY() + "," + cell.position.getZ();
            if (cell.display != null) displays.put(cell.position, display(cell.display, defaultKey, blocks));
        }
        var validators = new ObjectArrayList<StructurePredicate>();
        for (var spec : data.validators) validators.add(factories.create(spec.identifier, spec.parameters, blocks));
        var orientation = BlockOrientation.get(EnumFacing.NORTH, EnumFacing.UP);
        MachineStructure shape = data.type.equals("template")
            ? TemplateStructure.create(data.id, orientation, data.offset, data.hide, new StructurePattern(pattern), validators, children)
            : SliceStructure.create(data.id, orientation, data.offset, data.hide, new StructurePattern(pattern), data.min, data.max, data.sliceOffset, validators, children);
        return new StructureDefinition(shape, data.translationKey, displays);
    }

    private static StructureDisplay display(Display data, String defaultKey, StructureBlockResolver blocks) {
        var choices = new ObjectLinkedOpenHashSet<StructureDisplay.BlockOption>(data.blocks.size());
        for (var option : data.blocks) {
            try {
                choices.add(Objects.requireNonNull(blocks.displayState(option.id, option.meta, option.nbt, option.properties)));
            } catch (RuntimeException failure) {
                throw option.location.at("blockId").error("Cannot resolve display state " + option.id, failure);
            }
        }
        for (var regex : data.regex) {
            try {
                for (var option : blocks.displayRegex(regex.string())) choices.add(Objects.requireNonNull(option));
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
        for (var definition : definitions) {
            if (resolved.containsKey(definition.id))
                throw definition.location.at("id").error("JSON/Java structure ID conflict: " + definition.id);
            pending.put(definition.id, definition);
        }
        // Explicit DFS frames preserve reference identity without imposing Java recursion depth on DAGs.
        for (var definition : definitions) {
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
        for (var definition : definitions) ordered.add(resolved.get(definition.id));
        return new StructureCatalog(ordered);
    }

    private record Data(ResourceLocation id, @Nullable String translationKey, String type, BlockPos offset,
                        boolean hide,
                        List<Cell> pattern, List<Spec> validators, List<Reference> children,
                        int min, int max, BlockPos sliceOffset, StructureJsonParameters location) {
    }

    private record Cell(BlockPos position, List<Option> options, List<Spec> predicates, @Nullable Display display) {
    }

    private record Option(ResourceLocation id, int meta, Map<String, String> nbt, Map<String, String> properties, StructureJsonParameters location) {
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
