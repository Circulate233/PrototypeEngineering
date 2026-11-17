package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Incremental world store. One redo journal coordinates independently replaced owner/operation files.
 * File sync and atomic replacement provide process-crash recovery; directory power-loss durability
 * depends on the filesystem. The server thread is the sole owner of this store.
 */
public final class ResearchStateStore {
    private final Path legacy;
    private final Path root;
    private final Path metaPath;
    private final Path pendingPath;
    private final Path migrationPath;
    private final ResearchWorldNbt codec;
    private @Nullable State state;
    private boolean loaded;

    public ResearchStateStore(Path legacyTarget, ResearchWorldNbt codec) {
        legacy = legacyTarget.toAbsolutePath().normalize();
        var name = legacy.getFileName().toString();
        root = legacy.resolveSibling(name.endsWith(".dat") ? name.substring(0, name.length() - 4) : name + "-store");
        metaPath = root.resolve("meta.dat");
        pendingPath = root.resolve("pending.dat");
        migrationPath = root.resolveSibling(root.getFileName() + ".migrated");
        this.codec = codec;
    }

    /**
     * A corrupt initialized store never falls back to the retained legacy checkpoint.
     */
    public @Nullable ResearchWorldSnapshot read() throws IOException {
        loaded = false;
        state = null;
        if (exists(root)) {
            directory(root);
            recover();
            if (!exists(metaPath)) {
                throw new IOException("Research store has no committed metadata or redo journal: " + root);
            }
            state = readState(readMeta(readTag(metaPath)));
            var snapshot = snapshot(state);
            loaded = true;
            return snapshot;
        }
        if (exists(migrationPath)) throw new IOException("Migrated research store is missing: " + root);
        if (exists(legacy)) {
            var envelope = readTag(legacy);
            require(envelope, "data", 10);
            var data = envelope.getCompoundTag("data");
            require(data, "ServerTime", 4);
            for (var kind : Kind.VALUES) compounds(data, kind.tag);
        }
        var previous = new ResearchStateFile(legacy, codec).read();
        loaded = true;
        if (previous != null) write(previous);
        return previous;
    }

    /**
     * Unchanged shards, including other players' operation envelopes, are never rewritten.
     */
    public void write(ResearchWorldSnapshot snapshot) throws IOException {
        if (!loaded) read();
        if (exists(pendingPath)) {
            recover();
            state = readState(readMeta(readTag(metaPath)));
        }
        var encoded = codec.encode(snapshot);
        var next = new Object2ObjectLinkedOpenHashMap<Key, SavedShard>();
        var changed = new Object2ObjectLinkedOpenHashMap<Key, byte[]>();
        for (var kind : Kind.VALUES) {
            var list = compounds(encoded, kind.tag);
            for (int index = 0; index < list.tagCount(); index++) {
                var data = list.getCompoundTagAt(index);
                var key = new Key(kind, kind.identity(data));
                var previous = state == null ? null : state.shards.get(key);
                if (previous != null && previous.data.equals(data)) {
                    next.put(key, previous);
                } else {
                    var payload = compress(data);
                    next.put(key, new SavedShard(data, hash(payload)));
                    changed.put(key, payload);
                }
            }
        }
        var removed = new ObjectArrayList<Key>();
        if (state != null) {
            for (var key : state.shards.keySet()) if (!next.containsKey(key)) removed.add(key);
            if (changed.isEmpty() && removed.isEmpty() && state.meta.serverTime == snapshot.serverTime()
                && new ObjectArrayList<>(state.shards.keySet()).equals(new ObjectArrayList<>(next.keySet()))) return;
        }
        var entries = new ObjectArrayList<IndexEntry>(next.size());
        for (var entry : next.object2ObjectEntrySet())
            entries.add(new IndexEntry(entry.getKey(), entry.getValue().hash));
        var meta = new Meta(UUID.randomUUID(), snapshot.serverTime(), entries);
        var pending = new Pending(state == null ? null : state.meta.commit, meta, changed, removed);
        publishPending(compress(encodePending(pending)));
        apply(pending, false);
        state = new State(meta, next);
    }

