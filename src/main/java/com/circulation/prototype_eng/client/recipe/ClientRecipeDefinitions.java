package com.circulation.prototype_eng.client.recipe;

import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarations;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.recipe.RecipeDefinitionManifestCodec;
import com.circulation.prototype_eng.network.clientbound.RecipeDefinitionManifestPacket;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-owner desired/applied state. Only an accurate local declaration and confirmed native apply are ready.
 */
@SideOnly(Side.CLIENT)
public final class ClientRecipeDefinitions {
    private final Thread owner = Thread.currentThread();
    private final ClientRecipeDeclarations declarations;
    private final Renderer renderer;
    private @Nullable Object connection;
    private @Nullable UUID session;
    private long generation = -1;
    private boolean poisoned;
    private boolean declarationRequired;
    private boolean localStaticVisible;
    private boolean resolving;
    @SuppressWarnings("FieldCanBeLocal")
    // Renderer callbacks may reenter retry while the current native apply is running.
    private boolean retryRequested;
    private boolean retryQueued;
    private long readinessEpoch;
    private @Nullable Pending pending;
    private @Nullable RecipeDefinitionManifest desired;
    private byte @Nullable [] desiredBytes;
    private @Nullable RecipeDefinitionManifest applied;
    private String reason = "Disconnected";

    public ClientRecipeDefinitions(ClientRecipeDeclarations declarations, Renderer renderer) {
        this.declarations = Objects.requireNonNull(declarations);
        this.renderer = Objects.requireNonNull(renderer);
        declarations.onRegistered(() -> {
            readinessEpoch = Math.addExact(readinessEpoch, 1);
            retry();
        });
    }

    public void connected(Object connection) {
        requireOwner();
        Objects.requireNonNull(connection);
        if (this.connection == connection) return;
        this.connection = connection;
        clear();
        withdraw("Waiting for authoritative recipe generation");
        retry();
    }

    public void disconnected(Object connection) {
        requireOwner();
        if (this.connection != connection) return;
        this.connection = null;
        clear();
        withdraw("Disconnected");
    }

    private void clear() {
        session = null;
        generation = -1;
        poisoned = false;
        declarationRequired = false;
        localStaticVisible = false;
        retryQueued = false;
        pending = null;
        desired = null;
        desiredBytes = null;
        applied = null;
    }

    public boolean connectedTo(Object connection) {
        requireOwner();
        return this.connection != null && this.connection == connection;
    }

    public @Nullable RecipeDefinitionManifest desired() {
        requireOwner();
        return desired;
    }

    public @Nullable RecipeDefinitionManifest applied() {
        requireOwner();
        return applied;
    }

    public String reason() {
        requireOwner();
        return reason;
    }

    public long generation() {
        requireOwner();
        return generation;
    }

    /**
     * A preserved local static display is never a remotely confirmed applied manifest.
     */
    public boolean localStaticVisible() {
        requireOwner();
        return localStaticVisible;
    }

    /**
     * Pinned first server session, highest announced generation, one bounded incomplete manifest.
     */
    public boolean receive(Object connection, RecipeDefinitionManifestPacket packet) {
        requireOwner();
        if (!connectedTo(connection)) return false;
        if (session != null && !session.equals(packet.session()) || packet.generation() < generation) return false;
        if (session == null) session = packet.session();
        if (packet.declarationRequired() && !declarationRequired) {
            declarationRequired = true;
            withdraw("Waiting for complete recipe declaration manifest");
        }
        if (packet.generation() > generation) {
            generation = packet.generation();
            pending = null;
            desired = null;
            desiredBytes = null;
            poisoned = false;
            if (declarationRequired) withdraw("Waiting for complete recipe declaration manifest");
            else retry();
        }
        if (packet.notice() || poisoned) return false;
        byte[] bytes = packet.bytes();
        if (desiredBytes != null) {
            int offset = packet.index() * RecipeDefinitionManifestPacket.CHUNK_BYTES;
            if (packet.totalBytes() != desiredBytes.length
                || !Arrays.equals(bytes, Arrays.copyOfRange(desiredBytes, offset, offset + bytes.length)))
                conflict("Conflicting completed recipe manifest generation");
            return false;
        }
        if (pending == null) pending = new Pending(packet);
        var candidate = pending;
        if (candidate.first.totalBytes() != packet.totalBytes()) conflict("Conflicting recipe manifest chunk headers");
        byte[] previous = candidate.parts[packet.index()];
        if (previous != null) {
            if (!Arrays.equals(previous, bytes)) conflict("Conflicting repeated recipe manifest chunk");
            return false;
        }
        candidate.parts[packet.index()] = bytes;
        candidate.received++;
        if (candidate.received != candidate.parts.length) return false;
        byte[] complete = new byte[candidate.first.totalBytes()];
        int offset = 0;
        for (byte[] part : candidate.parts) {
            System.arraycopy(part, 0, complete, offset, part.length);
            offset += part.length;
        }
        pending = null;
        RecipeDefinitionManifest manifest;
        try {
            manifest = RecipeDefinitionManifestCodec.decode(complete);
            if (!manifest.serverSession().equals(session) || manifest.generation() != generation)
                throw new IllegalArgumentException("Recipe manifest payload differs from chunk session/generation");
        } catch (RuntimeException failure) {
            poisoned = true;
            withdraw("Invalid recipe declaration manifest: " + failure.getMessage());
            throw failure;
        }
        desired = manifest;
        desiredBytes = complete;
        retry();
        return true;
    }

