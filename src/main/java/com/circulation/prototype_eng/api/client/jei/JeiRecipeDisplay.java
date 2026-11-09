package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import ae2.core.localization.LocalizationEnum;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable declared display data. No machine, process, matcher or chance callback is evaluated.
 */
@SideOnly(Side.CLIENT)
public record JeiRecipeDisplay(List<Resource> resources, List<Text> information) {
    public JeiRecipeDisplay {
        resources = ObjectLists.unmodifiable(new ObjectArrayList<>(resources));
        information = ObjectLists.unmodifiable(new ObjectArrayList<>(information));
    }

    public enum Role {INPUT, OUTPUT}

    /**
     * One candidate icon; alternatives retain their shared selection explanation in details.
     */
    public record Resource(GenericStack stack, Role role, List<Text> details, @Nullable Chance chance,
                           @Nullable CandidateGroup candidates) {
        public Resource {
            Objects.requireNonNull(stack);
            Objects.requireNonNull(stack.what());
            Objects.requireNonNull(role);
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Display quantities must be positive");
            }
            details = ObjectLists.unmodifiable(new ObjectArrayList<>(details));
        }

        public Resource(GenericStack stack, Role role, List<Text> details) {
            this(stack, role, details, null, null);
        }
    }

    /** Pure declaration; attribute-driven chance is marked rather than evaluated. */
    public record Chance(double percent, boolean attributeDriven) {
        public Chance {
            if (!Double.isFinite(percent)) throw new IllegalArgumentException("Non-finite display chance");
        }
    }

    /** Identity scopes candidate rows; equal names from unrelated declarations never merge. */
    public record CandidateGroup(String name, int alternativeSlots) {
        public CandidateGroup {
            Objects.requireNonNull(name);
            if (alternativeSlots <= 0) throw new IllegalArgumentException("Non-positive candidate slot count");
        }

        public CandidateGroup(String name) { this(name, 1); }
    }

    public record Energy(long amount, Role role) {
        public Energy {
            Objects.requireNonNull(role);
            if (amount <= 0) throw new IllegalArgumentException("Non-positive display energy");
        }
    }

    public record Text(LocalizationEnum text, List<Object> arguments, List<Text> context, @Nullable Energy energy) {
        public Text {
            Objects.requireNonNull(text);
            arguments = ObjectLists.unmodifiable(new ObjectArrayList<>(arguments));
            context = ObjectLists.unmodifiable(new ObjectArrayList<>(context));
        }

        public Text(LocalizationEnum text, List<Object> arguments) {
            this(text, arguments, ObjectLists.emptyList(), null);
        }

        @SuppressWarnings("unused") // Retains the existing external scoped-text constructor.
        public Text(LocalizationEnum text, List<Object> arguments, List<Text> context) {
            this(text, arguments, context, null);
        }

        public static Text of(LocalizationEnum text, Object... arguments) {
            return new Text(text, ObjectArrayList.of(arguments));
        }

        public String local() {
            var result = new StringBuilder();
            for (var explanation : context) {
                result.append(explanation.local()).append(" · ");
            }
            return result.append(text.getLocal(arguments.toArray())).toString();
        }

        public Text scoped(List<Text> scope) {
            var combined = new ObjectArrayList<>(scope);
            combined.addAll(context);
            return new Text(text, arguments, combined, energy);
        }
    }

    /**
     * Scoped builders share destinations while preserving nested checkpoint/alternative meaning.
     */
    public static class Builder {
        private final ObjectArrayList<Resource> resources;
        private final ObjectArrayList<Text> information;
        private final List<Text> scope;
        @Nullable private final Chance chance;
        @Nullable private final CandidateGroup candidates;

        public Builder() {
            this(new ObjectArrayList<>(), new ObjectArrayList<>(), ObjectLists.emptyList(), null, null);
        }

        private Builder(ObjectArrayList<Resource> resources, ObjectArrayList<Text> information, List<Text> scope,
                        @Nullable Chance chance, @Nullable CandidateGroup candidates) {
            this.resources = resources;
            this.information = information;
            this.scope = scope;
            this.chance = chance;
            this.candidates = candidates;
        }

        public Builder scoped(Text... explanation) {
            var nested = new ObjectArrayList<>(scope);
            nested.addAll(ObjectArrayList.of(explanation));
            return new Builder(resources, information, ObjectLists.unmodifiable(nested), chance, candidates);
        }

        public Builder chance(double percent, boolean attributeDriven) {
            return new Builder(resources, information, scope, new Chance(percent, attributeDriven), candidates);
        }

        public Builder candidates(CandidateGroup group) {
            return new Builder(resources, information, scope, chance, Objects.requireNonNull(group));
        }

        public void resource(GenericStack stack, Role role, Text... details) {
            var explanation = new ObjectArrayList<>(scope);
            explanation.addAll(ObjectArrayList.of(details));
            resources.add(new Resource(stack, role, explanation, chance, candidates));
        }

        public void information(Text line) {
            // A single line carries its complete scope, even when panel boundaries split information.
            information.add(Objects.requireNonNull(line).scoped(scope));
        }

        public void energy(long amount, Role role, Text line) {
            information(new Text(line.text(), line.arguments(), line.context(), new Energy(amount, role)));
        }

        public JeiRecipeDisplay build() {
            return new JeiRecipeDisplay(resources, information);
        }
    }
}
