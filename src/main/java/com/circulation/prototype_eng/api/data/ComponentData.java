package com.circulation.prototype_eng.api.data;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Ordinary machine/process data, independent of script types. Each operation is atomic; live state
 * owns its mutable NBT while captured snapshots are read-only. Revisions let independent persistence
 * and full-sync consumers observe changes without clearing one another's dirty state.
 */
public final class ComponentData {
    private NBTTagCompound storage = new NBTTagCompound();
    private long revision;

    public synchronized long revision() {
        return revision;
    }

    public synchronized int size() {
        return storage.getSize();
    }

    public synchronized boolean has(String key) {
        return storage.hasKey(Objects.requireNonNull(key));
    }

    @Nullable
    @SuppressWarnings("DataFlowIssue")
    // Native NBTTagCompound.getTag directly returns Map.get, including null for absent keys.
    public synchronized NBTBase get(String key) {
        var value = storage.getTag(Objects.requireNonNull(key));
        return value == null ? null : value.copy();
    }

    /**
     * Null removes a value; unchanged contents do not advance the revision.
     */
    public synchronized void set(String key, @Nullable NBTBase value) {
        Objects.requireNonNull(key);
        if (Objects.equals(storage.getTag(key), value)) return;
        long next = Math.incrementExact(revision);
        if (value == null) storage.removeTag(key);
        else storage.setTag(key, value.copy());
        revision = next;
    }

    public void remove(String key) {
        set(key, null);
    }

    public synchronized void clear() {
        if (storage.getSize() == 0) return;
        long next = Math.incrementExact(revision);
        storage = new NBTTagCompound();
        revision = next;
    }

    /**
     * Replaces top-level keys, matching the original ordinary ZSData merge behavior.
     */
    public synchronized void merge(NBTTagCompound values) {
        NBTTagCompound replacement = null;
        for (var key : values.getKeySet()) {
            var value = values.getTag(key);
            if (Objects.equals(storage.getTag(key), value)) continue;
            if (replacement == null) {
                replacement = new NBTTagCompound();
                // Existing child tags stay privately owned and are never mutated in place.
                for (var current : storage.getKeySet()) replacement.setTag(current, storage.getTag(current));
            }
            replacement.setTag(key, value.copy());
        }
        if (replacement == null) return;
        long next = Math.incrementExact(revision);
        storage = replacement;
        revision = next;
    }

    /**
     * Full replacement for explicit codecs and received full-component synchronization.
     */
    public synchronized void replace(NBTTagCompound values) {
        if (storage.equals(values)) return;
        long next = Math.incrementExact(revision);
        storage = values.copy();
        revision = next;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(revision, storage.copy());
    }

    /**
     * Data is a completed capture; producers and consumers must not modify it after publication.
     */
    public record Snapshot(long revision, NBTTagCompound data) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("Negative component-data revision");
            Objects.requireNonNull(data);
        }
    }
}
