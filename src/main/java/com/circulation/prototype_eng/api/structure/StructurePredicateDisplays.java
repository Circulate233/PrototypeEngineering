package com.circulation.prototype_eng.api.structure;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Definition-time projection of retained facts, never an evaluation of matching callbacks.
 */
final class StructurePredicateDisplays {
    private static final int MAX_OPTIONS = 32;
    private static final int MAX_SEARCH_STEPS = 8192;

    private StructurePredicateDisplays() {
    }

    static @Nullable StructureDisplay describe(StructurePredicate root) {
        var facts = new Reference2ObjectOpenHashMap<StructurePredicate, Fact>();
        var queue = new ObjectArrayFIFOQueue<StructurePredicate>();
        var exactSeeds = new ObjectLinkedOpenHashSet<Seed>();
        var blockSeeds = new ObjectLinkedOpenHashSet<Seed>();
        queue.enqueue(root);
        while (!queue.isEmpty()) {
            var predicate = queue.dequeue();
            if (facts.containsKey(predicate)) continue;
            var fact = StructurePredicates.displayFact(predicate);
            facts.put(predicate, fact);
            if (fact instanceof Branch branch) for (int index = 0; index < branch.children.size(); index++) {
                var child = branch.children.get(index);
                queue.enqueue(child);
            }
            if (fact instanceof Material(var exact, var membership, var authored)) {
                if (exact != null) exactSeeds.add(new Seed(StructureDisplay.BlockOption.of(exact, Map.of()), exact));
                if (authored != null) {
                    var elements3 = authored.options();
                    for (int index3 = 0; index3 < elements3.size(); index3++) {
                        var option = elements3.get(index3);
                        exactSeeds.add(new Seed(new StructureDisplay.BlockOption(option.blockId(), option.meta(), Map.of(), option.properties()), null));
                    }
                }
                if (membership != null) {
                    // Collect all membership seeds before AND filtering; only final representatives are bounded.
                    for (var block : membership) {
                        var state = block.getDefaultState();
                        blockSeeds.add(new Seed(StructureDisplay.BlockOption.of(state, Map.of()), state));
                    }
                }
            }
        }
        if (facts.get(root) instanceof Material material && material.authored != null) return material.authored;
        exactSeeds.addAll(blockSeeds);
        var options = new ObjectLinkedOpenHashSet<StructureDisplay.BlockOption>();
        boolean complete = true;
        String tileNbt = null;
        boolean tileSeen = false;
        boolean tileAmbiguous = false;
        for (var seed : exactSeeds) {
            // Resolve every hard material constraint before an NBT effort limit can retain a partial state.
            var support = stateSupport(root, facts, seed);
            if (!support.pass || !support.localMaterial) continue;
            var attempts = new ObjectArrayList<Attempt>();
            attempts.push(new Attempt(new Pending(root, null), Map.of(), false, false, null, false));
            int steps = 0;
            boolean found = false;
            boolean exhausted = false;
            search:
            while (!attempts.isEmpty() && steps++ < MAX_SEARCH_STEPS) {
                var attempt = attempts.pop();
                var pending = attempt.pending;
                if (pending == null) {
                    if (!attempt.localMaterial) {
                        complete = false;
                        continue;
                    }
                    found = true;
                    options.add(new StructureDisplay.BlockOption(seed.option.blockId(), seed.option.meta(), attempt.nbt, seed.option.properties()));
                    complete &= !attempt.uncertain;
                    if (tileAmbiguous || attempt.tileConflict || tileSeen && !Objects.equals(tileNbt, attempt.tileNbt)) {
                        tileAmbiguous = true;
                        complete = false;
                        tileNbt = null;
                    } else if (!tileSeen) {
                        tileSeen = true;
                        tileNbt = attempt.tileNbt;
                    }
                    if (options.size() == MAX_OPTIONS) break;
                    continue;
                }
                var fact = facts.get(pending.predicate);
                switch (fact) {
                    case Branch(var children, var all) -> {
                        if (children.size() > MAX_SEARCH_STEPS - steps) {
                            exhausted = true;
                            break search;
                        }
                        steps += children.size();
                        if (all) {
                            var next = pending.next;
                            for (int i = children.size() - 1; i >= 0; i--) next = new Pending(children.get(i), next);
                            attempts.push(new Attempt(next, attempt.nbt, attempt.localMaterial, attempt.uncertain, attempt.tileNbt, attempt.tileConflict));
                        } else {
                            for (int i = children.size() - 1; i >= 0; i--)
                                attempts.push(new Attempt(new Pending(children.get(i), pending.next), attempt.nbt, attempt.localMaterial, attempt.uncertain, attempt.tileNbt, attempt.tileConflict));
                        }
                    }
                    case Nbt(var expected) -> {
                        var merged = merge(attempt.nbt, expected);
                        // Even an empty NBT predicate requires a tile; an empty field map cannot encode that fact.
                        if (merged != null)
                            attempts.push(new Attempt(pending.next, merged, attempt.localMaterial, attempt.uncertain || expected.isEmpty(), attempt.tileNbt, attempt.tileConflict));
                    }
                    case Material(var exact, var membership, var authored) -> {
                        if (exact != null && !sameState(seed, exact)) continue;
                        if (membership != null && !member(seed, membership)) continue;
                        if (authored == null) {
                            attempts.push(new Attempt(pending.next, attempt.nbt, true, attempt.uncertain, attempt.tileNbt, attempt.tileConflict));
                        } else {
                            if (authored.options().size() > MAX_SEARCH_STEPS - steps) {
                                exhausted = true;
                                break search;
                            }
                            steps += authored.options().size();
                            var elements5 = authored.options();
                            for (int index5 = 0; index5 < elements5.size(); index5++) {
                                var option = elements5.get(index5);
                                if (!compatibleAuthor(seed.option, option)) continue;
                                var merged = merge(attempt.nbt, option.nbt());
                                if (merged == null) continue;
                                boolean tileConflict = attempt.tileConflict || attempt.tileNbt != null && authored.tileNbt() != null
                                    && !attempt.tileNbt.equals(authored.tileNbt());
                                attempts.push(new Attempt(pending.next, merged, true, attempt.uncertain || !authored.complete() || tileConflict,
                                    tileConflict ? null : authored.tileNbt() == null ? attempt.tileNbt : authored.tileNbt(), tileConflict));
                            }
                        }
                    }
                    case Unknown ignored ->
                        attempts.push(new Attempt(pending.next, attempt.nbt, attempt.localMaterial, true, attempt.tileNbt, attempt.tileConflict));
                }
            }
            if (exhausted || !attempts.isEmpty()) {
                complete = false;
                // An exhausted search preserves a known state as explicitly partial data, never a witness.
                if (!found) options.add(seed.option);
            }
            if (options.size() >= MAX_OPTIONS) {
                complete = false;
                break;
            }
        }
        if (options.isEmpty()) return null;
        var text = new StringBuilder();
        for (var option : options) {
            field(text, option.blockId().toString());
            text.append('@').append(option.meta());
            fields(text, 'p', option.properties());
            fields(text, 'n', option.nbt());
        }
        text.append('t');
        field(text, tileNbt == null ? "" : tileNbt);
        text.append(";complete=").append(complete);
        return new StructureDisplay("intrinsic:" + UUID.nameUUIDFromBytes(text.toString().getBytes(StandardCharsets.UTF_8)), List.copyOf(options), tileNbt, complete);
    }

