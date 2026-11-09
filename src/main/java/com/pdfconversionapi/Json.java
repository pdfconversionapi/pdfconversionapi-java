package com.pdfconversionapi;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A small, strict JSON reader (RFC 8259) that works on the UTF-8 bytes of a response.
 *
 * <p>Values map to: objects to {@code Map<String, Object>} (insertion order, a repeated key keeps
 * its last value), arrays to {@code List<Object>}, strings to {@link Str}, numbers to {@link Num},
 * {@code true}/{@code false} to {@link Boolean}, and {@code null} to Java {@code null}.
 *
 * <p>Strings are not copied while parsing: a {@link Str} points into the input, so a 130 MB
 * base64 {@code FileData} is decoded straight from the response bytes by {@link
 * Str#decodeBase64()} without first becoming a Java string.
 */
final class Json {
  /** Deeper nesting than this is refused, so hostile input can't overflow the stack. */
  static final int MAX_DEPTH = 256;

  private final byte[] in;
  private final int end;
  private int pos;
  private int depth;

  private Json(byte[] in) {
    this.in = in;
    this.end = in.length;
  }

  /** Thrown for input that isn't valid JSON. */
  static final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;

    JsonException(String message) {
      super(message);
    }
  }

  /** Parses one JSON document. Whitespace around it, and a UTF-8 byte order mark, are allowed. */
  static @Nullable Object parse(byte[] data) throws JsonException {
    Json json = new Json(data);
    if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
        && (data[2] & 0xFF) == 0xBF) {
      json.pos = 3;
    }
    json.skipWhitespace();
    if (json.pos >= json.end) {
      throw new JsonException("empty input");
    }
    Object value = json.readValue();
    json.skipWhitespace();
    if (json.pos < json.end) {
      throw json.error("unexpected data after the JSON value");
    }
    return value;
  }

  /** Parses UTF-8 text; a convenience for tests and small documents. */
  static @Nullable Object parse(String text) throws JsonException {
    return parse(text.getBytes(StandardCharsets.UTF_8));
  }

  private JsonException error(String message) {
    return new JsonException(message + " at byte " + pos);
  }

  private void skipWhitespace() {
    while (pos < end) {
      byte b = in[pos];
      if (b == ' ' || b == '\t' || b == '\n' || b == '\r') {
        pos++;
      } else {
        return;
      }
    }
  }

  private @Nullable Object readValue() throws JsonException {
    if (pos >= end) {
      throw error("unexpected end of input");
    }
    byte b = in[pos];
    switch (b) {
      case '{':
        return readObject();
      case '[':
        return readArray();
      case '"':
        return readString();
      case 't':
        expectLiteral("true");
        return Boolean.TRUE;
      case 'f':
        expectLiteral("false");
        return Boolean.FALSE;
      case 'n':
        expectLiteral("null");
        return null;
      default:
        if (b == '-' || (b >= '0' && b <= '9')) {
          return readNumber();
        }
        throw error("unexpected character '" + printable(b) + "'");
    }
  }

  private static String printable(byte b) {
    int c = b & 0xFF;
    return c >= 0x20 && c < 0x7F ? String.valueOf((char) c) : String.format("\\x%02x", c);
  }

  private void expectLiteral(String literal) throws JsonException {
    int length = literal.length();
    if (end - pos < length) {
      throw error("unexpected end of input");
    }
    for (int i = 0; i < length; i++) {
      if (in[pos + i] != literal.charAt(i)) {
        throw error("invalid literal");
      }
    }
    pos += length;
  }

  private void enter() throws JsonException {
    if (++depth > MAX_DEPTH) {
      throw error("nesting deeper than " + MAX_DEPTH);
    }
  }

  private Map<String, Object> readObject() throws JsonException {
    enter();
    pos++; // {
    Map<String, Object> map = new LinkedHashMap<>();
    skipWhitespace();
    if (pos < end && in[pos] == '}') {
      pos++;
      depth--;
      return map;
    }
    while (true) {
      skipWhitespace();
      if (pos >= end) {
        throw error("unexpected end of input in an object");
      }
      if (in[pos] != '"') {
        throw error("expected a string key");
      }
      String key = readString().toString();
      skipWhitespace();
      if (pos >= end || in[pos] != ':') {
        throw error("expected ':'");
      }
      pos++;
      skipWhitespace();
      map.put(key, readValue());
      skipWhitespace();
      if (pos >= end) {
        throw error("unexpected end of input in an object");
      }
      byte b = in[pos++];
      if (b == '}') {
        depth--;
        return map;
      }
      if (b != ',') {
        pos--;
        throw error("expected ',' or '}'");
      }
    }
  }

  private List<Object> readArray() throws JsonException {
    enter();
    pos++; // [
    List<Object> list = new ArrayList<>();
    skipWhitespace();
    if (pos < end && in[pos] == ']') {
      pos++;
      depth--;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(readValue());
      skipWhitespace();
      if (pos >= end) {
        throw error("unexpected end of input in an array");
      }
      byte b = in[pos++];
      if (b == ']') {
        depth--;
        return list;
      }
      if (b != ',') {
        pos--;
        throw error("expected ',' or ']'");
      }
    }
  }

  private Str readString() throws JsonException {
    int start = ++pos; // after the opening quote
    boolean escapes = false;
    byte[] data = in;
    int limit = end;
    int i = start;
    while (i < limit) {
      byte b = data[i];
      if (b == '"') {
        pos = i + 1;
        return new Str(data, start, i, escapes);
      }
      if (b == '\\') {
        escapes = true;
        if (i + 1 >= limit) {
          break;
        }
        byte e = data[i + 1];
        if (e == 'u') {
          if (i + 6 > limit) {
            break;
          }
          for (int k = i + 2; k < i + 6; k++) {
            if (hexValue(data[k]) < 0) {
              pos = k;
              throw error("invalid \\u escape");
            }
          }
          i += 6;
          continue;
        }
        if (e != '"' && e != '\\' && e != '/' && e != 'b' && e != 'f' && e != 'n' && e != 'r'
            && e != 't') {
          pos = i;
          throw error("invalid escape");
        }
        i += 2;
        continue;
      }
      if ((b & 0xFF) < 0x20) {
        pos = i;
        throw error("unescaped control character in a string");
      }
      i++;
    }
    pos = limit;
    throw error("unterminated string");
  }

  private static int hexValue(byte b) {
    if (b >= '0' && b <= '9') {
      return b - '0';
    }
    if (b >= 'a' && b <= 'f') {
      return b - 'a' + 10;
    }
    if (b >= 'A' && b <= 'F') {
      return b - 'A' + 10;
    }
    return -1;
  }

  private Num readNumber() throws JsonException {
    int start = pos;
    if (in[pos] == '-') {
      pos++;
    }
    if (pos >= end) {
      throw error("unexpected end of input in a number");
    }
    if (in[pos] == '0') {
      pos++;
    } else if (in[pos] >= '1' && in[pos] <= '9') {
      while (pos < end && in[pos] >= '0' && in[pos] <= '9') {
        pos++;
      }
    } else {
      throw error("invalid number");
    }
    boolean integral = true;
    if (pos < end && in[pos] == '.') {
      integral = false;
      pos++;
      int digits = pos;
      while (pos < end && in[pos] >= '0' && in[pos] <= '9') {
        pos++;
      }
      if (pos == digits) {
        throw error("invalid number: no digits after '.'");
      }
    }
    if (pos < end && (in[pos] == 'e' || in[pos] == 'E')) {
      integral = false;
      pos++;
      if (pos < end && (in[pos] == '+' || in[pos] == '-')) {
        pos++;
      }
      int digits = pos;
      while (pos < end && in[pos] >= '0' && in[pos] <= '9') {
        pos++;
      }
      if (pos == digits) {
        throw error("invalid number: no digits in the exponent");
      }
    }
    return new Num(new String(in, start, pos - start, StandardCharsets.US_ASCII), integral);
  }

  /** A JSON string, kept as a slice of the input until it is read. */
  static final class Str {
    private final byte[] data;
    private final int start;
    private final int end;
    private final boolean escapes;
    private @Nullable String text;

    Str(byte[] data, int start, int end, boolean escapes) {
      this.data = data;
      this.start = start;
      this.end = end;
      this.escapes = escapes;
    }

    /** The length of the raw (still escaped) UTF-8 slice, in bytes. */
    int rawLength() {
      return end - start;
    }

    /** The string's value. */
    @Override
    public String toString() {
      String t = text;
      if (t == null) {
        t = escapes ? unescape() : new String(data, start, end - start, StandardCharsets.UTF_8);
        text = t;
      }
      return t;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof Str && toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
      return toString().hashCode();
    }

    private String unescape() {
      StringBuilder out = new StringBuilder(end - start);
      int run = start;
      int i = start;
      while (i < end) {
        if (data[i] != '\\') {
          i++;
          continue;
        }
        if (i > run) {
          out.append(new String(data, run, i - run, StandardCharsets.UTF_8));
        }
        byte e = data[i + 1];
        switch (e) {
          case 'b':
            out.append('\b');
            break;
          case 'f':
            out.append('\f');
            break;
          case 'n':
            out.append('\n');
            break;
          case 'r':
            out.append('\r');
            break;
          case 't':
            out.append('\t');
            break;
          case 'u':
            out.append(
                (char)
                    ((hexValue(data[i + 2]) << 12)
                        | (hexValue(data[i + 3]) << 8)
                        | (hexValue(data[i + 4]) << 4)
                        | hexValue(data[i + 5])));
            i += 4;
            break;
          default: // " \ /
            out.append((char) e);
        }
        i += 2;
        run = i;
      }
      if (end > run) {
        out.append(new String(data, run, end - run, StandardCharsets.UTF_8));
      }
      return out.toString();
    }

    /**
     * Decodes the string as standard base64 (RFC 4648 alphabet, {@code =} padding), straight
     * from the input bytes when the string has no escapes.
     *
     * @throws IllegalArgumentException if it isn't valid base64
     */
    byte[] decodeBase64() {
      if (escapes) {
        // Only an encoder that escapes '/' as "\/" produces this; it's rare, so take the slow way.
        return Base64.getDecoder().decode(toString().getBytes(StandardCharsets.ISO_8859_1));
      }
      ByteBuffer decoded = Base64.getDecoder().decode(ByteBuffer.wrap(data, start, end - start));
      byte[] array = decoded.array();
      if (decoded.arrayOffset() == 0 && decoded.position() == 0
          && decoded.remaining() == array.length) {
        return array;
      }
      byte[] exact = new byte[decoded.remaining()];
      decoded.get(exact);
      return exact;
    }
  }

  /** A JSON number, kept as its text. */
  static final class Num {
    private final String text;
    private final boolean integral;

    Num(String text, boolean integral) {
      this.text = text;
      this.integral = integral;
    }

    /** True when the number was written without a fraction or an exponent. */
    boolean isIntegral() {
      return integral;
    }

    /**
     * The value as a long.
     *
     * @throws ArithmeticException if it has a fractional part or doesn't fit in a long
     */
    long longValue() {
      if (integral && text.length() < 19) {
        return Long.parseLong(text);
      }
      try {
        return new BigDecimal(text).longValueExact();
      } catch (NumberFormatException e) { // an exponent too large for BigDecimal
        throw new ArithmeticException(text + " is out of range");
      }
    }

    /** The value as a double. */
    double doubleValue() {
      return Double.parseDouble(text);
    }

    @Override
    public String toString() {
      return text;
    }

    @Override
    public boolean equals(@Nullable Object other) {
      return other instanceof Num && text.equals(((Num) other).text);
    }

    @Override
    public int hashCode() {
      return text.hashCode();
    }
  }
}
