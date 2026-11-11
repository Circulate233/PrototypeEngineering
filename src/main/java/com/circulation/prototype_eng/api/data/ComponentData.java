package com.circulation.prototype_eng.api.data;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Ordinary machine/process data, independent of script types. Each operation is atomic and every
 * mutable NBT boundary is copied. Revisions let independent persistence and full-sync consumers
 * observe changes without clearing one another's dirty state. Access grants no world authority.
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
    public synchronized NBTBase get(String key) {
        if (!storage.hasKey(Objects.requireNonNull(key))) return null;
        return storage.getTag(key).copy();
    }

    /** Null removes a value; unchanged contents do not advance the revision. */
    public synchronized void set(String key, @Nullable NBTBase value) {
        Objects.requireNonNull(key);
        var owned = value == null ? null : value.copy();
        if (Objects.equals(storage.getTag(key), owned)) return;
        long next = Math.incrementExact(revision);
        if (owned == null) storage.removeTag(key);
        else storage.setTag(key, owned);
        revision = next;
    }

    public void remove(String key) {
        set(key, null);
    }

    public synchronized void clear() {
        install(new NBTTagCompound());
    }

    /** Replaces top-level keys, matching the original ordinary ZSData merge behavior. */
    public synchronized void merge(NBTTagCompound values) {
        var owned = Objects.requireNonNull(values).copy();
        var replacement = storage.copy();
        for (var key : owned.getKeySet()) replacement.setTag(key, owned.getTag(key));
        install(replacement);
    }

    /** Full replacement for explicit codecs and received full-component synchronization. */
    public synchronized void replace(NBTTagCompound values) {
        install(Objects.requireNonNull(values).copy());
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(revision, storage);
    }

    private void install(NBTTagCompound replacement) {
        if (storage.equals(replacement)) return;
        long next = Math.incrementExact(revision);
        storage = replacement;
        revision = next;
    }

    public record Snapshot(long revision, NBTTagCompound data) {
        public Snapshot {
            if (revision < 0) throw new IllegalArgumentException("Negative component-data revision");
            data = Objects.requireNonNull(data).copy();
        }

        @Override
        public NBTTagCompound data() {
            return data.copy();
        }
    }
}
