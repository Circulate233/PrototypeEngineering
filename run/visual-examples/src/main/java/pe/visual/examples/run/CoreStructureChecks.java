package pe.visual.examples.run;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.structure.*;
import com.circulation.prototype_eng.api.structure.json.*;
import com.circulation.prototype_eng.structure.json.StructureJsonLoader;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static pe.visual.examples.run.CoreCases.*;

/** Frozen capture/evaluation and JSON resolver tests; no World/FML bootstrap. */
final class CoreStructureChecks {
    private CoreStructureChecks() {}
    static void run() throws java.io.IOException, InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        structureOwner = Thread.currentThread();
        int before = customVisits();
        var factories = new StructurePredicateRegistry();
        factories.register(id("core_custom"), Set.of(), (_, _) -> Objects.requireNonNull(predicate).value());
        factories.freeze();
        var file = java.nio.file.Path.of("scripts", "fixtures", "core_custom.json");
        if (!java.nio.file.Files.isRegularFile(file)) file = java.nio.file.Path.of("run", "scripts", "fixtures", "core_custom.json");
        var sources = Map.of("core_custom.json", java.nio.file.Files.readString(file));
        var json = StructureJsonLoader.readSources(sources).resolve(new StructureCatalog(List.of()), factories, new NoBlocks());
        NativeChecks.check("core_json_resolution_no_callback", customVisits() == before);
        boolean closed = false;
        try { factories.register(id("core_late"), Set.of(), (_, _) -> predicate.value()); }
        catch (IllegalStateException expected) { closed = true; }
        NativeChecks.check("core_json_predicate_freeze", closed);
        var capturedSource = new Source(structureOwner);
        try (var worker = Executors.newSingleThreadExecutor()) {
            for (var definition : List.of(Objects.requireNonNull(shape).definition().shape(), Objects.requireNonNull(json.get(id("core_custom_json"))).shape())) {
                var plan = new StructurePlan(definition, new BlockPos(10, 64, 10), BlockOrientation.EAST_UP);
                var snapshot = StructureSnapshot.capture(plan.reads(), capturedSource);
                var result = worker.submit(() -> plan.evaluate(snapshot)).get(5, TimeUnit.SECONDS);
                NativeChecks.check("core_custom_match_" + definition.id().getPath(), result.status() == StructurePredicateResult.MATCHED && result.isCurrent(capturedSource));
                capturedSource.brightness = 18;
                NativeChecks.check("core_custom_stale_" + definition.id().getPath(), !result.isCurrent(capturedSource));
                var changed = StructureSnapshot.capture(plan.reads(), capturedSource);
                NativeChecks.check("core_custom_changed_" + definition.id().getPath(), worker.submit(() -> plan.evaluate(changed)).get(5, TimeUnit.SECONDS).status() == StructurePredicateResult.MISMATCH);
                // Held snapshot still evaluates with captured 17 after the source changes.
                NativeChecks.check("core_custom_frozen_" + definition.id().getPath(), worker.submit(() -> plan.evaluate(snapshot)).get(5, TimeUnit.SECONDS).status() == StructurePredicateResult.MATCHED);
                capturedSource.brightness = 17;
            }
        }
        NativeChecks.check("core_custom_worker_callbacks", customVisits() - before == 6);
    }
    private static final class Source implements StructureReadSource {
        private final Thread owner;
        private int brightness = 17;
        private Source(Thread owner) { this.owner = owner; }
        @Override public void requireServerThread() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("Capture/freshness left owner thread");
        }
        @Override public boolean isAreaLoaded(StructureBounds bounds) { requireServerThread(); return true; }
        @Override @SuppressWarnings("unchecked") public <T> T read(StructureQuery<T> requested, BlockPos position) {
            requireServerThread();
            Object fact;
            if (requested == query.value()) fact = new NBTTagInt(brightness);
            else if (requested == StructureQueries.BIOME_NAME) fact = "Birch Forest";
            else if (requested == StructureQueries.BIOME) fact = id("plains");
            else if (requested == StructureQueries.DIMENSION) fact = 12;
            else if (requested == StructureQueries.LIGHT) fact = 9;
            else if (requested == StructureQueries.WORLD_TIME) fact = 6000L;
            else throw new IllegalArgumentException("Undeclared fixture fact: " + requested.id());
            return (T) fact;
        }
    }
    private static final class NoBlocks implements StructureBlockResolver {
        private IllegalArgumentException unexpected() { return new IllegalArgumentException("Core JSON must not resolve blocks/displays"); }
        @Override public StructurePredicate block(ResourceLocation id) { throw unexpected(); }
        @Override public StructurePredicate state(ResourceLocation id, int meta, Map<String, String> nbt) { throw unexpected(); }
        @Override public StructurePredicate regex(String expression) { throw unexpected(); }
        @Override public StructureDisplay.BlockOption displayState(ResourceLocation id, int meta, Map<String, String> nbt) { throw unexpected(); }
        @Override public List<StructureDisplay.BlockOption> displayRegex(String expression) { throw unexpected(); }
    }
}