    private void publishPending(byte[] bytes) throws IOException {
        if (exists(root)) {
            directory(root);
            atomicWrite(pendingPath, bytes);
            return;
        }
        directory(root.getParent());
        var staging = Files.createTempDirectory(root.getParent(), root.getFileName() + "-bootstrap-");
        try {
            // A visible root always starts with its complete redo journal, even on first migration.
            atomicWrite(staging.resolve("pending.dat"), bytes);
            Files.move(staging, root, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                Files.deleteIfExists(staging.resolve("pending.dat"));
                Files.deleteIfExists(staging);
            } catch (IOException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private void recover() throws IOException {
        if (!exists(pendingPath)) return;
        var pending = readPending(readTag(pendingPath));
        apply(pending, true);
    }

    private void apply(Pending pending, boolean verifyUnchanged) throws IOException {
        var current = exists(metaPath) ? readMeta(readTag(metaPath)) : null;
        boolean atBase = current == null ? pending.base == null : current.commit.equals(pending.base);
        boolean atTarget = current != null && current.commit.equals(pending.meta.commit);
        if (!atBase && !atTarget)
            throw new IOException("Research redo journal belongs to a different commit: " + pendingPath);
        for (var entry : pending.changed.object2ObjectEntrySet()) {
            var path = path(entry.getKey());
            var parent = path.getParent();
            if (!exists(parent)) Files.createDirectory(parent);
            else directory(parent);
            atomicWrite(path, entry.getValue());
        }
        // Recovery validates every referenced postimage before publishing or cleaning the journal.
        if (verifyUnchanged) readState(pending.meta);
        atomicWrite(metaPath, compress(encodeMeta(pending.meta)));
        // Publish removals in the index before deleting obsolete files.
        for (var key : pending.removed) {
            var path = path(key);
            if (exists(path)) {
                regular(path);
                Files.delete(path);
            }
        }
        // Retained legacy bytes must never revive after an initialized store is lost.
        if (pending.base == null && exists(legacy))
            atomicWrite(migrationPath, pending.meta.commit.toString().getBytes(StandardCharsets.UTF_8));
        Files.delete(pendingPath);
    }

    private State readState(Meta meta) throws IOException {
        var shards = new Object2ObjectLinkedOpenHashMap<Key, SavedShard>(meta.entries.size());
        for (var entry : meta.entries) {
            var target = path(entry.key);
            directory(target.getParent());
            var bytes = readBytes(target);
            if (!Arrays.equals(entry.hash, hash(bytes)))
                throw new IOException("Research shard checksum differs: " + target);
            var data = decompress(bytes);
            if (!entry.key.id.equals(entry.key.kind.identity(data)))
                throw new IOException("Research shard identity differs: " + target);
            shards.put(entry.key, new SavedShard(data, entry.hash));
        }
        return new State(meta, shards);
    }

    private ResearchWorldSnapshot snapshot(State saved) throws IOException {
        var data = new NBTTagCompound();
        data.setLong("ServerTime", saved.meta.serverTime);
        for (var kind : Kind.VALUES) {
            var list = new NBTTagList();
            for (var entry : saved.shards.object2ObjectEntrySet()) {
                if (entry.getKey().kind == kind) list.appendTag(entry.getValue().data);
            }
            data.setTag(kind.tag, list);
        }
        try {
            return codec.decode(data);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid research store content: " + root, failure);
        }
    }

    private Path path(Key key) {
        var filename = key.kind == Kind.TEAM ? HexFormat.of().formatHex(hash(key.id.getBytes(StandardCharsets.UTF_8))) : key.id;
        return root.resolve(key.kind.directory).resolve(filename + ".dat");
    }

    private static NBTTagCompound encodeMeta(Meta meta) {
        var tag = new NBTTagCompound();
        tag.setString("Commit", meta.commit.toString());
        tag.setLong("ServerTime", meta.serverTime);
        for (var kind : Kind.VALUES) {
            var list = new NBTTagList();
            for (var entry : meta.entries) {
                if (entry.key.kind != kind) continue;
                var value = new NBTTagCompound();
                value.setString("Id", entry.key.id);
                value.setByteArray("Hash", entry.hash);
                list.appendTag(value);
            }
            tag.setTag(kind.tag, list);
        }
        return tag;
    }

    private static Meta readMeta(NBTTagCompound tag) throws IOException {
        var commit = uuid(string(tag, "Commit"));
        require(tag, "ServerTime", 4);
        var entries = new ObjectArrayList<IndexEntry>();
        for (var kind : Kind.VALUES) {
            var list = compounds(tag, kind.tag);
            for (int index = 0; index < list.tagCount(); index++) {
                var value = list.getCompoundTagAt(index);
                var key = new Key(kind, kind.validate(string(value, "Id")));
                require(value, "Hash", 7);
                var digest = value.getByteArray("Hash");
                if (digest.length != 32) throw new IOException("Invalid research shard checksum");
                entries.add(new IndexEntry(key, digest));
            }
        }
        return new Meta(commit, tag.getLong("ServerTime"), entries);
    }

    private static NBTTagCompound encodePending(Pending pending) {
        var tag = new NBTTagCompound();
        tag.setString("BaseCommit", pending.base == null ? "" : pending.base.toString());
        tag.setTag("Meta", encodeMeta(pending.meta));
        var changed = new NBTTagList();
        for (var entry : pending.changed.object2ObjectEntrySet()) {
            var value = keyTag(entry.getKey());
            value.setByteArray("Payload", entry.getValue());
            changed.appendTag(value);
        }
        tag.setTag("Changed", changed);
        var removed = new NBTTagList();
        for (var key : pending.removed) removed.appendTag(keyTag(key));
        tag.setTag("Removed", removed);
        return tag;
    }

    private static NBTTagCompound keyTag(Key key) {
        var tag = new NBTTagCompound();
        tag.setString("Kind", key.kind.tag);
        tag.setString("Id", key.id);
        return tag;
    }

    private static Pending readPending(NBTTagCompound tag) throws IOException {
        var base = string(tag, "BaseCommit");
        require(tag, "Meta", 10);
        var meta = readMeta(tag.getCompoundTag("Meta"));
        var expected = new Object2ObjectLinkedOpenHashMap<Key, byte[]>(meta.entries.size());
        for (var entry : meta.entries) expected.put(entry.key, entry.hash);
        var changed = new Object2ObjectLinkedOpenHashMap<Key, byte[]>();
        var list = compounds(tag, "Changed");
        for (int index = 0; index < list.tagCount(); index++) {
            var value = list.getCompoundTagAt(index);
            var key = readKey(value);
            require(value, "Payload", 7);
            var bytes = value.getByteArray("Payload");
            if (!Arrays.equals(expected.get(key), hash(bytes)) || !key.id.equals(key.kind.identity(decompress(bytes))))
                throw new IOException("Research redo postimage differs from its target index");
            changed.put(key, bytes);
        }
        var removed = new ObjectArrayList<Key>();
        var removals = compounds(tag, "Removed");
        for (int index = 0; index < removals.tagCount(); index++) {
            var key = readKey(removals.getCompoundTagAt(index));
            if (expected.containsKey(key)) throw new IOException("Research redo removes a referenced shard");
            removed.add(key);
        }
        return new Pending(base.isEmpty() ? null : uuid(base), meta, changed, removed);
    }

    private static Key readKey(NBTTagCompound tag) throws IOException {
        var name = string(tag, "Kind");
        for (var kind : Kind.VALUES) if (kind.tag.equals(name)) return new Key(kind, kind.validate(string(tag, "Id")));
        throw new IOException("Unknown research shard category: " + name);
    }

    private static NBTTagList compounds(NBTTagCompound tag, String name) throws IOException {
        require(tag, name, 9);
        var list = (NBTTagList) tag.getTag(name);
        if (list.tagCount() != 0 && list.getTagType() != 10)
            throw new IOException("Invalid research compound list: " + name);
        return list;
    }

    private static String string(NBTTagCompound tag, String name) throws IOException {
        require(tag, name, 8);
        return tag.getString(name);
    }

    private static void require(NBTTagCompound tag, String name, int type) throws IOException {
        if (!tag.hasKey(name, type)) throw new IOException("Missing or invalid research field: " + name);
    }

    private static UUID uuid(String value) throws IOException {
        try {
            var id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IOException("Noncanonical research UUID: " + value);
            return id;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid research UUID: " + value, failure);
        }
    }

    private static byte[] compress(NBTTagCompound tag) throws IOException {
        var bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, bytes);
        return bytes.toByteArray();
    }

    private static NBTTagCompound decompress(byte[] bytes) throws IOException {
        try (var input = new ByteArrayInputStream(bytes)) {
            return CompressedStreamTools.readCompressed(input);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid compressed research content", failure);
        }
    }

    private static NBTTagCompound readTag(Path path) throws IOException {
        return decompress(readBytes(path));
    }

    private static byte[] readBytes(Path path) throws IOException {
        regular(path);
        return Files.readAllBytes(path);
    }

    private static boolean exists(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return true;
    }

    private static void regular(Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile())
            throw new IOException("Research state is not a regular file: " + path);
    }

    private static void directory(Path path) throws IOException {
        if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory())
            throw new IOException("Research state path is not a directory: " + path);
    }

