package com.circulation.prototype_eng.api.data;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Ordinary machine/process data, independent of script types. Each operation is atomic; live state
 * owns its top-level NBT while child tags and captured snapshots are borrowed read-only. Revisions let independent persistence
 * and full-sync consumers observe changes without clearing one another's dirty state.
 */
public final class ComponentData {
    private NBTTagCompound storage = new NBTTagCompound();
    private long revision;
    private Snapshot captured;

    public synchronized long revision() {
        return revision;
    }

    public synchronized int size() {
        return storage.getSize();
    }

    public synchronized boolean has(String key) {
        return storage.hasKey(key);
    }

    /**
     * Returns a borrowed read-only value. Changes must go through set or merge to advance the revision.
     */
    @Nullable
    @SuppressWarnings("DataFlowIssue")
    // Native NBTTagCompound.getTag directly returns Map.get, including null for absent keys.
    public synchronized NBTBase get(String key) {
        return storage.getTag(key);
    }

    /**
     * Null removes a value; unchanged contents do not advance the revision. Supplied tags must not be mutated after this call.
     */
    public synchronized void set(String key, @Nullable NBTBase value) {
        if (Objects.equals(storage.getTag(key), value)) return;
        long next = Math.incrementExact(revision);
        if (value == null) storage.removeTag(key);
        else storage.setTag(key, value);
        revision = next;
        captured = null;
    }

    public void remove(String key) {
        set(key, null);
    }

    public synchronized void clear() {
        if (storage.getSize() == 0) return;
        long next = Math.incrementExact(revision);
        storage = new NBTTagCompound();
        revision = next;
        captured = null;
    }

    /**
     * Replaces top-level keys. Supplied child tags are borrowed and must not be mutated after this call.
     */
    public synchronized void merge(NBTTagCompound values) {
        NBTTagCompound replacement = null;
        for (var key : values.getKeySet()) {
            var value = values.getTag(key);
            if (Objects.equals(storage.getTag(key), value)) continue;
            if (replacement == null) {
                replacement = new NBTTagCompound();
                for (var current : storage.getKeySet()) replacement.setTag(current, storage.getTag(current));
            }
            replacement.setTag(key, value);
        }
        if (replacement == null) return;
        long next = Math.incrementExact(revision);
        storage = replacement;
        revision = next;
        captured = null;
    }

    /**
     * Replaces the top-level index for codecs and synchronization; supplied child tags remain read-only.
     */
    public synchronized void replace(NBTTagCompound values) {
        if (storage.equals(values)) return;
        long next = Math.incrementExact(revision);
        storage = copyRoot(values);
        revision = next;
        captured = null;
    }

    public synchronized Snapshot snapshot() {
        if (captured == null) captured = new Snapshot(revision, copyRoot(storage));
        return captured;
    }

    private static NBTTagCompound copyRoot(NBTTagCompound source) {
        var result = new NBTTagCompound();
        for (var key : source.getKeySet()) result.setTag(key, source.getTag(key));
        return result;
    }

    /**
     * Data is a completed capture; producers and consumers must not modify it after publication.
     */
    public record Snapshot(long revision, NBTTagCompound data) {
    }
}
