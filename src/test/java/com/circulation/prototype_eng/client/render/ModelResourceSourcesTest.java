package com.circulation.prototype_eng.client.render;

import net.minecraft.client.resources.FileResourcePack;
import net.minecraft.client.resources.FolderResourcePack;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModelResourceSourcesTest {
    private static final ResourceLocation LOCATION = new ResourceLocation("source_test", "geo/test.json");
    @TempDir
    Path root;

    private FileResourcePack zip(String directory, String contents) throws IOException {
        var path = root.resolve(directory).resolve("same.zip");
        Files.createDirectories(path.getParent());
        try (var output = new ZipOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("assets/source_test/geo/test.json"));
            output.write(contents.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new FileResourcePack(path.toFile());
    }

    @Test
    void independentZipReaderKeepsOriginalSelectedBytesAfterNativePackCloseAndNewSelection() throws Exception {
        var original = zip("old", "original");
        var successor = zip("new", "successor");
        var selected = ModelResourceSources.capturePacks(List.of(original), LOCATION);
        original.close();
        var result = new CompletableFuture<String>();
        var worker = Thread.ofPlatform().start(() -> {
            try (selected) {
                result.complete(new String(selected.readAllBytes(), StandardCharsets.UTF_8));
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals("original", result.get());
        try (var input = ModelResourceSources.capturePacks(List.of(successor), LOCATION)) {
            assertEquals("successor", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            successor.close();
        }
        Files.delete(original.getResourcePackFile().toPath());
    }

    @SuppressWarnings("TryFinallyCanBeTryWithResources")
    // Native pack close deliberately precedes the independent reader's exit.
    @Test
    void samePackNameStillUsesTheExactHighestPriorityPackObject() throws Exception {
        var lower = zip("lower", "lower");
        var higher = zip("higher", "higher");
        assertEquals(lower.getPackName(), higher.getPackName());
        try (var selected = ModelResourceSources.capturePacks(List.of(lower, higher), LOCATION)) {
            lower.close();
            higher.close();
            assertEquals("higher", new String(selected.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            lower.close();
            higher.close();
        }
    }

    @Test
    void overriddenNativePackFreezesItsCustomStreamOnOwnerInsteadOfBypassingTheOverride() throws Exception {
        class CustomFolder extends FolderResourcePack {
            byte[] bytes = "custom".getBytes(StandardCharsets.UTF_8);
            int closes;

            CustomFolder() {
                super(root.toFile());
            }

            @Override
            public boolean resourceExists(@NotNull ResourceLocation location) {
                return true;
            }

            @SuppressWarnings("ReturnOfInnerClass") // The overridden stream records close on this exact custom pack.
            @Override
            public @NotNull InputStream getInputStream(@NotNull ResourceLocation location) {
                return new ByteArrayInputStream(bytes) {
                    @Override
                    public void close() {
                        closes++;
                    }
                };
            }
        }
        var pack = new CustomFolder();
        try (var selected = ModelResourceSources.capturePacks(List.of(pack), LOCATION)) {
            assertEquals(1, pack.closes);
            pack.bytes = "changed".getBytes(StandardCharsets.UTF_8);
            assertEquals("custom", new String(selected.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void nativeFolderKeepsItsValidationAndIndependentOpenedStream() throws Exception {
        var path = root.resolve("assets/source_test/geo/test.json");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "folder");
        var pack = new FolderResourcePack(root.toFile());
        try (var selected = ModelResourceSources.capturePacks(List.of(pack), LOCATION)) {
            assertEquals("folder", new String(selected.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThrows(IOException.class, () -> {
            try (var unexpected = ModelResourceSources.capturePacks(List.of(pack), new ResourceLocation("source_test", "geo/../test.json"))) {
                fail("Invalid path opened a native stream: " + unexpected);
            }
        });
    }

    @Test
    void failedBatchCaptureDoesNotPublishEarlierSourcesAndOriginalFailureKeepsCleanupFailures() throws Exception {
        var resources = BModelFixtures.resources("{}");
        var client = new ModelClientThread(Thread.currentThread(), Runnable::run);
        try (var assets = new ModelAssets(client, resources, root)) {
            var missing = new ResourceLocation("source_test", "missing");
            assertThrows(IOException.class, () -> assets.select(List.of(BModelFixtures.GEO, missing)));
            assertEquals(1, resources.closes);
            assertThrows(IOException.class, () -> assets.getResource(BModelFixtures.GEO));
            assets.select(List.of(BModelFixtures.GEO));
            int selected = resources.selections;
            assets.select(List.of(BModelFixtures.GEO, BModelFixtures.GEO));
            assertEquals(selected, resources.selections);
        }
    }

    @Test
    void customPackReadFailurePreservesTheOriginalIOExceptionAndCloseError() {
        var original = new IOException("custom read");
        var cleanup = new AssertionError("custom close");
        class FailingFolder extends FolderResourcePack {
            FailingFolder() {
                super(root.toFile());
            }

            @Override
            public boolean resourceExists(@NotNull ResourceLocation location) {
                return true;
            }

            @SuppressWarnings("ReturnOfInnerClass") // This fixture stream carries the exact read and close failures.
            @Override
            public @NotNull InputStream getInputStream(@NotNull ResourceLocation location) {
                return new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw original;
                    }

                    @Override
                    public void close() {
                        throw cleanup;
                    }
                };
            }
        }
        assertSame(original, assertThrows(IOException.class, () -> {
            try (var unexpected = ModelResourceSources.capturePacks(List.of(new FailingFolder()), LOCATION)) {
                fail("Failing source opened a snapshot: " + unexpected);
            }
        }));
        assertArrayEquals(new Throwable[]{cleanup}, original.getSuppressed());
    }
}