    private static void atomicWrite(Path target, byte[] bytes) throws IOException {
        directory(target.getParent());
        if (exists(target)) regular(target);
        var temporary = Files.createTempFile(target.getParent(), "research-", ".tmp");
        boolean moved = false;
        try {
            try (var output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
            try (var output = FileChannel.open(target, StandardOpenOption.WRITE)) {
                output.force(true);
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (!moved) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
            }
            throw failure;
        }
    }

    private static byte[] hash(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private enum Kind {
        PLAYER("Players", "players", "Player"), TEAM("Teams", "teams", "Team"),
        DOWNLOAD("Downloads", "downloads", "Id"), RECEIPT("DownloadReceipts", "receipts", "Id"),
        IMPORT("DiskImports", "imports", "Operation");
        private static final Kind[] VALUES = values();
        private final String tag;
        private final String directory;
        private final String identity;

        Kind(String tag, String directory, String identity) {
            this.tag = tag;
            this.directory = directory;
            this.identity = identity;
        }

        private String identity(NBTTagCompound data) throws IOException {
            if (this == IMPORT) {
                require(data, "commit", 10);
                data = data.getCompoundTag("commit");
            }
            return validate(string(data, identity));
        }

        private String validate(String id) throws IOException {
            if (this == TEAM) {
                if (id.isBlank() || !id.equals(id.trim())) throw new IOException("Invalid research team identity");
            } else uuid(id);
            return id;
        }
    }

    private record Key(Kind kind, String id) {
    }

    private record SavedShard(NBTTagCompound data, byte[] hash) {
    }

    private record IndexEntry(Key key, byte[] hash) {
    }

    private record Meta(UUID commit, long serverTime, ObjectList<IndexEntry> entries) {
    }

    private record State(Meta meta, Object2ObjectLinkedOpenHashMap<Key, SavedShard> shards) {
    }

    private record Pending(@Nullable UUID base, Meta meta, Object2ObjectLinkedOpenHashMap<Key, byte[]> changed,
                           ObjectList<Key> removed) {
    }
}
