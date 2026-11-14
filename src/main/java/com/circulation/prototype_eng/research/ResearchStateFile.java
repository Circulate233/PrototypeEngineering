package com.circulation.prototype_eng.research;

import org.jetbrains.annotations.Nullable;

import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Sole owner of the research file; compressed data uses PN's sync, atomic replace and final force.
 */
@SuppressWarnings("unused") // Server persistence calls this independent file owner.
public final class ResearchStateFile {
    private final Path target;
    private final ResearchWorldNbt codec;

    public ResearchStateFile(Path target, ResearchWorldNbt codec) {
        this.target = Objects.requireNonNull(target).toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec);
    }

    /**
     * Only a genuinely absent path starts an empty world; unreadable/corrupt files fail visibly.
     */
    public @Nullable ResearchWorldSnapshot read() throws IOException {
        if (Files.notExists(target)) {
            return null;
        }
        if (!Files.readAttributes(target, BasicFileAttributes.class).isRegularFile()) {
            throw new IOException("Research state is not a regular file: " + target);
        }
        if (!Files.isReadable(target)) {
            throw new AccessDeniedException(target.toString());
        }
        try (var input = Files.newInputStream(target)) {
            var root = CompressedStreamTools.readCompressed(input);
            return codec.decode(root.getCompoundTag("data"));
        }
    }

    public void write(ResearchWorldSnapshot snapshot) throws IOException {
        var data = codec.encode(snapshot);
        var parent = target.getParent();
        if (parent == null || !Files.readAttributes(parent, BasicFileAttributes.class).isDirectory()) {
            throw new IOException("Research state parent is not a directory: " + target);
        }
        if (!Files.isWritable(parent)) {
            throw new AccessDeniedException(parent.toString());
        }
        var temporary = Files.createTempFile(parent, "prototype-eng-research-", ".tmp");
        boolean moved = false;
        try {
            try (var output = new FileOutputStream(temporary.toFile())) {
                var root = new NBTTagCompound();
                root.setTag("data", data);
                // Minecraft closes its compressed wrapper; keep the descriptor open until sync finishes.
                CompressedStreamTools.writeCompressed(root, new SyncOutputStream(output));
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
                    if (cleanup != failure) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            throw failure;
        }
    }

    private static final class SyncOutputStream extends FilterOutputStream {
        private SyncOutputStream(FileOutputStream output) {
            super(output);
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
