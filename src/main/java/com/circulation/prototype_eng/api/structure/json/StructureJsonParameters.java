package com.circulation.prototype_eng.api.structure.json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable parameter view with typed reads and source paths; JSON copies never expose backing data.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class StructureJsonParameters {
    private final String source;
    private final String path;
    private final JsonElement value;

    private StructureJsonParameters(String source, String path, JsonElement value) {
        this.source = source;
        this.path = path;
        this.value = value;
    }

    /**
     * The caller retains ownership of the supplied reader.
     */
    @SuppressWarnings("deprecation") // setLenient is retained for the bundled Gson 2.2.4 API.
    public static StructureJsonParameters read(String source, Reader input) {
        Objects.requireNonNull(source);
        var reader = new JsonReader(new LiteralCheckedReader(Objects.requireNonNull(input)));
        reader.setLenient(false);
        try {
            var value = readValue(reader, source, "$", 0);
            if (reader.peek() != JsonToken.END_DOCUMENT)
                throw new StructureJsonException(source, "$", "Trailing JSON data");
            var result = new StructureJsonParameters(source, "$", value);
            result.object();
            return result;
        } catch (IOException | NumberFormatException failure) {
            throw new StructureJsonException(source, "$", "Cannot read strict JSON: " + failure.getMessage(), failure);
        }
    }

    private static JsonElement readValue(JsonReader reader, String source, String path, int depth) {
        if (depth > 256) throw new StructureJsonException(source, path, "JSON nesting is too deep");
        try {
            return switch (reader.peek()) {
                case BEGIN_OBJECT -> {
                    reader.beginObject();
                    var result = new JsonObject();
                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        String child = childPath(path, name);
                        if (result.has(name)) throw new StructureJsonException(source, child, "Duplicate JSON member");
                        result.add(name, readValue(reader, source, child, depth + 1));
                    }
                    reader.endObject();
                    yield result;
                }
                case BEGIN_ARRAY -> {
                    reader.beginArray();
                    var result = new JsonArray();
                    while (reader.hasNext())
                        result.add(readValue(reader, source, path + "[" + result.size() + "]", depth + 1));
                    reader.endArray();
                    yield result;
                }
                case STRING -> new JsonPrimitive(reader.nextString());
                case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
                case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
                case NULL -> {
                    reader.nextNull();
                    yield JsonNull.INSTANCE;
                }
                default -> throw new StructureJsonException(source, path, "Expected JSON value");
            };
        } catch (IOException | NumberFormatException failure) {
            throw new StructureJsonException(source, path, "Cannot read strict JSON: " + failure.getMessage(), failure);
        }
    }

    private static String childPath(String parent, String field) {
        return field.matches("[A-Za-z_][A-Za-z0-9_]*") ? parent + "." + field
            : parent + "['" + field.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private static JsonElement copyValue(JsonElement value) {
        if (value.isJsonNull()) return JsonNull.INSTANCE;
        if (value.isJsonArray()) {
            var copy = new JsonArray();
            for (var entry : value.getAsJsonArray()) copy.add(copyValue(entry));
            return copy;
        }
        if (value.isJsonObject()) {
            var copy = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(entry -> copy.add(entry.getKey(), copyValue(entry.getValue())));
            return copy;
        }
        // Parsed primitives contain immutable String/Boolean/BigDecimal values; only arrays/objects mutate.
        return value;
    }

    private JsonObject object() {
        if (!value.isJsonObject()) throw error("Expected an object");
        return value.getAsJsonObject();
    }

    public void fields(String... allowed) {
        var names = Set.of(allowed);
        for (var field : object().entrySet()) {
            if (!names.contains(field.getKey())) throw at(field.getKey()).error("Unknown field");
            if (field.getValue().isJsonNull()) throw at(field.getKey()).error("Null fields are not allowed");
        }
    }

    public boolean has(String field) {
        return object().has(field);
    }

    public StructureJsonParameters at(String field) {
        var child = object().get(field);
        String childPath = childPath(path, field);
        if (child == null || child.isJsonNull())
            throw new StructureJsonException(source, childPath, "Required field is missing or null");
        return new StructureJsonParameters(source, childPath, child);
    }

    public StructureJsonParameters parameters(String field) {
        if (!has(field)) return new StructureJsonParameters(source, childPath(path, field), new JsonObject());
        var child = at(field);
        child.object();
        return child;
    }

    public List<StructureJsonParameters> array(String field) {
        var child = at(field);
        if (!child.value.isJsonArray()) throw child.error("Expected an array");
        var result = new ObjectArrayList<StructureJsonParameters>();
        var entries = child.value.getAsJsonArray();
        for (int i = 0; i < entries.size(); i++)
            result.add(new StructureJsonParameters(source, child.path + "[" + i + "]", entries.get(i)));
        return List.copyOf(result);
    }

    public String string() {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isString()) throw error("Expected a string");
        return primitive.getAsString();
    }

    public String string(String field) {
        return at(field).string();
    }

    public String nonBlank(String field) {
        String text = string(field);
        if (text.isBlank()) throw at(field).error("Expected a nonempty string");
        return text;
    }

    public int integer() {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()) throw error("Expected an integer");
        var number = new BigDecimal(primitive.getAsString());
        if (number.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0 || number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0)
            throw error("Integer is outside the signed 32-bit range");
        if (number.stripTrailingZeros().scale() > 0) throw error("Expected an exact integer value");
        return number.intValue();
    }

    public int integer(String field, int fallback) {
        return has(field) ? at(field).integer() : fallback;
    }

    public int meta() {
        int meta = integer("meta", 0);
        if (meta < 0 || meta > 15) throw at("meta").error("Block metadata must be in 0..15");
        return meta;
    }

    public boolean bool(String field, boolean fallback) {
        if (!has(field)) return fallback;
        var child = at(field);
        if (!(child.value instanceof JsonPrimitive primitive) || !primitive.isBoolean())
            throw child.error("Expected a boolean");
        return primitive.getAsBoolean();
    }

    public ResourceLocation id() {
        String id = string();
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw error("Expected a fully qualified resource ID");
        return new ResourceLocation(id);
    }

    public ResourceLocation id(String field) {
        return at(field).id();
    }

    public BlockPos position(String field, BlockPos fallback) {
        if (!has(field)) return fallback;
        var position = at(field);
        position.fields("x", "y", "z");
        return new BlockPos(position.at("x").integer(), position.at("y").integer(), position.at("z").integer());
    }

    /**
     * PM shallow NBT constraints: callers provide strings matching bare String tags or typed SNBT.
     */
    public Map<String, String> stringMap(String field) {
        if (!has(field)) return Map.of();
        var map = at(field);
        var result = new Object2ObjectLinkedOpenHashMap<String, String>();
        for (var entry : map.object().entrySet()) {
            if (entry.getKey().isEmpty()) throw map.at(entry.getKey()).error("Empty NBT field name");
            result.put(entry.getKey(), map.at(entry.getKey()).string());
        }
        return Object2ObjectMaps.unmodifiable(result);
    }

    /**
     * Arbitrary addon parameters remain available as detached copies, never mutable backing JSON.
     */
    public JsonElement copy(String field) {
        return copyValue(at(field).value);
    }

    public String source() {
        return source;
    }

    public String path() {
        return path;
    }

    public StructureJsonException error(String message) {
        return new StructureJsonException(source, path, message);
    }

    public StructureJsonException error(String message, Throwable cause) {
        return new StructureJsonException(source, path, message, cause);
    }

    /**
     * Gson 2.2.4 accepts uppercase literals and some invalid string escapes even with lenient=false.
     */
    private static final class LiteralCheckedReader extends Reader {
        private final Reader input;
        private final StringBuilder token = new StringBuilder();
        private boolean string;
        private boolean escape;
        private int unicode;
        private int line = 1;
        private int column;

        private LiteralCheckedReader(Reader input) {
            this.input = input;
        }

        @Override
        public int read(char @NotNull [] buffer, int offset, int length) throws IOException {
            int count = input.read(buffer, offset, length);
            if (count == -1) finishToken();
            for (int i = offset; i < offset + count; i++) {
                char c = buffer[i];
                column++;
                if (string) {
                    if (unicode != 0) {
                        if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'))
                            throw invalid("Invalid Unicode escape");
                        unicode--;
                    } else if (escape) {
                        if (c == 'u') unicode = 4;
                        else if ("\"\\/bfnrt".indexOf(c) < 0) throw invalid("Invalid JSON string escape");
                        escape = false;
                    } else if (c < 0x20) throw invalid("Unescaped JSON string control character");
                    else if (c == '\\') escape = true;
                    else if (c == '"') string = false;
                } else if (c == '"') {
                    finishToken();
                    string = true;
                } else if ("{}[],: \t\r\n".indexOf(c) >= 0) finishToken();
                else token.append(c);
                if (c == '\n') {
                    line++;
                    column = 0;
                }
            }
            return count;
        }

        private IOException invalid(String message) {
            return new IOException(message + " at line " + line + " column " + column);
        }

        private void finishToken() throws IOException {
            String literal = token.toString();
            token.setLength(0);

            var elements = List.of("true", "false", "null");
            for (int index = 0; index < elements.size(); index++) {
                String keyword = elements.get(index);
                if (keyword.equalsIgnoreCase(literal) && !keyword.equals(literal))
                    throw invalid("JSON literal must be lowercase");
            }

        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
