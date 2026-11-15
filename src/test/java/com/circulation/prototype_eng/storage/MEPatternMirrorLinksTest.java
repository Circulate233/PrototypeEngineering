package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MEPatternMirrorLinksTest {
    private static final KeyType TYPE = new KeyType("pattern_mirror_links");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(id -> id.equals(TYPE.getId()) ? TYPE : null);

    private static MEPatternAssemblyStorage storage(long amount) {
        var storage = new MEPatternAssemblyStorage(1, () -> AEColor.RED, () -> {
        }, KEYS);
        var input = storage.input();
        assertNotNull(input);
        assertEquals(amount, input.insert(MATERIAL, amount, Actionable.MODULATE));
        return storage;
    }

    private static long stock(MEPatternAssemblyStorage storage) {
        var input = storage.input();
        assertNotNull(input);
        return input.amount(MATERIAL);
    }

    @Test
    void aWaitingMirrorConnectsAfterLoadAndFollowsTheReplacementAtItsBoundLocation() {
        var links = new MEPatternMirrorLinks();
        var mirror = new Mirror();
        try (var _ = links.subscribe(0, 4, 128, mirror::changed)) {
            assertNull(mirror.current);
            var source = storage(6);
            try (var publication = links.publish(4, 128, source)) {
                assertEquals(6, mirror.stock());
                var original = mirror.current;
                assertNotNull(original);
                publication.close();

                assertNull(mirror.current);
                assertFalse(original.isValid());
                assertTrue(source.isValid());
                var replacement = storage(11);
                try (var _ = links.publish(4, 128, replacement)) {
                    assertEquals(11, mirror.stock());
                    assertEquals(6, stock(source));
                    assertFalse(original.isValid());
                }
            }
        }
    }

    @Test
    void closingTheOldPublicationCannotDisconnectANewerSourceOrItsStorageReplacement() {
        var links = new MEPatternMirrorLinks();
        var mirror = new Mirror();
        var first = storage(3);
        try (var older = links.publish(2, 256, first);
             var _ = links.subscribe(0, 2, 256, mirror::changed)) {
            var original = mirror.current;
            assertNotNull(original);
            var second = storage(8);
            try (var newer = links.publish(2, 256, second)) {
                older.close();
                assertEquals(8, mirror.stock());
                assertFalse(original.isValid());
                var beforeReplacement = mirror.current;
                assertNotNull(beforeReplacement);

                var replacement = storage(12);
                newer.replace(replacement);
                assertEquals(12, mirror.stock());
                assertFalse(beforeReplacement.isValid());
                assertEquals(8, stock(second));
            }
            assertNull(mirror.current);
        }
    }

    @Test
    void unloadingADimensionRevokesItsSourcesAndMirrorsWithoutRemovingOtherSources() {
        var links = new MEPatternMirrorLinks();
        var remoteSource = new Mirror();
        var unloadingMirror = new Mirror();
        var survivingMirror = new Mirror();
        var first = storage(5);
        var other = storage(9);
        try (var publication = links.publish(7, 512, first);
             var _ = links.publish(9, 1024, other);
             var _ = links.subscribe(0, 7, 512, remoteSource::changed);
             var unloadingSubscription = links.subscribe(7, 9, 1024, unloadingMirror::changed);
             var _ = links.subscribe(0, 9, 1024, survivingMirror::changed)) {
            assertEquals(5, remoteSource.stock());
            assertEquals(9, unloadingMirror.stock());
            assertEquals(9, survivingMirror.stock());
            links.unloadDimension(7);

            assertNull(remoteSource.current);
            assertNull(unloadingMirror.current);
            assertEquals(9, survivingMirror.stock());
            publication.close();
            unloadingSubscription.close();
            var replacement = storage(14);
            try (var _ = links.publish(7, 512, replacement);
                 var _ = links.subscribe(7, 9, 1024, unloadingMirror::changed)) {
                assertEquals(14, remoteSource.stock());
                assertEquals(9, unloadingMirror.stock());
                assertEquals(9, survivingMirror.stock());
            }
        }
    }

    private static final class Mirror {
        private @Nullable MEPatternMirrorHatch current;

        private void changed(@Nullable MEPatternAssemblyStorage storage) {
            if (current != null) current.revoke();
            current = storage == null ? null : new MEPatternMirrorHatch(storage);
        }

        private long stock() {
            var mirror = current;
            assertNotNull(mirror);
            var input = mirror.input();
            assertNotNull(input);
            return input.amount(MATERIAL);
        }
    }
}
