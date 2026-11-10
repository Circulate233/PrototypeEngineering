package com.circulation.prototype_eng.recipe.requirement;

import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLongArray;
import net.minecraft.nbt.NBTTagString;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.IntToLongFunction;

/**
 * Exact legacy canonical wire bytes, yielding before every node/name/byte and array element.
 */
class CanonicalKeyEncoding {
    private final ObjectArrayList<Part> parts = new ObjectArrayList<>();
    private final ByteArrayList emitted = new ByteArrayList();
    private byte @Nullable [] result;
    private int copied;
    private int allocationCredit;

    CanonicalKeyEncoding(NBTTagCompound tag) {
        parts.add(new Node(Objects.requireNonNull(tag)));
    }

    boolean advance(RecipeWorkBudget budget) {
        while (!parts.isEmpty()) {
            int frame = parts.size() - 1;
            var part = parts.get(frame);
            if (!part.advance(budget)) {
                if (parts.size() > frame + 1) {
                    continue;
                }
                return false;
            }
            // A part may have pushed children; remove this exact completed frame, not the child.
            parts.remove(frame);
        }
        if (result == null) {
            while (allocationCredit < emitted.size()) {
                if (!budget.take()) {
                    return false;
                }
                allocationCredit++;
            }
            result = new byte[emitted.size()];
        }
        // Copying final bytes is separately charged before each read/write.
        while (copied < result.length) {
            if (!budget.take()) {
                return false;
            }
            result[copied] = emitted.getByte(copied);
            copied++;
        }
        return true;
    }

    byte[] result() {
        if (result == null || copied != result.length) {
            throw new IllegalStateException("Canonical encoding is incomplete");
        }
        return result;
    }

    private void emit(int value) {
        emitted.add((byte) value);
    }

    private void push(Part part) {
        parts.add(part);
    }

    private Part leaf(NBTBase tag) {
        return switch (tag) {
            case NBTTagByteArray array -> {
                byte[] values = array.getByteArray();
                yield new Array(values.length, 1, index -> values[index]);
            }
            case NBTTagIntArray array -> {
                int[] values = array.getIntArray();
                yield new Array(values.length, 4, index -> values[index]);
            }
            case NBTTagLongArray array -> {
                var values = (NbtLongArrayAccess) array;
                yield new Array(values.prototype_eng$length(), 8, values::prototype_eng$value);
            }
            case NBTPrimitive number -> switch (tag.getId()) {
                case 1 -> new Word(number.getByte(), 1);
                case 2 -> new Word(number.getShort(), 2);
                case 3 -> new Word(number.getInt(), 4);
                case 4 -> new Word(number.getLong(), 8);
                case 5 -> new Word(Float.floatToIntBits(number.getFloat()), 4);
                case 6 -> new Word(Double.doubleToLongBits(number.getDouble()), 8);
                default -> throw new IllegalArgumentException("Unsupported numeric key tag");
            };
            default -> throw new IllegalArgumentException("Unsupported serialized key tag");
        };
    }

    private interface Part {
        boolean advance(RecipeWorkBudget budget);
    }

    private class Word implements Part {
        private final long value;
        private final int count;
        private int cursor;

        private Word(long value, int count) {
            this.value = value;
            this.count = count;
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            while (cursor < count) {
                if (!budget.take()) {
                    return false;
                }
                emit((int) (value >>> (8 * (count - cursor - 1))));
                cursor++;
            }
            return true;
        }
    }

    private class Text implements Part {
        private final String value;
        private final Word length;
        private int cursor;
        private int half;

        private Text(String value) {
            this.value = value;
            length = new Word(value.length(), 4);
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (!length.advance(budget)) {
                return false;
            }
            while (cursor < value.length()) {
                if (!budget.take()) {
                    return false;
                }
                emit(half == 0 ? value.charAt(cursor) >>> 8 : value.charAt(cursor));
                if (++half == 2) {
                    half = 0;
                    cursor++;
                }
            }
            return true;
        }
    }

    private class Array implements Part {
        private final int length;
        private final int elementBytes;
        private final IntToLongFunction element;
        private final Word header;
        private int cursor;
        @Nullable
        private Word current;

        private Array(int length, int elementBytes, IntToLongFunction element) {
            this.length = length;
            this.elementBytes = elementBytes;
            this.element = element;
            header = new Word(length, 4);
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (!header.advance(budget)) {
                return false;
            }
            while (cursor < length) {
                if (current == null) {
                    if (!budget.take()) {
                        return false;
                    }
                    current = new Word(element.applyAsLong(cursor), elementBytes);
                }
                if (!current.advance(budget)) {
                    return false;
                }
                current = null;
                cursor++;
            }
            return true;
        }
    }

    private class Node implements Part {
        private final NBTBase tag;
        private final Word id;
        private boolean expanded;

        private Node(NBTBase tag) {
            this.tag = tag;
            id = new Word(tag.getId(), 1);
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (!id.advance(budget)) {
                return false;
            }
            if (!expanded) {
                if (!budget.take()) {
                    return false;
                }
                expanded = true;
                switch (tag) {
                    case NBTTagCompound compound -> push(new Compound(compound));
                    case NBTTagList list -> push(new Sequence(list));
                    case NBTTagString text -> push(new Text(text.getString()));
                    default -> {
                        // CompressedStreamTools.write({value: leaf}) consists of this fixed root/name
                        // header, the exact vanilla leaf body and the final compound terminator.
                        push(new Word(0, 1));
                        push(leaf(tag));
                        push(new Word(0x000576616c7565L, 7));
                        push(new Word(tag.getId(), 1));
                        push(new Word(0x0a0000, 3));
                    }
                }
            }
            return true;
        }
    }

    private class Compound implements Part {
        private final NBTTagCompound tag;
        private final Iterator<String> names;
        private final ObjectArrayList<String> captured = new ObjectArrayList<>();
        @Nullable
        private PureMergeSort<String> order;
        @Nullable
        private Word length;
        private int cursor;

        private Compound(NBTTagCompound tag) {
            this.tag = tag;
            names = tag.getKeySet().iterator();
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            while (names.hasNext()) {
                if (!budget.take()) {
                    return false;
                }
                captured.add(names.next());
            }
            if (order == null) {
                order = new PureMergeSort<>(captured, PureMergeSort.StringComparison::new);
            }
            if (!order.advance(budget)) {
                return false;
            }
            List<String> ordered = order.result();
            if (length == null) {
                length = new Word(ordered.size(), 4);
            }
            if (!length.advance(budget)) {
                return false;
            }
            if (cursor < ordered.size()) {
                if (!budget.take()) {
                    return false;
                }
                String name = ordered.get(cursor++);
                push(new Node(Objects.requireNonNull(tag.getTag(name))));
                push(new Text(name));
                return false;
            }
            return true;
        }
    }

    private class Sequence implements Part {
        private final NBTTagList tag;
        private final Word type;
        private final Word length;
        private int cursor;

        private Sequence(NBTTagList tag) {
            this.tag = tag;
            type = new Word(tag.getTagType(), 1);
            length = new Word(tag.tagCount(), 4);
        }

        @Override
        public boolean advance(RecipeWorkBudget budget) {
            if (!type.advance(budget) || !length.advance(budget)) {
                return false;
            }
            if (cursor < tag.tagCount()) {
                if (!budget.take()) {
                    return false;
                }
                push(new Node(tag.get(cursor++)));
                return false;
            }
            return true;
        }
    }
}
