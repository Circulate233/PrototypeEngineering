package com.circulation.prototype_eng.structure.json;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureDefinition;
import com.circulation.prototype_eng.api.structure.StructureDisplay;
import com.circulation.prototype_eng.api.structure.StructurePattern;
import com.circulation.prototype_eng.api.structure.StructurePlan;
import com.circulation.prototype_eng.api.structure.StructurePredicate;
import com.circulation.prototype_eng.api.structure.StructurePredicates;
import com.circulation.prototype_eng.api.structure.StructureQueries;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.api.structure.StructureReadPlan;
import com.circulation.prototype_eng.api.structure.StructureReadSource;
import com.circulation.prototype_eng.api.structure.StructureSnapshot;
import com.circulation.prototype_eng.api.structure.StructureTransform;
import com.circulation.prototype_eng.api.structure.json.StructureBlockResolver;
import com.circulation.prototype_eng.api.structure.json.StructureJsonException;
import com.circulation.prototype_eng.api.structure.json.StructurePredicateRegistry;
import com.circulation.prototype_eng.localization.RecipeText;
import com.circulation.prototype_eng.structure.TemplateStructure;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StructureJsonLoaderTest {
    private static final StructureQuery<Token> STATE = new StructureQuery<>(id("fake_state"),
        (_, _) -> fail("The fake resolver never reads a World"), UnaryOperator.identity(), Token::equals);
    private static final StructureQuery<Boolean> ENABLED = new StructureQuery<>(id("fake_enabled"),
        (_, _) -> fail("The addon predicate never reads a World"), UnaryOperator.identity(), Boolean::equals);

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }

    private static StructureCatalog emptyCatalog() {
        return new StructureCatalog(List.of());
    }

    private static @NotNull StructureDefinition definition(StructureCatalog catalog, String name) {
        return Objects.requireNonNull(catalog.get(id(name)), "Missing test definition: " + name);
    }

    private static StructureJsonLoader load(String json) {
        return StructureJsonLoader.readSources(Map.of("shape.json", json));
    }

    private static StructurePredicateRegistry builtins() {
        var registry = new StructurePredicateRegistry();
        BuiltinStructurePredicates.register(registry);
        registry.freeze();
        return registry;
    }

    private static boolean matches(MachineStructure shape, FakeSource source, BlockOrientation orientation) {
        var plan = new StructurePlan(shape, BlockPos.ORIGIN, orientation);
        return plan.match(StructureSnapshot.capture(plan.reads(), source)) != null;
    }

    @Test
    void jsonDefaultTemplateAndSliceKeepTheirRotatedCursorsAcrossOneUnitSlices() {
        var loader = StructureJsonLoader.readSources(Map.of(
            "parent.json", """
                {"id":"test:parent","type":"template","pattern":[],"children":["test:child"]}
                """,
            "child.json", """
                {"id":"test:child","type":"slice","offset":{"x":1,"y":0,"z":0},
                 "minCount":1,"maxCount":3,"sliceOffset":{"x":1,"y":0,"z":0},
                 "pattern":[{"pos":{"x":0,"y":0,"z":0},"blockId":"test:first"}]}
                """));
        var shape = definition(loader.resolve(emptyCatalog(), builtins(), new FakeBlocks()), "parent").shape();
        var planning = StructurePlan.beginCreate(shape, BlockPos.ORIGIN, BlockOrientation.EAST_UP);
        int slices = 0;
        while (!planning.advance(new RecipeWorkBudget(1))) assertTrue(++slices < 1000);
        assertTrue(slices > 10, "The actual JSON default transform/declaration is retained and split");
        var plan = Objects.requireNonNull(planning.result());
        var source = new FakeSource();
        source.put(STATE, new BlockPos(0, 0, 1), new Token(id("first"), 0));
        source.put(STATE, new BlockPos(0, 0, 2), new Token(id("first"), 0));
        source.put(STATE, new BlockPos(0, 0, 3), new Token(id("second"), 0));
        var evaluation = plan.beginEvaluate(StructureSnapshot.capture(plan.reads(), source));
        slices = 0;
        while (!evaluation.advance(new RecipeWorkBudget(1))) assertTrue(++slices < 1000);
        var match = Objects.requireNonNull(Objects.requireNonNull(evaluation.result()).match());
        assertEquals(2, match.children().getFirst().repetitions());
        assertEquals(List.of(new BlockPos(0, 0, 1), new BlockPos(0, 0, 2)), match.allBlocks());
    }

    @Test
    void preInitReadsNestedFilesWithoutResolvingBlocksAndPostInitReportsTheOriginalSource(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/unresolved.json"), """
            {"id":"test:shape","type":"template","pattern":[
              {"pos":{"x":1,"y":0,"z":0},"blockId":"missing:block"}]}
            """);
        var loader = StructureJsonLoader.readDirectory(root);
        var failure = assertThrows(StructureJsonException.class, () -> loader.resolve(emptyCatalog(), builtins(), new FakeBlocks()));
        assertEquals("nested/unresolved.json", failure.source());
        assertEquals("$.pattern[0].blockId", failure.path());
        assertTrue(failure.getCause().getMessage().contains("Unknown block"));
    }

    @Test
    void duplicateJsonMembersCoordinatesAndIdsCannotBeHiddenByMapOverwrite() {
        var member = assertThrows(StructureJsonException.class, () -> load("""
            {"id":"test:shape","type":"template","pattern":[
              {"pos":{"x":0,"x":1,"y":0,"z":0},"blockId":"test:first"}]}
            """));
        assertEquals("$.pattern[0].pos.x", member.path());
        var coordinate = assertThrows(StructureJsonException.class, () -> load("""
            {"id":"test:shape","type":"template","pattern":[
              {"pos":{"x":0,"y":0,"z":0},"blockId":"test:first"},
              {"pos":{"x":0,"y":0,"z":0},"blockId":"test:second"}]}
            """));
        assertEquals("$.pattern[1].pos", coordinate.path());
        String definition = "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[]}";
        var duplicate = assertThrows(StructureJsonException.class,
            () -> StructureJsonLoader.readSources(Map.of("first.json", definition, "second.json", definition)));
        assertEquals("second.json", duplicate.source());
        assertTrue(duplicate.getMessage().contains("first.json"));
    }

    @Test
    void strictDtoRejectsUnknownFieldsAliasesWrongTypesAndInvalidSliceRanges() {
        for (String json : List.of(
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"name\":\"old alias\"}",
            "{\"id\":\"test:shape\",\"type\":\"slice\",\"pattern\":[],\"minCount\":-1,\"maxCount\":1}",
            "{\"id\":\"test:shape\",\"type\":\"slice\",\"pattern\":[],\"minCount\":2,\"maxCount\":1}",
            "{\"id\":\"test:shape\",\"type\":\"slice\",\"pattern\":[],\"minCount\":0,\"maxCount\":2147483648}",
            "{\"id\":\"test:shape\",\"type\":\"slice\",\"pattern\":[],\"minCount\":0.5,\"maxCount\":1}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"hideWorldBlocks\":\"true\"}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"validators\":[\"prototype_eng:any\"]}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"validators\":[{\"type\":\"prototype_eng:any\"}]}",
            "{\"id\":\"shape\",\"type\":\"template\",\"pattern\":[]}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":null}"))
            assertThrows(StructureJsonException.class, () -> load(json), json);
    }

    @Test
    void oneBadSourceCannotProduceAUsablePartialBatch() {
        var failure = assertThrows(StructureJsonException.class, () -> StructureJsonLoader.readSources(Map.of(
            "good.json", "{\"id\":\"test:good\",\"type\":\"template\",\"pattern\":[]}",
            "bad.json", "{\"id\":\"test:bad\",\"type\":\"template\",\"pattern\":[],}")));
        assertEquals("bad.json", failure.source());
    }

    @Test
    void reverseFileReferencesShareOneCanonicalChildAndUnreferencedDefinitionsRemainInTheCatalog() {
        var loader = StructureJsonLoader.readSources(Map.of(
            "a-parent.json", "{\"id\":\"test:parent\",\"type\":\"template\",\"pattern\":[],\"children\":[\"test:child\"]}",
            "b-other.json", "{\"id\":\"test:other\",\"type\":\"template\",\"pattern\":[],\"children\":[\"test:child\"]}",
            "z-child.json", "{\"id\":\"test:child\",\"type\":\"slice\",\"pattern\":[],\"minCount\":0,\"maxCount\":0}"));
        var catalog = loader.resolve(emptyCatalog(), builtins(), new FakeBlocks());
        var child = definition(catalog, "child").shape();
        assertSame(child, ((TemplateStructure) definition(catalog, "parent").shape()).children().getFirst());
        assertSame(child, ((TemplateStructure) definition(catalog, "other").shape()).children().getFirst());
        assertEquals(Set.of(id("parent"), id("other"), id("child")), catalog.definitions().keySet());
    }

    @Test
    void cyclesAndMissingChildrenRetainTheirReferenceFieldAndFile() {
        var cycle = StructureJsonLoader.readSources(Map.of(
            "a.json", "{\"id\":\"test:a\",\"type\":\"template\",\"pattern\":[],\"children\":[\"test:b\"]}",
            "b.json", "{\"id\":\"test:b\",\"type\":\"template\",\"pattern\":[],\"children\":[\"test:a\"]}"));
        var failure = assertThrows(StructureJsonException.class, () -> cycle.resolve(emptyCatalog(), builtins(), new FakeBlocks()));
        assertEquals("b.json", failure.source());
        assertEquals("$.children[0]", failure.path());
        assertTrue(failure.getMessage().contains("test:a -> test:b -> test:a"));
        var missing = load("{\"id\":\"test:a\",\"type\":\"template\",\"pattern\":[],\"children\":[\"test:missing\"]}");
        assertEquals("$.children[0]", assertThrows(StructureJsonException.class,
            () -> missing.resolve(emptyCatalog(), builtins(), new FakeBlocks())).path());
    }

    @Test
    void javaChildrenAreReusedAndJavaJsonIdConflictsAreRejected() {
        var child = new TemplateStructure(id("child"), BlockOrientation.NORTH_UP, BlockPos.ORIGIN, false,
            new StructurePattern(Map.of()), List.of(), List.of());
        var java = new StructureCatalog(List.of(new StructureDefinition(child)));
        var loader = load("{\"id\":\"test:parent\",\"type\":\"template\",\"pattern\":[],\"children\":[\"test:child\"]}");
        assertSame(child, ((TemplateStructure) definition(loader.resolve(java, builtins(), new FakeBlocks()), "parent").shape()).children().getFirst());
        var conflict = load("{\"id\":\"test:child\",\"type\":\"template\",\"pattern\":[]}");
        assertEquals("$.id", assertThrows(StructureJsonException.class,
            () -> conflict.resolve(java, builtins(), new FakeBlocks())).path());
    }

    @Test
    void alternativeNbtBranchesAreOrCombinedThenAndCombinedWithTheAddonPredicate() {
        var registry = new StructurePredicateRegistry();
        registry.register(id("enabled"), Set.of("expected"), (parameters, _) -> {
            parameters.fields("expected");
            boolean expected = parameters.bool("expected", true);
            return StructurePredicates.test(ENABLED, value -> value == expected);
        });
        registry.freeze();
        var loader = load("""
            {"id":"test:shape","type":"template","pattern":[{
              "pos":{"x":1,"y":0,"z":0},"blockId":"test:first","nbt":{"amount":"1"},
              "alternatives":[{"blockId":"test:second","nbt":{"amount":"7"}}],
              "predicates":[{"id":"test:enabled","params":{"expected":true}}]}]}
            """);
        var shape = definition(loader.resolve(emptyCatalog(), registry, new FakeBlocks()), "shape").shape();
        var source = new FakeSource();
        var position = new BlockPos(1, 0, 0);
        source.put(STATE, position, new Token(id("second"), 0));
        source.put(ENABLED, position, true);
        var tag = new NBTTagCompound();
        tag.setInteger("amount", 7);
        source.put(StructureQueries.TILE_NBT, position, tag);
        assertTrue(matches(shape, source, BlockOrientation.NORTH_UP));
        source.put(ENABLED, position, false);
        assertFalse(matches(shape, source, BlockOrientation.NORTH_UP));
        source.put(ENABLED, position, true);
        tag.setInteger("amount", 1);
        assertFalse(matches(shape, source, BlockOrientation.NORTH_UP));
    }

    @Test
    void displayOverrideDoesNotChangeMatchingAndBadDisplayCannotPublish() {
        String base = """
            {"id":"test:shape","type":"template","translationKey":"addon.structure.shape","pattern":[{
              "pos":{"x":1,"y":0,"z":0},"blockId":"test:first",%s}]}
            """;
        String display = "\"display\":{\"key\":\"visual\",\"blocks\":[{\"blockId\":\"test:second\"}],\"tileNbt\":\"{amount:7}\"}";
        var catalog = load(base.formatted(display)).resolve(emptyCatalog(), builtins(), new FakeBlocks());
        var plain = load(base.formatted("\"predicates\":[]")).resolve(emptyCatalog(), builtins(), new FakeBlocks());
        var source = new FakeSource();
        source.put(STATE, new BlockPos(1, 0, 0), new Token(id("first"), 0));
        assertTrue(matches(definition(catalog, "shape").shape(), source, BlockOrientation.NORTH_UP));
        assertTrue(matches(definition(plain, "shape").shape(), source, BlockOrientation.NORTH_UP));
        assertEquals(id("second"), definition(catalog, "shape").displays().get(new BlockPos(1, 0, 0)).options().getFirst().blockId());
        var bad = load(base.formatted(display.replace("test:second", "missing:block")));
        assertEquals("$.pattern[0].display.blocks[0].blockId", assertThrows(StructureJsonException.class,
            () -> bad.resolve(emptyCatalog(), builtins(), new FakeBlocks())).path());
    }

    @Test
    void regexFreezesResolverMembershipAndExplicitAirIsDifferentFromAnUnknownBlock() {
        var blocks = new FakeBlocks();
        var shape = definition(load("""
            {"id":"test:shape","type":"template","pattern":[{"pos":{"x":1,"y":0,"z":0},
              "predicates":[{"id":"prototype_eng:block_id_regex","params":{"pattern":"test:.*"}}]}]}
            """).resolve(emptyCatalog(), builtins(), blocks), "shape").shape();
        blocks.known.clear();
        var source = new FakeSource();
        source.put(STATE, new BlockPos(1, 0, 0), new Token(id("first"), 0));
        assertTrue(matches(shape, source, BlockOrientation.NORTH_UP));
        var air = definition(load("""
            {"id":"test:shape","type":"template","pattern":[{"pos":{"x":1,"y":0,"z":0},"blockId":"minecraft:air"}]}
            """).resolve(emptyCatalog(), builtins(), new FakeBlocks()), "shape").shape();
        source.put(STATE, new BlockPos(1, 0, 0), new Token(new ResourceLocation("minecraft", "air"), 0));
        assertTrue(matches(air, source, BlockOrientation.NORTH_UP));
    }

    @Test
    void jsonValidatorChecksRotateTogetherWithTheStructureOffset() {
        var shape = definition(load("""
            {"id":"test:shape","type":"template","offset":{"x":1,"y":0,"z":0},"pattern":[],
             "validators":[{"id":"prototype_eng:tile_nbt","params":{"checks":[
               {"pos":{"x":2,"y":0,"z":0},"nbt":{"amount":"7"},"blockId":"test:first"}]}}]}
            """).resolve(emptyCatalog(), builtins(), new FakeBlocks()), "shape").shape();
        for (var orientation : List.of(BlockOrientation.EAST_UP, BlockOrientation.SOUTH_UP)) {
            var source = new FakeSource();
            var expected = StructureTransform.of(orientation).apply(new BlockPos(3, 0, 0));
            var tag = new NBTTagCompound();
            tag.setInteger("amount", 7);
            source.put(StructureQueries.TILE_NBT, expected, tag);
            source.put(STATE, expected, new Token(id("first"), 3));
            assertTrue(matches(shape, source, orientation));
            tag.setInteger("amount", 8);
            assertFalse(matches(shape, source, orientation));
        }
    }

    @Test
    void rootCellIsPreservedRatherThanRemovedBeforeItCanBeUsedByChildOccurrences() {
        var catalog = StructureJsonLoader.readSources(Map.of(
            "parent.json", "{\"id\":\"test:parent\",\"type\":\"template\",\"offset\":{\"x\":1,\"y\":0,\"z\":0},\"pattern\":[],\"children\":[\"test:child\"]}",
            "child.json", "{\"id\":\"test:child\",\"type\":\"template\",\"pattern\":[{\"pos\":{\"x\":0,\"y\":0,\"z\":0},\"blockId\":\"test:first\"}]}"
        )).resolve(emptyCatalog(), builtins(), new FakeBlocks());
        var source = new FakeSource();
        source.put(STATE, new BlockPos(1, 0, 0), new Token(id("second"), 0));
        assertFalse(matches(definition(catalog, "parent").shape(), source, BlockOrientation.NORTH_UP));
        source.put(STATE, new BlockPos(1, 0, 0), new Token(id("first"), 0));
        assertTrue(matches(definition(catalog, "parent").shape(), source, BlockOrientation.NORTH_UP));
    }

    @Test
    void unknownAndMalformedValidatorsCannotSilentlyRelaxACompleteCandidate() {
        var unknown = load("{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"validators\":[{\"id\":\"missing:validator\"}]}");
        assertEquals("$.validators[0].id", assertThrows(StructureJsonException.class,
            () -> unknown.resolve(emptyCatalog(), builtins(), new FakeBlocks())).path());
        var malformed = load("""
            {"id":"test:shape","type":"template","pattern":[],"validators":[
              {"id":"prototype_eng:tile_nbt","params":{"checks":[{"pos":{"x":1,"y":0,"z":0}}]}}]}
            """);
        assertEquals("$.validators[0].params.checks[0].nbt", assertThrows(StructureJsonException.class,
            () -> malformed.resolve(emptyCatalog(), builtins(), new FakeBlocks())).path());
    }

    @Test
    void catalogAndMachineRuntimeStayUnpublishedAfterMissingAssemblyThenPublishTheCompleteRetry() {
        var catalog = load("{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[]}").resolve(emptyCatalog(), builtins(), new FakeBlocks());
        var type = MachineType.builder(id("machine"), RecipeText.Cancelled, id("shape")).build();
        var registry = new MachineTypeRegistry();
        registry.register(type);
        registry.freeze();
        assertThrows(IllegalArgumentException.class, () -> registry.resolveRuntime(catalog, List.of()));
        assertThrows(IllegalStateException.class, registry::structureCatalog);
        assertThrows(IllegalStateException.class, () -> registry.structure(type));
        assertThrows(IllegalStateException.class, () -> registry.assembly(type));
        var assembly = MachineAssembly.builder(type, "v1").build();
        registry.resolveRuntime(catalog, List.of(assembly));
        assertSame(catalog, registry.structureCatalog());
        assertSame(definition(catalog, "shape").shape(), registry.structure(type));
        assertSame(assembly, registry.assembly(type));
    }

    @Test
    void oldGsonLiteralAndEscapeLeniencyDoesNotPermitNonJsonSyntax() {
        for (String json : List.of(
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"hideWorldBlocks\":TRUE}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"hideWorldBlocks\":truE}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"hideWorldBlocks\":falsE}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"translationKey\":\"bad\\q\"}",
            "{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"translationKey\":\"bad\ntext\"}"))
            assertThrows(StructureJsonException.class, () -> load(json));
        assertDoesNotThrow(() -> load("{\"id\":\"test:shape\",\"type\":\"template\",\"pattern\":[],\"translationKey\":\"Addon.Title.TRUE\"}"));
    }

    @Test
    void uppercaseNumericExponentReachesTheAddonFactoryAsNumericJson() {
        var registry = new StructurePredicateRegistry();
        registry.register(id("coefficient"), Set.of("value"), (parameters, _) -> {
            assertEquals(new BigDecimal("0.001"), parameters.copy("value").getAsBigDecimal());
            return StructurePredicates.all(List.of());
        });
        registry.freeze();
        var loader = load("""
            {"id":"test:shape","type":"template","pattern":[],"validators":[
              {"id":"test:coefficient","params":{"value":1E-3}}]}
            """);
        assertNotNull(definition(loader.resolve(emptyCatalog(), registry, new FakeBlocks()), "shape"));
    }

    @Test
    void integerDomainsAcceptEquivalentExactValuesAndRejectNonintegralOrOutOfRangeValues() {
        for (String number : List.of("1", "1.0", "1e0", "1E0", "1.0e1", "1e1")) {
            var loader = load("{\"id\":\"test:shape\",\"type\":\"slice\",\"pattern\":[],\"minCount\":" + number + ",\"maxCount\":10}");
            assertNotNull(loader.resolve(emptyCatalog(), builtins(), new FakeBlocks()).get(id("shape")));
        }
        for (String number : List.of("1E-3", "1.5", "2147483648", "2147483648.0", "-2147483649")) {
            var failure = assertThrows(StructureJsonException.class, () -> load(
                "{\"id\":\"test:shape\",\"type\":\"slice\",\"pattern\":[],\"minCount\":" + number + ",\"maxCount\":10}"));
            assertEquals("$.minCount", failure.path());
        }
    }

    @Test
    void existingNonDirectoryRootFailsWhileMissingAndEmptyDirectoriesAreAccepted(@TempDir Path root) throws Exception {
        Path ordinaryFile = root.resolve("structures");
        Files.writeString(ordinaryFile, "{}");
        var failure = assertThrows(StructureJsonException.class, () -> StructureJsonLoader.readDirectory(ordinaryFile));
        assertEquals(ordinaryFile.toString(), failure.source());
        assertEquals("$", failure.path());
        assertTrue(StructureJsonLoader.readDirectory(root.resolve("missing")).resolve(emptyCatalog(), builtins(), new FakeBlocks()).definitions().isEmpty());
        Path empty = Files.createDirectory(root.resolve("empty"));
        assertTrue(StructureJsonLoader.readDirectory(empty).resolve(emptyCatalog(), builtins(), new FakeBlocks()).definitions().isEmpty());
    }

    @Test
    void declaredAddonParameterFieldsRejectUnknownKeysBeforeCallingTheFactory() {
        var registry = new StructurePredicateRegistry();
        registry.register(id("enabled"), Set.of("expected"), (_, _) -> fail("Unknown fields must fail before the addon runs"));
        registry.freeze();
        var loader = load("""
            {"id":"test:shape","type":"template","pattern":[],"validators":[
              {"id":"test:enabled","params":{"expected":true,"typo":false}}]}
            """);
        var failure = assertThrows(StructureJsonException.class, () -> loader.resolve(emptyCatalog(), registry, new FakeBlocks()));
        assertEquals("$.validators[0].params.typo", failure.path());
    }

    @Test
    void addonJsonCopiesCannotChangeFrozenParametersAcrossResolutionAttempts() {
        var registry = new StructurePredicateRegistry();
        registry.register(id("enabled"), Set.of("configuration"), (parameters, _) -> {
            var detached = parameters.copy("configuration").getAsJsonObject();
            detached.addProperty("expected", false);
            var configuration = parameters.parameters("configuration");
            configuration.fields("expected");
            boolean expected = configuration.bool("expected", false);
            return StructurePredicates.test(ENABLED, value -> value == expected);
        });
        registry.freeze();
        var loader = load("""
            {"id":"test:shape","type":"template","pattern":[{"pos":{"x":1,"y":0,"z":0},
             "predicates":[{"id":"test:enabled","params":{"configuration":{"expected":true}}}]}]}
            """);
        var source = new FakeSource();
        source.put(ENABLED, new BlockPos(1, 0, 0), true);
        for (int attempt = 0; attempt < 2; attempt++) {
            var shape = definition(loader.resolve(emptyCatalog(), registry, new FakeBlocks()), "shape").shape();
            assertTrue(matches(shape, source, BlockOrientation.NORTH_UP));
        }
    }

    @Test
    void daytimeValidatorUsesTheWorldDaytimeFactRatherThanOnlyTheWorldTimeCycle() {
        var shape = definition(load("""
            {"id":"test:shape","type":"template","pattern":[],"validators":[{"id":"prototype_eng:day_only"}]}
            """).resolve(emptyCatalog(), builtins(), new FakeBlocks()), "shape").shape();
        var source = new FakeSource();
        source.put(StructureQueries.WORLD_TIME, BlockPos.ORIGIN, 13000L);
        source.put(StructureQueries.DAYTIME, BlockPos.ORIGIN, true);
        assertTrue(matches(shape, source, BlockOrientation.NORTH_UP));
        source.put(StructureQueries.DAYTIME, BlockPos.ORIGIN, false);
        assertFalse(matches(shape, source, BlockOrientation.NORTH_UP));
    }

    private record Token(ResourceLocation id, int meta) {
    }

    private static final class FakeBlocks implements StructureBlockResolver {
        private final ObjectLinkedOpenHashSet<ResourceLocation> known = new ObjectLinkedOpenHashSet<>(List.of(id("first"), id("second"), new ResourceLocation("minecraft", "air")));

        private void check(ResourceLocation id) {
            if (!known.contains(id)) throw new IllegalArgumentException("Unknown block " + id);
        }

        @Override
        public StructurePredicate block(ResourceLocation id) {
            check(id);
            return StructurePredicates.test(STATE, value -> value != null && value.id.equals(id));
        }

        @Override
        public StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt) {
            check(id);
            var state = StructurePredicates.test(STATE, value -> value != null && value.id.equals(id) && value.meta == meta);
            return nbt.isEmpty() ? state : StructurePredicates.all(List.of(state, StructurePredicates.nbt(nbt)));
        }

        @Override
        public StructurePredicate regex(String expression) {
            var pattern = Pattern.compile(expression);
            var frozen = Set.copyOf(known.stream().filter(id -> pattern.matcher(id.toString()).matches()).toList());
            return StructurePredicates.test(STATE, value -> value != null && frozen.contains(value.id));
        }

        @Override
        public StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt) {
            check(id);
            return new StructureDisplay.BlockOption(id, meta, nbt);
        }

        @Override
        public List<StructureDisplay.BlockOption> displayRegex(String expression) {
            var pattern = Pattern.compile(expression);
            return known.stream().filter(id -> pattern.matcher(id.toString()).matches())
                        .map(id -> new StructureDisplay.BlockOption(id, 0, Map.of())).toList();
        }
    }

    private static final class FakeSource implements StructureReadSource {
        private final Object2ObjectOpenHashMap<StructureReadPlan.Read<?>, Object> values = new Object2ObjectOpenHashMap<>();

        private <T> void put(StructureQuery<T> query, BlockPos position, T value) {
            values.put(new StructureReadPlan.Read<>(position, query), value);
        }

        @Override
        public void requireServerThread() {
        }

        @Override
        public boolean isAreaLoaded(StructureBounds bounds) {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(StructureQuery<T> query, BlockPos position) {
            return (T) values.get(new StructureReadPlan.Read<>(position, query));
        }
    }
}
