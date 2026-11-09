package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonTest {
  private static Object parse(String text) throws Json.JsonException {
    return Json.parse(text);
  }

  @Test
  void scalars() throws Exception {
    assertEquals(Boolean.TRUE, parse("true"));
    assertEquals(Boolean.FALSE, parse(" false "));
    assertNull(parse("null"));
    assertEquals("abc", parse("\"abc\"").toString());
    assertEquals(42, ((Json.Num) parse("42")).longValue());
    assertEquals(-7, ((Json.Num) parse("-7")).longValue());
    assertEquals(0, ((Json.Num) parse("0")).longValue());
    assertEquals(0.036, ((Json.Num) parse("0.036")).doubleValue());
    assertEquals(-1.5e3, ((Json.Num) parse("-1.5E+3")).doubleValue());
    assertEquals(2e-2, ((Json.Num) parse("2e-2")).doubleValue());
  }

  @Test
  void numbersKnowWhetherTheyAreWhole() throws Exception {
    assertTrue(((Json.Num) parse("65272")).isIntegral());
    assertFalse(((Json.Num) parse("1.0")).isIntegral());
    assertFalse(((Json.Num) parse("1e2")).isIntegral());
    assertEquals(1, ((Json.Num) parse("1.0")).longValue());
    assertEquals(100, ((Json.Num) parse("1e2")).longValue());
    assertEquals(Long.MAX_VALUE, ((Json.Num) parse("9223372036854775807")).longValue());
    assertEquals(Long.MIN_VALUE, ((Json.Num) parse("-9223372036854775808")).longValue());
    assertThrows(ArithmeticException.class, () -> ((Json.Num) parse("9223372036854775808")).longValue());
    assertThrows(ArithmeticException.class, () -> ((Json.Num) parse("1.5")).longValue());
    assertThrows(ArithmeticException.class, () -> ((Json.Num) parse("1e99999999999")).longValue());
  }

  @Test
  void objectsKeepOrderAndTheLastDuplicate() throws Exception {
    Map<?, ?> map = (Map<?, ?>) parse("{\"b\":1,\"a\":[true,null,{}],\"b\":2,\"c\":{\"d\":\"e\"}}");
    assertEquals(List.of("b", "a", "c"), List.copyOf(map.keySet()));
    assertEquals("2", map.get("b").toString());
    assertEquals(Arrays.asList(Boolean.TRUE, null, Map.of()), map.get("a"));
    assertEquals("e", ((Map<?, ?>) map.get("c")).get("d").toString());
    assertTrue(map.containsKey("a"));
  }

  @Test
  void emptyContainersAndWhitespace() throws Exception {
    assertEquals(Map.of(), parse(" \t\r\n{ \n } "));
    assertEquals(List.of(), parse("[ ]"));
    assertEquals(List.of(List.of(List.of())), parse("[[[]]]"));
  }

  @Test
  void stringEscapes() throws Exception {
    assertEquals("a\"b\\c/d\be\ff\ng\rh\ti", parse("\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"").toString());
    assertEquals("é", parse("\"\\u00e9\"").toString());
    assertEquals("é", parse("\"\\u00E9\"").toString());
    assertEquals("😀", parse("\"\\ud83d\\ude00\"").toString(), "a surrogate pair");
    assertEquals("x😀y", parse("\"x😀y\"").toString(), "raw UTF-8");
    assertEquals("Résumé final (1).txt", parse("\"Résumé final (1).txt\"").toString());
    assertEquals("中文 \u0000 end", parse("\"中文 \\u0000 end\"").toString());
    assertEquals("", parse("\"\"").toString());
  }

  @Test
  void stringsCompareByValue() throws Exception {
    assertEquals(parse("\"\\u0061b\""), parse("\"ab\""));
    assertEquals(parse("\"ab\"").hashCode(), parse("\"\\u0061b\"").hashCode());
  }

  @Test
  void byteOrderMarkIsSkipped() throws Exception {
    byte[] withBom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '[', '1', ']'};
    assertEquals(1, ((List<?>) Json.parse(withBom)).size());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "   ",
        "{",
        "}",
        "[1,]",
        "[1 2]",
        "{\"a\" 1}",
        "{\"a\":1,}",
        "{a:1}",
        "{\"a\":1 \"b\":2}",
        "'single'",
        "\"unterminated",
        "\"bad escape \\x\"",
        "\"short unicode \\u12\"",
        "\"bad unicode \\u12g4\"",
        "\"tab\tinside\"",
        "\"newline\ninside\"",
        "01",
        "-",
        "1.",
        ".5",
        "1e",
        "1e+",
        "+1",
        "NaN",
        "Infinity",
        "tru",
        "nul",
        "falsey",
        "{} {}",
        "[1] x",
        "\"trailing backslash\\"
      })
  void rejectsInvalidJson(String text) {
    assertThrows(Json.JsonException.class, () -> parse(text), text);
  }

  @Test
  void errorsSayWhere() {
    Json.JsonException e = assertThrows(Json.JsonException.class, () -> parse("[1, x]"));
    assertTrue(e.getMessage().contains("at byte 4"), e.getMessage());
  }

  @Test
  void nestingIsLimited() throws Exception {
    String ok = "[".repeat(Json.MAX_DEPTH) + "]".repeat(Json.MAX_DEPTH);
    assertInstanceOf(List.class, parse(ok));
    String deep = "[".repeat(Json.MAX_DEPTH + 1) + "]".repeat(Json.MAX_DEPTH + 1);
    assertThrows(Json.JsonException.class, () -> parse(deep));
    String hostile = "{\"a\":".repeat(100_000);
    assertThrows(Json.JsonException.class, () -> parse(hostile));
  }

  @Test
  void decodesBase64InPlace() throws Exception {
    byte[] data = "hello, world".getBytes(StandardCharsets.UTF_8);
    String b64 = Base64.getEncoder().encodeToString(data);
    assertArrayEquals(data, ((Json.Str) parse("\"" + b64 + "\"")).decodeBase64());
    // An encoder that escapes '/' as "\/" still decodes.
    byte[] slashes = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertEquals("////", Base64.getEncoder().encodeToString(slashes));
    assertArrayEquals(slashes, ((Json.Str) parse("\"\\/\\/\\/\\/\"")).decodeBase64());
    // Every padding length.
    for (int n = 0; n < 5; n++) {
      byte[] bytes = new byte[n];
      Arrays.fill(bytes, (byte) n);
      String text = "\"" + Base64.getEncoder().encodeToString(bytes) + "\"";
      assertArrayEquals(bytes, ((Json.Str) parse(text)).decodeBase64(), "length " + n);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"not base64!", "aGVsbG8=x", "a\\nGVs", "aGVsbG8===", "é"})
  void rejectsBadBase64(String body) throws Exception {
    Json.Str str = (Json.Str) parse("\"" + body + "\"");
    assertThrows(IllegalArgumentException.class, str::decodeBase64);
  }

  @Test
  void largeBase64IsDecodedQuickly() throws Exception {
    // About 64 MB of JSON, as in a 48 MB output file. Parsing and decoding must stay fast and
    // must not copy the string through a Java String.
    byte[] data = new byte[48 * 1024 * 1024];
    new Random(1).nextBytes(data);
    byte[] b64 = Base64.getEncoder().encode(data);
    ByteArrayOutputStream out = new ByteArrayOutputStream(b64.length + 200);
    out.writeBytes("{\"ConversionCost\":1,\"Files\":[{\"FileName\":\"big.bin\",\"FileData\":\"".getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(b64);
    out.writeBytes(("\",\"FileSize\":" + data.length + "}]}").getBytes(StandardCharsets.US_ASCII));
    byte[] body = out.toByteArray();
    int b64Length = b64.length;
    assertTimeoutPreemptively(
        Duration.ofSeconds(20),
        () -> {
          Map<?, ?> root = (Map<?, ?>) Json.parse(body);
          Map<?, ?> file = (Map<?, ?>) ((List<?>) root.get("Files")).get(0);
          Json.Str fileData = (Json.Str) file.get("FileData");
          assertEquals(b64Length, fileData.rawLength());
          assertArrayEquals(data, fileData.decodeBase64());
        });
  }
}
