package com.circulation.prototype_eng.client.render.gecko;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;

import java.io.Reader;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable animation-file bone index, owned by the exact resource epoch. Malformed files fail explicitly. */
public final class GeckoAnimatedBoneIndex {
    private final Map<String, Set<String>> bones;
    private final Set<String> all;
    private final List<String> sortedNames;

    private GeckoAnimatedBoneIndex(Map<String, Set<String>> bones) {
        this.bones = Collections.unmodifiableMap(bones);
        var union = new ObjectLinkedOpenHashSet<String>();
        bones.values().forEach(union::addAll);
        all = Collections.unmodifiableSet(union);
        sortedNames = bones.keySet().stream().sorted().toList();
    }

    @SuppressWarnings("deprecation")
    // The normal test/game runtime retains Gson 2.2.4, whose actual ABI has this parser entry.
    public static GeckoAnimatedBoneIndex read(Reader reader) {
        JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
        var out = new Object2ObjectLinkedOpenHashMap<String, Set<String>>();
        var animations = root.getAsJsonObject("animations");
        if (animations == null) throw new IllegalArgumentException("Animation file has no animations object");
        for (var animation : animations.entrySet()) {
            var names = new ObjectLinkedOpenHashSet<String>();
            var values = animation.getValue().getAsJsonObject().getAsJsonObject("bones");
            if (values != null) for (var bone : values.entrySet()) names.add(bone.getKey());
            // Retain an empty animation for the same deterministic name fallback as the actual loader.
            out.put(animation.getKey(), Collections.unmodifiableSet(names));
        }
        return new GeckoAnimatedBoneIndex(out);
    }

    public Set<String> allAnimatedBones() {
        return all;
    }

    public List<String> pickAnimationNames(List<String> preferred) {
        var selected = preferred.stream().map(String::trim).filter(bones::containsKey).toList();
        return selected.isEmpty() ? sortedNames.stream().limit(1).toList() : selected;
    }

    public Set<String> animatedBonesFor(List<String> preferred) {
        var out = new ObjectLinkedOpenHashSet<String>();
        for (var name : pickAnimationNames(preferred)) out.addAll(bones.get(name));
        return Collections.unmodifiableSet(out);
    }
}
