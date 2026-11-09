package com.pdfconversionapi;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Reads successful responses into result types, or throws {@link InvalidResponseException}. */
final class Responses {
  private Responses() {}

  /** The response body of a conversion. */
  static ConversionResult conversion(byte[] body, @Nullable String requestId, PdfConversionApi client) {
    Reader root = Reader.parse(body, requestId);
    List<Object> entries = root.list("Files");
    if (entries.isEmpty()) {
      throw root.invalid("the response's Files list is empty");
    }
    List<OutputFile> files = new ArrayList<>(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      Reader entry = root.child(entries.get(i), "Files[" + i + "]");
      files.add(outputFile(entry, client));
    }
    int cost = root.has("ConversionCost") ? (int) root.integer("ConversionCost", Integer.MAX_VALUE) : 0;
    double time = root.has("ConversionTime") ? root.number("ConversionTime") : 0.0;
    return new ConversionResult(files, cost, time, requestId);
  }

  private static OutputFile outputFile(Reader entry, PdfConversionApi client) {
    String name = entry.string("FileName");
    long size = entry.integer("FileSize", Long.MAX_VALUE);
    if (size < 0) {
      throw entry.invalid("FileSize is negative");
    }
    String ext = entry.has("FileExt") ? entry.string("FileExt") : extensionOf(name);
    if (entry.has("FileData")) {
      Json.Str encoded = entry.str("FileData");
      byte[] data;
      try {
        data = encoded.decodeBase64();
      } catch (IllegalArgumentException e) {
        throw entry.invalid("FileData of " + name + " isn't valid base64", e);
      }
      if (data.length != size) {
        throw entry.invalid(
            "FileData of " + name + " decodes to " + data.length + " bytes, but FileSize says " + size);
      }
      return new OutputFile(name, ext, size, data, null, null, client);
    }
    if (entry.has("Url") && entry.has("FileId")) {
      return new OutputFile(name, ext, size, null, entry.string("Url"), entry.string("FileId"), client);
    }
    throw entry.invalid("output " + name + " has neither FileData nor Url and FileId");
  }

  private static String extensionOf(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /** The response body of {@code GET /v2/usage}. */
  static Usage usage(byte[] body, @Nullable String requestId) {
    Reader root = Reader.parse(body, requestId);
    Long remaining = root.isNullOrAbsent("Remaining") ? null : root.integer("Remaining", Long.MAX_VALUE);
    return new Usage(
        root.string("Plan"),
        root.integer("Included", Long.MAX_VALUE),
        root.integer("Used", Long.MAX_VALUE),
        remaining,
        root.has("Overage") && root.bool("Overage"),
        root.has("Running") ? (int) root.integer("Running", Integer.MAX_VALUE) : 0,
        (int) root.integer("ConcurrentLimit", Integer.MAX_VALUE),
        (int) root.integer("MaxUploadMB", Integer.MAX_VALUE),
        root.instant("PeriodStart"),
        root.instant("PeriodEnd"));
  }

  /** The response body of {@code GET /v2/info}. */
  static List<Conversion> conversions(byte[] body, @Nullable String requestId) {
    Object json;
    try {
      json = Json.parse(body);
    } catch (Json.JsonException e) {
      throw new InvalidResponseException("the response isn't valid JSON: " + e.getMessage(), requestId, e);
    }
    if (!(json instanceof List)) {
      throw new InvalidResponseException("the response isn't a JSON array", requestId, null);
    }
    List<?> entries = (List<?>) json;
    Reader root = new Reader(Map.of(), "response", requestId);
    List<Conversion> conversions = new ArrayList<>(entries.size());
    for (int i = 0; i < entries.size(); i++) {
      Reader entry = root.child(entries.get(i), "[" + i + "]");
      conversions.add(
          new Conversion(
              entry.string("From"),
              entry.string("To"),
              entry.string("Title"),
              entry.string("Category"),
              entry.string("Inputs")));
    }
    return java.util.Collections.unmodifiableList(conversions);
  }

  /** Typed access to one JSON object, with errors that say where the problem is. */
  static final class Reader {
    private final Map<?, ?> object;
    private final String where;
    private final @Nullable String requestId;

    Reader(Map<?, ?> object, String where, @Nullable String requestId) {
      this.object = object;
      this.where = where;
      this.requestId = requestId;
    }

    static Reader parse(byte[] body, @Nullable String requestId) {
      Object json;
      try {
        json = Json.parse(body);
      } catch (Json.JsonException e) {
        throw new InvalidResponseException(
            "the response isn't valid JSON: " + e.getMessage(), requestId, e);
      }
      if (!(json instanceof Map)) {
        throw new InvalidResponseException("the response isn't a JSON object", requestId, null);
      }
      return new Reader((Map<?, ?>) json, "response", requestId);
    }

    InvalidResponseException invalid(String message) {
      return new InvalidResponseException(message, requestId, null);
    }

    InvalidResponseException invalid(String message, Throwable cause) {
      return new InvalidResponseException(message, requestId, cause);
    }

    Reader child(@Nullable Object value, String path) {
      if (!(value instanceof Map)) {
        throw invalid(path + " in the response isn't a JSON object");
      }
      return new Reader((Map<?, ?>) value, path, requestId);
    }

    boolean has(String key) {
      return object.get(key) != null;
    }

    boolean isNullOrAbsent(String key) {
      return object.get(key) == null;
    }

    private Object require(String key) {
      Object value = object.get(key);
      if (value == null) {
        throw invalid(where + " has no " + key);
      }
      return value;
    }

    Json.Str str(String key) {
      Object value = require(key);
      if (!(value instanceof Json.Str)) {
        throw invalid(key + " in " + where + " isn't a string");
      }
      return (Json.Str) value;
    }

    String string(String key) {
      return str(key).toString();
    }

    long integer(String key, long max) {
      Object value = require(key);
      if (!(value instanceof Json.Num)) {
        throw invalid(key + " in " + where + " isn't a number");
      }
      long result;
      try {
        result = ((Json.Num) value).longValue();
      } catch (ArithmeticException e) {
        throw invalid(key + " in " + where + " isn't a whole number in range: " + value, e);
      }
      if (result > max || result < -max) {
        throw invalid(key + " in " + where + " is out of range: " + value);
      }
      return result;
    }

    double number(String key) {
      Object value = require(key);
      if (!(value instanceof Json.Num)) {
        throw invalid(key + " in " + where + " isn't a number");
      }
      return ((Json.Num) value).doubleValue();
    }

    boolean bool(String key) {
      Object value = require(key);
      if (!(value instanceof Boolean)) {
        throw invalid(key + " in " + where + " isn't true or false");
      }
      return (Boolean) value;
    }

    List<Object> list(String key) {
      Object value = require(key);
      if (!(value instanceof List)) {
        throw invalid(key + " in " + where + " isn't a list");
      }
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) value;
      return list;
    }

    Instant instant(String key) {
      String text = string(key);
      try {
        return OffsetDateTime.parse(text).toInstant();
      } catch (DateTimeParseException e) {
        throw invalid(key + " in " + where + " isn't a date-time: " + text, e);
      }
    }
  }
}
