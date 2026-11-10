package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.api.storage.HatchReconfigurationStatus;
import com.circulation.prototype_eng.api.storage.StorageContext;

import java.util.List;

/** Version facts from the original fence capture, used only to avoid unchanged capacity retries. */
public record HatchReplacement(HatchReconfigurationStatus status, List<Version> versions) {
    public HatchReplacement {
        versions = List.copyOf(versions);
    }

    public static HatchReplacement of(HatchReconfigurationStatus status) {
        return new HatchReplacement(status, List.of());
    }

    public boolean unchanged() {
        return !versions.isEmpty() && versions.stream().allMatch(Version::current);
    }

    public record Version(StorageContext context, long revision) {
        public boolean current() {
            return context.current(revision);
        }
    }
}
