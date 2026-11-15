package com.circulation.prototype_eng.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionRevision;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Bounded identities-only binary protocol. No requirements, script source or providers are encoded.
 */
public final class RecipeDefinitionManifestCodec {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STRING_BYTES = 65536;
    private static final int MAX_ENTRIES = 65536;

    private RecipeDefinitionManifestCodec() {
    }

    public static byte[] encode(RecipeDefinitionManifest manifest) {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(new BoundedOutput(bytes))) {
            output.writeLong(manifest.serverSession().getMostSignificantBits());
            output.writeLong(manifest.serverSession().getLeastSignificantBits());
            output.writeLong(manifest.generation());
            string(output, manifest.declaration().id().toString());
            string(output, manifest.declaration().revision());
            count(output, manifest.pools().size());
            var elements2 = manifest.pools();
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var pool = elements2.get(index2);
                string(output, pool.id().toString());
                count(output, pool.members().size());
                var elements = pool.members();
                for (int index = 0; index < elements.size(); index++) {
                    var member = elements.get(index);
                    string(output, member.recipeId().toString());
                    string(output, member.revision());
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode recipe declaration manifest", failure);
        }
        return bytes.toByteArray();
    }

    public static RecipeDefinitionManifest decode(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Recipe manifest payload bounds");
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            var session = new UUID(input.readLong(), input.readLong());
            long generation = input.readLong();
            var key = new RecipeDeclarationKey(id(input), string(input));
            int poolCount = count(input);
            var pools = new ObjectArrayList<RecipeDefinitionManifest.Pool>(poolCount);
            for (int p = 0; p < poolCount; p++) {
                var poolId = id(input);
                int memberCount = count(input);
                var members = new ObjectArrayList<RecipeDefinitionRevision>(memberCount);
                for (int r = 0; r < memberCount; r++)
                    members.add(new RecipeDefinitionRevision(id(input), string(input)));
                pools.add(new RecipeDefinitionManifest.Pool(poolId, members));
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing recipe manifest bytes");
            return new RecipeDefinitionManifest(session, generation, key, pools);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode recipe declaration manifest", failure);
        }
    }

    private static ResourceLocation id(DataInputStream input) throws IOException {
        String value = string(input);
        return new ResourceLocation(value);
    }

    private static int count(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_ENTRIES || count > input.available() / 4)
            throw new IllegalArgumentException("Recipe manifest entry bounds");
        return count;
    }

    private static void count(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Recipe manifest entry bounds");
        output.writeInt(count);
    }

    private static String string(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > MAX_STRING_BYTES || length > input.available())
            throw new IllegalArgumentException("Recipe manifest string bounds");
        var bytes = input.readNBytes(length);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                     .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void string(DataOutputStream output, String value) throws IOException {
        var encoded = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                                            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
        var bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (bytes.length == 0 || bytes.length > MAX_STRING_BYTES)
            throw new IllegalArgumentException("Recipe manifest string bounds");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream output;

        private BoundedOutput(ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) {
            if (output.size() >= MAX_BYTES) throw new IllegalArgumentException("Recipe manifest is too large");
            output.write(value);
        }

        @Override
        public void write(byte @NotNull [] bytes, int offset, int length) {
            if (length > MAX_BYTES - output.size()) throw new IllegalArgumentException("Recipe manifest is too large");
            output.write(bytes, offset, length);
        }
    }
}
