package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.StorageContext;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;

/**
 * Version facts from the original fence capture, used only to avoid unchanged capacity retries.
 * The supplied versions list must remain unchanged for this result's lifetime.
 */
public record HatchReplacement(HatchReconfigurationStatus status, List<Version> versions) {
    public static HatchReplacement of(HatchReconfigurationStatus status) {
        return new HatchReplacement(status, List.of());
    }

    public static List<Version> captureVersions(List<StorageContext> contexts) {
        var result = new ObjectArrayList<Version>(contexts.size());
        for (int index2 = 0; index2 < contexts.size(); index2++) {
            var context = contexts.get(index2);
            result.add(new Version(context, context.revision()));
        }
        return result;
    }

    public static boolean current(List<Version> versions) {
        for (int index = 0; index < versions.size(); index++) {
            var version = versions.get(index);
            if (!version.current()) return false;
        }
        return true;
    }

    public boolean unchanged() {
        return !versions.isEmpty() && current(versions);
    }

    public record Version(StorageContext context, long revision) {
        public boolean current() {
            return context.current(revision);
        }
    }
}
