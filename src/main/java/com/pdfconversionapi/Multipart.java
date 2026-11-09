package com.pdfconversionapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * A multipart/form-data body, written by hand because the JDK has no encoder.
 *
 * <p>File names go in {@code filename="..."} as raw UTF-8, with {@code "}, CR and LF replaced by
 * {@code _}: the API reads {@code %22} literally and turns a quote into {@code _} itself, so
 * {@code say "hi".txt} comes back as {@code say _hi_.pdf}. Never {@code filename*=}, never
 * percent-encoding: the API reads only the plain {@code filename="..."}. Field names keep the
 * HTML form rule ({@code %22}, {@code %0D}, {@code %0A}), so a malformed parameter name still
 * reaches the API and gets its 400. Files on disk are streamed, and re-read for each attempt.
 */
final class Multipart {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final byte[] CRLF = {'\r', '\n'};

  private final String boundary;
  /** Each segment is a byte[] (headers, text values, data in memory) or a Path (streamed). */
  private final List<Object> segments = new ArrayList<>();

  private Multipart(String boundary) {
    this.boundary = boundary;
  }

  /** Starts a body with a random boundary. */
  static Multipart create() {
    byte[] random = new byte[16];
    RANDOM.nextBytes(random);
    StringBuilder boundary = new StringBuilder("pdfconversionapi-");
    for (byte b : random) {
      boundary.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
    }
    return new Multipart(boundary.toString());
  }

  /** A body with a fixed boundary, for tests. */
  static Multipart withBoundary(String boundary) {
    return new Multipart(boundary);
  }

  String boundary() {
    return boundary;
  }

  String contentType() {
    return "multipart/form-data; boundary=" + boundary;
  }

  /** A file name for {@code filename="..."}: a quote, CR or LF becomes {@code _}. */
  static String fileName(String value) {
    return value.replace('"', '_').replace('\r', '_').replace('\n', '_');
  }

  /** Escapes a field name for {@code name="..."} with the HTML form rule. */
  static String escape(String value) {
    StringBuilder out = null;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      String replacement = c == '"' ? "%22" : c == '\r' ? "%0D" : c == '\n' ? "%0A" : null;
      if (replacement != null) {
        if (out == null) {
          out = new StringBuilder(value.length() + 8).append(value, 0, i);
        }
        out.append(replacement);
      } else if (out != null) {
        out.append(c);
      }
    }
    return out == null ? value : out.toString();
  }

  /** Adds a text field. */
  Multipart text(String name, String value) {
    segments.add(
        ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + escape(name) + "\"\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    segments.add(value.getBytes(StandardCharsets.UTF_8));
    segments.add(CRLF);
    return this;
  }

  /** Adds a file field. */
  Multipart file(String name, UploadFile file) {
    segments.add(
        ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + escape(name)
                + "\"; filename=\"" + fileName(file.getName())
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8));
    byte[] bytes = file.bytes();
    segments.add(bytes != null ? bytes : file.getPath());
    segments.add(CRLF);
    return this;
  }

  /** Writes the closing boundary. Call once, after the last field. */
  Multipart finish() {
    segments.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return this;
  }

  /**
   * The body's length now. Files on disk are measured again on every call, so a retry after a
   * file changed sends a correct Content-Length.
   *
   * @throws UncheckedIOException if a file can't be read
   */
  long contentLength() {
    long length = 0;
    for (Object segment : segments) {
      if (segment instanceof byte[]) {
        length += ((byte[]) segment).length;
      } else {
        Path path = (Path) segment;
        try {
          if (Files.isDirectory(path)) {
            throw new java.nio.file.FileSystemException(path.toString(), null, "is a folder, not a file");
          }
          length += Files.size(path);
        } catch (IOException e) {
          throw new UncheckedIOException("can't read the input file " + path, e);
        }
      }
    }
    return length;
  }

  /**
   * A publisher for one attempt. A failure to read a local file is stored in {@code localError},
   * so the caller can tell it apart from a network failure (which is retried).
   */
  HttpRequest.BodyPublisher publisher(AtomicReference<@Nullable IOException> localError) {
    long length = contentLength();
    return HttpRequest.BodyPublishers.fromPublisher(
        HttpRequest.BodyPublishers.ofInputStream(() -> new SegmentStream(segments, localError)),
        length);
  }

  /** The whole body in memory, for tests. */
  byte[] toByteArray() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (InputStream in = new SegmentStream(segments, new AtomicReference<>())) {
      in.transferTo(out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  /**
   * Turns a parameter value into its text: strings as they are, booleans as true/false, numbers
   * in plain decimal (never an exponent).
   *
   * @throws IllegalArgumentException for a value of another type, NaN or infinity
   */
  static String formatValue(String name, Object value) {
    if (value instanceof CharSequence) {
      return value.toString();
    }
    if (value instanceof Boolean) {
      return ((Boolean) value) ? "true" : "false";
    }
    if (value instanceof Character) {
      return value.toString();
    }
    if (value instanceof Integer || value instanceof Long || value instanceof Short
        || value instanceof Byte || value instanceof BigInteger) {
      return value.toString();
    }
    if (value instanceof BigDecimal) {
      return plain((BigDecimal) value);
    }
    if (value instanceof Double || value instanceof Float) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new IllegalArgumentException("parameter " + name + " is " + value + ", which isn't a number the API reads");
      }
      // Float.toString keeps a float's shortest form (1.1f is "1.1", not 1.100000023841858).
      return plain(new BigDecimal(value instanceof Float ? Float.toString((Float) value) : Double.toString(d)));
    }
    if (value instanceof Number) { // AtomicLong, LongAdder and the like
      try {
        return plain(new BigDecimal(value.toString()));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("parameter " + name + " is " + value + ", which isn't a number the API reads", e);
      }
    }
    if (value instanceof Enum) {
      return ((Enum<?>) value).name();
    }
    throw new IllegalArgumentException(
        "parameter " + name + " has a value of type " + value.getClass().getName()
            + "; use a String, a number, a Boolean, or an UploadFile, Path or File for a file");
  }

  private static String plain(BigDecimal value) {
    BigDecimal stripped = value.stripTrailingZeros();
    // 72.0 is sent as 72, 1.50 as 1.5, 1E+3 as 1000.
    return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
  }

  /** Reads the segments in order, opening each file only when it is reached. */
  private static final class SegmentStream extends InputStream {
    private final List<Object> segments;
    private final AtomicReference<@Nullable IOException> localError;
    private int index = -1;
    private @Nullable InputStream current;

    SegmentStream(List<Object> segments, AtomicReference<@Nullable IOException> localError) {
      this.segments = segments;
      this.localError = localError;
    }

    private boolean advance() throws IOException {
      closeCurrent();
      index++;
      if (index >= segments.size()) {
        return false;
      }
      Object segment = segments.get(index);
      if (segment instanceof byte[]) {
        current = new java.io.ByteArrayInputStream((byte[]) segment);
      } else {
        try {
          current = Files.newInputStream((Path) segment);
        } catch (IOException e) {
          localError.compareAndSet(null, e);
          throw e;
        }
      }
      return true;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (length == 0) {
        return 0;
      }
      while (true) {
        InputStream in = current;
        if (in == null) {
          if (!advance()) {
            return -1;
          }
          continue;
        }
        int n;
        try {
          n = in.read(buffer, offset, length);
        } catch (IOException e) {
          localError.compareAndSet(null, e);
          throw e;
        }
        if (n > 0) {
          return n;
        }
        if (n < 0 && !advance()) {
          return -1;
        }
      }
    }

    private void closeCurrent() throws IOException {
      InputStream in = current;
      current = null;
      if (in != null) {
        in.close();
      }
    }

    @Override
    public void close() throws IOException {
      index = segments.size();
      closeCurrent();
    }
  }

  /** Adds the fields of one conversion request, in order: files, parameters, then options. */
  static Multipart forConversion(
      List<UploadFile> files, Map<String, ?> params, ConvertOptions options) {
    Multipart body = create();
    String fileField = files.size() == 1 ? "File" : "Files";
    for (UploadFile file : files) {
      body.file(fileField, file);
    }
    for (Map.Entry<String, ?> entry : params.entrySet()) {
      String name = entry.getKey();
      Object value = entry.getValue();
      if (options.getFileName() != null && name.equalsIgnoreCase("FileName")) {
        continue; // the typed option wins
      }
      if (options.getStoreFile() != null && name.equalsIgnoreCase("StoreFile")) {
        continue;
      }
      if (value == null) {
        continue;
      }
      if (value instanceof UploadFile) {
        body.file(name, (UploadFile) value);
      } else if (value instanceof Path) {
        body.file(name, UploadFile.fromPath((Path) value));
      } else if (value instanceof java.io.File) {
        body.file(name, UploadFile.fromPath(((java.io.File) value).toPath()));
      } else {
        body.text(name, formatValue(name, value));
      }
    }
    String fileName = options.getFileName();
    if (fileName != null) {
      body.text("FileName", fileName);
    }
    if (Boolean.TRUE.equals(options.getStoreFile())) {
      body.text("StoreFile", "true");
    }
    return body.finish();
  }
}
