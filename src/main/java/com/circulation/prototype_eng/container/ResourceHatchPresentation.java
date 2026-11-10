package com.circulation.prototype_eng.container;

/** Input identities come from a completed frame, never directly from a newly received mapping. */
public final class ResourceHatchPresentation {
    private long layoutRevision;
    private long presentedRevision;
    private ResourceHatchWindowSnapshot received = ResourceHatchWindowSnapshot.EMPTY;

    public ResourceHatchWindowSnapshot receive(ResourceHatchWindowSnapshot snapshot) {
        if (snapshot.revision() >= received.revision()) received = snapshot;
        return received;
    }

    public void beginLayout(long revision) {
        layoutRevision = revision;
    }

    public void completeFrame() {
        presentedRevision = layoutRevision;
    }

    public long gestureRevision() {
        return presentedRevision;
    }

    public boolean accepts(long receivedRevision, long gestureRevision) {
        return receivedRevision > 0 && receivedRevision == presentedRevision && gestureRevision == presentedRevision;
    }
}