    private static Support stateSupport(StructurePredicate root, Reference2ObjectOpenHashMap<StructurePredicate, Fact> facts, Seed seed) {
        var support = new Reference2ObjectOpenHashMap<StructurePredicate, Support>();
        var pending = new ObjectArrayList<Visit>();
        pending.push(new Visit(root, false));
        while (!pending.isEmpty()) {
            var visit = pending.pop();
            if (support.containsKey(visit.predicate)) continue;
            var fact = facts.get(visit.predicate);
            if (fact instanceof Branch(var children, var all)) {
                if (!visit.exit) {
                    pending.push(new Visit(visit.predicate, true));
                    for (int i = children.size() - 1; i >= 0; i--) pending.push(new Visit(children.get(i), false));
                    continue;
                }
                boolean pass = all, local = false;
                for (int index4 = 0; index4 < children.size(); index4++) {
                    var child = children.get(index4);
                    var result = support.get(child);
                    pass = all ? pass && result.pass : pass || result.pass;
                    local |= result.pass && result.localMaterial;
                }
                support.put(visit.predicate, new Support(pass, pass && local));
            } else if (fact instanceof Material(var exact, var membership, var authored)) {
                boolean pass = (exact == null || sameState(seed, exact)) && (membership == null || member(seed, membership))
                    && (authored == null || compatibleAuthor(seed.option, authored));
                support.put(visit.predicate, new Support(pass, pass));
            } else {
                support.put(visit.predicate, new Support(true, false));
            }
        }
        return support.get(root);
    }

