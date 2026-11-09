package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultipartTest {
  @TempDir Path temp;

  private static String text(Multipart body) {
    return new String(body.toByteArray(), StandardCharsets.UTF_8);
  }

  @Test
  void exactBytesOfATextAndAFileField() {
    Multipart body =
        Multipart.withBoundary("BOUNDARY")
            .file("File", UploadFile.fromBytes("hi\n".getBytes(StandardCharsets.UTF_8), "a.txt"))
            .text("PageRange", "1-3")
            .finish();
    String expected =
        "--BOUNDARY\r\n"
            + "Content-Disposition: form-data; name=\"File\"; filename=\"a.txt\"\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "\r\n"
            + "hi\n\r\n"
            + "--BOUNDARY\r\n"
            + "Content-Disposition: form-data; name=\"PageRange\"\r\n"
            + "\r\n"
            + "1-3\r\n"
            + "--BOUNDARY--\r\n";
    assertEquals(expected, text(body));
    assertEquals(expected.getBytes(StandardCharsets.UTF_8).length, body.contentLength());
    assertEquals("multipart/form-data; boundary=BOUNDARY", body.contentType());
  }

  @Test
  void fileNamesAreRawUtf8WithQuotesAndLineBreaksReplaced() {
    Multipart body =
        Multipart.withBoundary("B")
            .file("File", UploadFile.fromBytes(new byte[0], "Résumé \"final\"\r\n(1).txt"))
            .finish();
    String text = text(body);
    assertTrue(text.contains("filename=\"Résumé _final___(1).txt\""), text);
    assertFalse(text.contains("filename*"), text);
    assertFalse(text.contains("%"), text);
    // The UTF-8 bytes themselves, not an encoding of them.
    byte[] bytes = body.toByteArray();
    byte[] e9 = "é".getBytes(StandardCharsets.UTF_8);
    assertTrue(indexOf(bytes, e9) > 0);
  }

  @Test
  void fieldNamesUseTheHtmlFormRule() {
    assertEquals("a%22b%0D%0Ac", Multipart.escape("a\"b\r\nc"));
    assertEquals("plain", Multipart.escape("plain"));
    String text = text(Multipart.withBoundary("B").text("we\"ird", "v").finish());
    assertTrue(text.contains("name=\"we%22ird\""), text);
  }

  @Test
  void fileNameReplacement() {
    assertEquals("say _hi_.txt", Multipart.fileName("say \"hi\".txt"));
    assertEquals("a_b_c", Multipart.fileName("a\rb\nc"));
    assertEquals("ok.pdf", Multipart.fileName("ok.pdf"));
  }

  @Test
  void boundariesAreRandom() {
    String a = Multipart.create().boundary();
    String b = Multipart.create().boundary();
    assertNotEquals(a, b);
    assertTrue(a.matches("pdfconversionapi-[0-9a-f]{32}"), a);
  }

  @Test
  void conversionFieldOrderAndFileFieldNames() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("PageRange", "1-3");
    params.put("ImageFile", UploadFile.fromBytes(new byte[] {1}, "logo.png"));
    params.put("Empty", null);
    params.put("Opacity", 30);

    String one = text(Multipart.forConversion(List.of(UploadFile.fromBytes(new byte[] {1}, "a.pdf")), params, ConvertOptions.defaults()));
    assertOrder(one, "name=\"File\"; filename=\"a.pdf\"", "name=\"PageRange\"", "name=\"ImageFile\"; filename=\"logo.png\"", "name=\"Opacity\"");
    assertFalse(one.contains("Empty"), "a null parameter is left out");
    assertFalse(one.contains("name=\"Files\""));

    String many =
        text(
            Multipart.forConversion(
                List.of(UploadFile.fromBytes(new byte[] {1}, "a.pdf"), UploadFile.fromBytes(new byte[] {2}, "b.pdf")),
                Map.of(),
                ConvertOptions.defaults()));
    assertOrder(many, "name=\"Files\"; filename=\"a.pdf\"", "name=\"Files\"; filename=\"b.pdf\"");
    assertFalse(many.contains("name=\"File\""));

    String none = text(Multipart.forConversion(List.of(), Map.of("Data", "x"), ConvertOptions.defaults()));
    assertFalse(none.contains("filename="));
    assertTrue(none.contains("name=\"Data\""));
  }

  @Test
  void typedOptionsWinOverParameters() {
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("filename", "from-params");
    params.put("STOREFILE", "true");
    params.put("Other", "x");
    String text =
        text(Multipart.forConversion(List.of(), params, ConvertOptions.builder().fileName("typed").storeFile(false).build()));
    assertFalse(text.contains("from-params"));
    assertFalse(text.contains("STOREFILE"), "storeFile(false) wins and sends nothing");
    assertTrue(text.contains("name=\"FileName\"\r\n\r\ntyped\r\n"), text);
    assertTrue(text.contains("name=\"Other\""));

    String stored = text(Multipart.forConversion(List.of(), Map.of(), ConvertOptions.builder().storeFile(true).build()));
    assertTrue(stored.contains("name=\"StoreFile\"\r\n\r\ntrue\r\n"), stored);

    // Without typed options the parameters are sent as they are.
    String plain = text(Multipart.forConversion(List.of(), params, ConvertOptions.defaults()));
    assertTrue(plain.contains("from-params"));
    assertTrue(plain.contains("name=\"STOREFILE\""));
  }

  @Test
  void valuesAreFormattedAsTheApiReadsThem() {
    assertEquals("72", Multipart.formatValue("n", 72));
    assertEquals("-3", Multipart.formatValue("n", -3L));
    assertEquals("7", Multipart.formatValue("n", (short) 7));
    assertEquals("7", Multipart.formatValue("n", (byte) 7));
    assertEquals("1.5", Multipart.formatValue("n", 1.5));
    assertEquals("72", Multipart.formatValue("n", 72.0));
    assertEquals("0.1", Multipart.formatValue("n", 0.1));
    assertEquals("1.1", Multipart.formatValue("n", 1.1f));
    assertEquals("10000000000", Multipart.formatValue("n", 1e10));
    assertEquals("0.00001", Multipart.formatValue("n", 1e-5));
    assertEquals("123456789012345678901234567890", Multipart.formatValue("n", new BigInteger("123456789012345678901234567890")));
    assertEquals("1000", Multipart.formatValue("n", new BigDecimal("1E+3")));
    assertEquals("1.5", Multipart.formatValue("n", new BigDecimal("1.50")));
    assertEquals("0", Multipart.formatValue("n", 0.0));
    assertEquals("5", Multipart.formatValue("n", new AtomicLong(5)));
    assertEquals("true", Multipart.formatValue("b", true));
    assertEquals("false", Multipart.formatValue("b", Boolean.FALSE));
    assertEquals("1-3", Multipart.formatValue("s", "1-3"));
    assertEquals("sb", Multipart.formatValue("s", new StringBuilder("sb")));
    assertEquals("x", Multipart.formatValue("c", 'x'));
    assertEquals("SECONDS", Multipart.formatValue("e", TimeUnit.SECONDS));
    assertEquals("", Multipart.formatValue("s", ""));
  }

  @Test
  void unusableValuesAreArgumentErrors() {
    assertThrows(IllegalArgumentException.class, () -> Multipart.formatValue("n", Double.NaN));
    assertThrows(IllegalArgumentException.class, () -> Multipart.formatValue("n", Double.POSITIVE_INFINITY));
    assertThrows(IllegalArgumentException.class, () -> Multipart.formatValue("n", Float.NEGATIVE_INFINITY));
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> Multipart.formatValue("When", Duration.ofSeconds(1)));
    assertTrue(e.getMessage().contains("When"), e.getMessage());
    assertThrows(IllegalArgumentException.class, () -> Multipart.formatValue("l", List.of(1)));
  }

  @Test
  void filesOnDiskAreStreamedAndMeasuredEachTime() throws Exception {
    Path file = temp.resolve("in.txt");
    Files.writeString(file, "first");
    Multipart body = Multipart.withBoundary("B").file("File", UploadFile.fromPath(file)).finish();
    long first = body.contentLength();
    assertTrue(text(body).contains("\r\n\r\nfirst\r\n"));
    Files.writeString(file, "second, longer");
    assertEquals(first + "second, longer".length() - "first".length(), body.contentLength());
    assertTrue(text(body).contains("\r\n\r\nsecond, longer\r\n"));
    Files.delete(file);
    assertThrows(UncheckedIOException.class, body::contentLength);
  }

  @Test
  void pathAndFileParameterValuesAreFiles() throws Exception {
    Path logo = Files.write(temp.resolve("logo.png"), new byte[] {1, 2, 3});
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("ImageFile", logo);
    params.put("OverlayFile", logo.toFile());
    String text = text(Multipart.forConversion(List.of(), params, ConvertOptions.defaults()));
    assertTrue(text.contains("name=\"ImageFile\"; filename=\"logo.png\""), text);
    assertTrue(text.contains("name=\"OverlayFile\"; filename=\"logo.png\""), text);
  }

  @Test
  void aFolderIsNotAFile() {
    Multipart body = Multipart.withBoundary("B").file("File", UploadFile.fromPath(temp)).finish();
    assertThrows(UncheckedIOException.class, body::contentLength);
  }

  private static void assertOrder(String text, String... parts) {
    int from = 0;
    for (String part : parts) {
      int at = text.indexOf(part, from);
      assertTrue(at >= 0, "missing or out of order: " + part + "\n" + text);
      from = at + part.length();
    }
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
