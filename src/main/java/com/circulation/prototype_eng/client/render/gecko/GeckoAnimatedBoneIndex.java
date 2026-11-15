package com.circulation.prototype_eng.client.render.gecko;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;

import java.io.Reader;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable animation-file bone index, owned by the exact resource epoch. Malformed files fail explicitly.
 */
public final class GeckoAnimatedBoneIndex {
    private final Map<String, Set<String>> bones;
    private final Set<String> all;
    private final List<String> fallbackNames;

    private GeckoAnimatedBoneIndex(Map<String, Set<String>> bones) {
        this.bones = bones;
        var union = new ObjectLinkedOpenHashSet<String>();
        bones.values().forEach(union::addAll);
        all = union;
        String first = null;
        for (var name : bones.keySet()) if (first == null || name.compareTo(first) < 0) first = name;
        fallbackNames = first == null ? List.of() : List.of(first);
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
            out.put(animation.getKey(), names);
        }
        return new GeckoAnimatedBoneIndex(out);
    }

    public Set<String> allAnimatedBones() {
        return all;
    }

    public List<String> pickAnimationNames(List<String> preferred) {
        ObjectArrayList<String> selected = null;
        for (int index2 = 0; index2 < preferred.size(); index2++) {
            var value = preferred.get(index2);
            var name = value.trim();
            boolean accepted = bones.containsKey(name);
            if (selected == null && (!accepted || !name.equals(value))) {
                selected = new ObjectArrayList<>(preferred.size());
                for (int index = 0; index < index2; index++) selected.add(preferred.get(index));
            }
            if (selected != null && accepted) selected.add(name);
        }
        if (selected == null) return preferred.isEmpty() ? fallbackNames : preferred;
        return selected.isEmpty() ? fallbackNames : selected;
    }

    public Set<String> animatedBonesFor(List<String> preferred) {
        var elements = pickAnimationNames(preferred);
        if (elements.isEmpty()) return Set.of();
        if (elements.size() == 1) return bones.get(elements.getFirst());
        var out = new ObjectLinkedOpenHashSet<String>();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var name = elements.get(index1);
            out.addAll(bones.get(name));
        }
        return out;
    }
}