    private static void field(StringBuilder text, String value) {
        text.append(value.length()).append(':').append(value);
    }

    private static void fields(StringBuilder text, char domain, Map<String, String> values) {
        text.append(domain).append(values.size()).append(':');
        new Object2ObjectRBTreeMap<>(values).forEach((name, value) -> {
            field(text, name);
            field(text, value);
        });
    }

    private static boolean compatibleAuthor(StructureDisplay.BlockOption seed, StructureDisplay authored) {
        var elements = authored.options();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var option = elements.get(index2);
            if (compatibleAuthor(seed, option)) return true;
        }
        return false;
    }

    private static boolean compatibleAuthor(StructureDisplay.BlockOption seed, StructureDisplay.BlockOption authored) {
        if (!seed.blockId().equals(authored.blockId()) || seed.meta() != authored.meta()) return false;
        for (var entry : authored.properties().entrySet())
            if (!entry.getValue().equals(seed.properties().get(entry.getKey()))) return false;
        return true;
    }

    private static boolean member(Seed seed, ReferenceSet<Block> blocks) {
        if (seed.state != null) return blocks.contains(seed.state.getBlock());
        for (var block : blocks) if (seed.option.blockId().equals(block.getRegistryName())) return true;
        return false;
    }

    private static boolean sameState(Seed seed, IBlockState state) {
        return seed.state != null ? seed.state == state : sameOption(seed.option, StructureDisplay.BlockOption.of(state, Map.of()));
    }

    private static boolean sameOption(StructureDisplay.BlockOption first, StructureDisplay.BlockOption second) {
        return first.blockId().equals(second.blockId()) && first.meta() == second.meta() && first.properties().equals(second.properties());
    }

    private static @Nullable Map<String, String> merge(Map<String, String> first, Map<String, String> second) {
        if (second.isEmpty()) return first;
        var merged = new Object2ObjectLinkedOpenHashMap<>(first);
        for (var entry : second.entrySet()) {
            var prior = merged.putIfAbsent(entry.getKey(), entry.getValue());
            if (prior != null && !prior.equals(entry.getValue())) return null;
        }
        return Map.copyOf(merged);
    }

    enum Unknown implements Fact {INSTANCE}

    sealed interface Fact permits Material, Nbt, Branch, Unknown {
    }

    static final class Cache {
        private boolean resolved;
        private @Nullable StructureDisplay display;

        synchronized @Nullable StructureDisplay get(StructurePredicate predicate) {
            if (!resolved) {
                display = describe(predicate);
                resolved = true;
            }
            return display;
        }
    }

    record Material(@Nullable IBlockState exact, @Nullable ReferenceSet<Block> membership,
                    @Nullable StructureDisplay authored) implements Fact {
    }

    record Nbt(Map<String, String> expected) implements Fact {
    }

    record Branch(List<StructurePredicate> children, boolean all) implements Fact {
    }

    private record Seed(StructureDisplay.BlockOption option, @Nullable IBlockState state) {
    }

    /**
     * A persistent continuation makes deep AND/OR trees iterative without constructing DNF.
     */
    private record Pending(StructurePredicate predicate, @Nullable Pending next) {
    }

    private record Attempt(@Nullable Pending pending, Map<String, String> nbt, boolean localMaterial,
                           boolean uncertain, @Nullable String tileNbt, boolean tileConflict) {
    }

    private record Support(boolean pass, boolean localMaterial) {
    }

    private record Visit(StructurePredicate predicate, boolean exit) {
    }
}
