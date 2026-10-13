package io.ajdm.ipc;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.HashSet;

/** Strict tree parsing prevents duplicate-key ambiguity, coercion, unbounded recursion and reflection. */
public final class Protocol {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private Protocol() {}
    public static byte[] encode(JsonObject object) throws IOException {
        byte[] bytes = JSON.toJson(object).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("IPC message too large");
        return bytes;
    }
    public static JsonObject parse(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Invalid IPC length");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new IOException("Invalid UTF-8"); }
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement tree = read(reader, 0);
            if (!tree.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Invalid JSON document");
            return tree.getAsJsonObject();
        } catch (IllegalArgumentException | IllegalStateException e) { throw new IOException("Invalid JSON"); }
    }
    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) throw new IOException("JSON nesting limit");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                var object = new JsonObject(); var keys = new HashSet<String>(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName(); if (!keys.add(key)) throw new IOException("Duplicate JSON key");
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                var array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON token");
        };
    }
    public static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
        return value.getAsString();
    }
    public static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
        return value.getAsBigDecimal().intValueExact();
    }
    public static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null) return fallback;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Expected boolean");
        return value.getAsBoolean();
    }
}