    private void conflict(String reason) {
        poisoned = true;
        pending = null;
        desired = null;
        desiredBytes = null;
        withdraw(reason);
        throw new IllegalArgumentException(reason);
    }

    /**
     * Accurate still-desired declaration for D's initial native category construction/reload.
     */
    public @Nullable ClientRecipeDeclarations.Declaration resolved() {
        requireOwner();
        if (connection == null || poisoned || desired == null) return null;
        var declaration = declarations.get(desired.declaration());
        return declaration != null && desired.matches(declaration.snapshot()) ? declaration : null;
    }

    /**
     * Declaration/runtime late arrival retries only the current desired manifest.
     */
    public void retry() {
        requireOwner();
        if (resolving) {
            retryRequested = true;
            return;
        }
        resolving = true;
        var capturedDesired = desired;
        var capturedConnection = connection;
        long capturedGeneration = generation;
        long capturedReadiness = readinessEpoch;
        try {
            retryRequested = false;
            resolveCurrent();
        } finally {
            resolving = false;
        }
        // Reentrant callbacks alone are not evidence that the same tuple needs another inline apply.
        if (retryRequested && !retryQueued && (desired != capturedDesired || connection != capturedConnection
            || generation != capturedGeneration || readinessEpoch != capturedReadiness)) {
            retryQueued = true;
        }
    }

    /**
     * Consume only the pending retry present at entry. Reentrant changes wait for the next owner tick.
     */
    public void tick() {
        requireOwner();
        if (resolving || !retryQueued) return;
        retryQueued = false;
        retry();
    }

    private void resolveCurrent() {
        var captured = desired;
        if (poisoned) return;
        if (captured == null) {
            if (!declarationRequired && (connection == null || session != null && generation == 0)) {
                var capturedConnection = connection;
                long capturedReadiness = readinessEpoch;
                long capturedGeneration = generation;
                applied = null;
                localStaticVisible = false;
                ApplyResult result;
                try {
                    result = Objects.requireNonNull(renderer.showLocalStatic());
                } catch (RuntimeException | Error failure) {
                    try {
                        withdraw("Local static display application failed: " + failure.getMessage());
                    } catch (RuntimeException | Error cleanup) {
                        if (cleanup != failure) failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
                if (declarationRequired || connection != capturedConnection || generation != capturedGeneration
                    || readinessEpoch != capturedReadiness) {
                    withdraw("Local static display application became obsolete");
                    return;
                }
                applied = null;
                localStaticVisible = result.ready();
                reason = result.ready() ? "Local static display; remote declaration not verified" : result.reason();
                if (!result.ready()) renderer.withdraw(reason);
            }
            return;
        }
        if (connection == null) return;
        var local = declarations.get(captured.declaration());
        if (local == null) {
            withdraw("Missing local recipe declaration: " + captured.declaration());
            return;
        }
        if (!captured.matches(local.snapshot())) {
            withdraw("Local recipe declaration pool/member/revision mismatch: " + captured.declaration());
            return;
        }
        // Native availability may have changed without changing the tuple, so each retry revalidates it.
        applied = null;
        var capturedConnection = connection;
        long capturedReadiness = readinessEpoch;
        ApplyResult result;
        try {
            result = Objects.requireNonNull(renderer.apply(local));
        } catch (RuntimeException | Error failure) {
            try {
                withdraw("Recipe display application failed: " + failure.getMessage());
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        if (connection != capturedConnection || desired != captured || poisoned || readinessEpoch != capturedReadiness) {
            withdraw("Recipe display application became obsolete");
            return;
        }
        if (result.ready()) {
            localStaticVisible = false;
            applied = captured;
            reason = "Ready";
        } else withdraw(result.reason());
    }

    public void resourcesReloaded() {
        requireOwner();
        readinessEpoch = Math.addExact(readinessEpoch, 1);
        withdraw("Recipe display resources reloading");
        retry();
    }

    public void runtimeReady() {
        requireOwner();
        readinessEpoch = Math.addExact(readinessEpoch, 1);
        retry();
    }

    public void runtimeUnavailable() {
        requireOwner();
        readinessEpoch = Math.addExact(readinessEpoch, 1);
        withdraw("HEI runtime is rebuilding");
    }

    private void withdraw(String reason) {
        applied = null;
        localStaticVisible = false;
        this.reason = Objects.requireNonNull(reason);
        renderer.withdraw(reason);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Client recipe definition state requires its owner");
    }

    public interface Renderer {
        ApplyResult apply(ClientRecipeDeclarations.Declaration declaration);

        void withdraw(String reason);

        default ApplyResult showLocalStatic() {
            return new ApplyResult(false, "Local static display is unavailable");
        }
    }

    public record ApplyResult(boolean ready, String reason) {
        public ApplyResult {
            Objects.requireNonNull(reason);
        }
    }

    private static final class Pending {
        private final RecipeDefinitionManifestPacket first;
        private final byte[][] parts;
        private int received;

        private Pending(RecipeDefinitionManifestPacket first) {
            this.first = first;
            parts = new byte[first.chunks()][];
        }
    }
}
