package com.circulation.prototype_eng.api.client.jei;

import ae2.api.stacks.GenericStack;
import ae2.core.localization.LocalizationEnum;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Objects;

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
    public record Resource(GenericStack stack, Role role, List<Text> details) {
        public Resource {
            Objects.requireNonNull(stack);
            Objects.requireNonNull(stack.what());
            Objects.requireNonNull(role);
            if (stack.amount() <= 0) {
                throw new IllegalArgumentException("Display quantities must be positive");
            }
            details = ObjectLists.unmodifiable(new ObjectArrayList<>(details));
        }
    }

    public record Text(LocalizationEnum text, List<Object> arguments, List<Text> context) {
        public Text {
            Objects.requireNonNull(text);
            arguments = ObjectLists.unmodifiable(new ObjectArrayList<>(arguments));
            context = ObjectLists.unmodifiable(new ObjectArrayList<>(context));
        }

        public Text(LocalizationEnum text, List<Object> arguments) {
            this(text, arguments, ObjectLists.emptyList());
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
            return new Text(text, arguments, combined);
        }
    }

    /**
     * Scoped builders share destinations while preserving nested checkpoint/alternative meaning.
     */
    public static class Builder {
        private final ObjectArrayList<Resource> resources;
        private final ObjectArrayList<Text> information;
        private final List<Text> scope;

        public Builder() {
            this(new ObjectArrayList<>(), new ObjectArrayList<>(), ObjectLists.emptyList());
        }

        private Builder(ObjectArrayList<Resource> resources, ObjectArrayList<Text> information, List<Text> scope) {
            this.resources = resources;
            this.information = information;
            this.scope = scope;
        }

        public Builder scoped(Text... explanation) {
            var nested = new ObjectArrayList<>(scope);
            nested.addAll(ObjectArrayList.of(explanation));
            return new Builder(resources, information, ObjectLists.unmodifiable(nested));
        }

        public void resource(GenericStack stack, Role role, Text... details) {
            var explanation = new ObjectArrayList<>(scope);
            explanation.addAll(ObjectArrayList.of(details));
            resources.add(new Resource(stack, role, explanation));
        }

        public void information(Text line) {
            // A single line carries its complete scope, even when panel boundaries split information.
            information.add(Objects.requireNonNull(line).scoped(scope));
        }

        public JeiRecipeDisplay build() {
            return new JeiRecipeDisplay(resources, information);
        }
    }
}
