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
        for (var context : contexts) result.add(new Version(context, context.revision()));
        return result;
    }

    public static boolean current(List<Version> versions) {
        for (var version : versions) if (!version.current()) return false;
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
