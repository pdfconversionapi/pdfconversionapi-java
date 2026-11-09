package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResponsesTest {
  private final PdfConversionApi client = PdfConversionApi.builder().environment(name -> null).build();

  @TempDir Path temp;

  private ConversionResult parse(String json) {
    return Responses.conversion(json.getBytes(StandardCharsets.UTF_8), "rid", client);
  }

  @Test
  void inlineFiles() {
    ConversionResult result =
        parse(
            "{\"ConversionCost\":1,\"ConversionTime\":0.036,\"Files\":["
                + "{\"FileName\":\"season-guide-1.jpg\",\"FileExt\":\"jpg\",\"FileSize\":3,\"FileData\":\"AQID\"},"
                + "{\"FileName\":\"season-guide-2.jpg\",\"FileExt\":\"jpg\",\"FileSize\":0,\"FileData\":\"\"}]}");
    assertEquals(1, result.getConversionCost());
    assertEquals(0.036, result.getConversionTime());
    assertEquals("rid", result.getRequestId());
    assertEquals(2, result.getFiles().size());
    OutputFile first = result.getFile();
    assertEquals("season-guide-1.jpg", first.getName());
    assertEquals("jpg", first.getExt());
    assertEquals(3, first.getSize());
    assertArrayEquals(new byte[] {1, 2, 3}, first.getData());
    assertArrayEquals(new byte[0], result.getFiles().get(1).getData());
    assertThrows(UnsupportedOperationException.class, () -> result.getFiles().clear());
    assertTrue(result.toString().contains("season-guide-1.jpg"));
  }

  @Test
  void storedFiles() {
    ConversionResult result =
        parse(
            "{\"ConversionCost\":1,\"ConversionTime\":0.04,\"Files\":[{\"FileName\":\"a.jpg\",\"FileExt\":\"jpg\","
                + "\"FileSize\":65272,\"Url\":\"https://api.pdfconversionapi.com/v2/d/32001ff088d5c35e10e4bc6f6ddf09a9/a.jpg\","
                + "\"FileId\":\"32001ff088d5c35e10e4bc6f6ddf09a9\"}]}");
    OutputFile file = result.getFile();
    assertTrue(file.isStored());
    assertEquals(65272, file.getSize());
    assertEquals("32001ff088d5c35e10e4bc6f6ddf09a9", file.getFileId());
    assertTrue(file.getUrl().endsWith("/a.jpg"));
    assertThrows(StoredFileException.class, file::getData);
  }

  @Test
  void lenientWhereItIsHarmless() {
    ConversionResult result = parse("{\"Files\":[{\"FileName\":\"a.PDF\",\"FileSize\":1,\"FileData\":\"QQ==\",\"Extra\":[1,2]}],\"New\":{}}");
    assertEquals(0, result.getConversionCost());
    assertEquals(0.0, result.getConversionTime());
    assertEquals("pdf", result.getFile().getExt(), "FileExt missing: taken from the name");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{not json",
        "[]",
        "{}",
        "{\"Files\":null}",
        "{\"Files\":{}}",
        "{\"Files\":[]}",
        "{\"Files\":[1]}",
        "{\"Files\":[{\"FileSize\":1,\"FileData\":\"QQ==\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileData\":\"QQ==\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":\"1\",\"FileData\":\"QQ==\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":1.5,\"FileData\":\"QQ==\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":-1,\"FileData\":\"\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":2,\"FileData\":\"QQ==\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":1,\"FileData\":\"Q!==\"}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":1}]}",
        "{\"Files\":[{\"FileName\":\"a\",\"FileSize\":1,\"Url\":\"https://x\"}]}",
        "{\"Files\":[{\"FileName\":5,\"FileSize\":1,\"FileData\":\"QQ==\"}]}",
        "{\"ConversionCost\":\"1\",\"Files\":[{\"FileName\":\"a\",\"FileSize\":1,\"FileData\":\"QQ==\"}]}",
      })
  void unusableBodiesAreInvalidResponses(String json) {
    InvalidResponseException e = assertThrows(InvalidResponseException.class, () -> parse(json));
    assertEquals("rid", e.getRequestId());
  }

  @Test
  void sizeMismatchSaysWhat() {
    InvalidResponseException e =
        assertThrows(InvalidResponseException.class, () -> parse("{\"Files\":[{\"FileName\":\"a.pdf\",\"FileSize\":2,\"FileData\":\"QQ==\"}]}"));
    assertTrue(e.getMessage().contains("1 bytes") && e.getMessage().contains("FileSize says 2"), e.getMessage());
  }

  @Test
  void usage() {
    Usage usage =
        Responses.usage(
            ("{\"Plan\":\"Free\",\"Included\":250,\"Used\":12,\"Remaining\":238,\"Overage\":false,\"Running\":0,"
                    + "\"ConcurrentLimit\":1,\"MaxUploadMB\":10,\"PeriodStart\":\"2026-11-01T00:00:00.000Z\","
                    + "\"PeriodEnd\":\"2026-12-01T00:00:00.000Z\"}")
                .getBytes(StandardCharsets.UTF_8),
            null);
    assertEquals(238L, usage.getRemaining());
    assertEquals(usage, Responses.usage(usageJson().getBytes(StandardCharsets.UTF_8), null));
    assertEquals(usage.hashCode(), Responses.usage(usageJson().getBytes(StandardCharsets.UTF_8), null).hashCode());
    assertTrue(usage.toString().contains("plan=Free"));
    assertThrows(InvalidResponseException.class, () -> Responses.usage("{\"Plan\":\"Free\"}".getBytes(StandardCharsets.UTF_8), null));
    assertThrows(
        InvalidResponseException.class,
        () -> Responses.usage(usageJson().replace("2026-11-01T00:00:00.000Z", "yesterday").getBytes(StandardCharsets.UTF_8), null));
    assertNull(Responses.usage(usageJson().replace("\"Remaining\":238", "\"Remaining\":null").getBytes(StandardCharsets.UTF_8), null).getRemaining());
  }

  private static String usageJson() {
    return "{\"Plan\":\"Free\",\"Included\":250,\"Used\":12,\"Remaining\":238,\"Overage\":false,\"Running\":0,"
        + "\"ConcurrentLimit\":1,\"MaxUploadMB\":10,\"PeriodStart\":\"2026-11-01T00:00:00.000Z\","
        + "\"PeriodEnd\":\"2026-12-01T00:00:00.000Z\"}";
  }

  @Test
  void conversions() {
    List<Conversion> list =
        Responses.conversions(
            "[{\"From\":\"docx\",\"To\":\"pdf\",\"Title\":\"DOCX to PDF\",\"Category\":\"Document\",\"Inputs\":\"one\"}]"
                .getBytes(StandardCharsets.UTF_8),
            null);
    assertEquals(1, list.size());
    Conversion c = list.get(0);
    assertEquals(new Conversion("docx", "pdf", "DOCX to PDF", "Document", "one"), c);
    assertEquals(new Conversion("docx", "pdf", "DOCX to PDF", "Document", "one").hashCode(), c.hashCode());
    assertTrue(c.toString().contains("docx/to/pdf"));
    assertThrows(InvalidResponseException.class, () -> Responses.conversions("{}".getBytes(StandardCharsets.UTF_8), null));
    assertThrows(InvalidResponseException.class, () -> Responses.conversions("[{\"From\":\"x\"}]".getBytes(StandardCharsets.UTF_8), null));
    assertThrows(InvalidResponseException.class, () -> Responses.conversions("[1]".getBytes(StandardCharsets.UTF_8), null));
  }

  @Test
  void namesFromTheApiStayInsideTheFolder() throws Exception {
    for (String hostile : new String[] {"../escape.pdf", "..\\escape.pdf", "/etc/passwd", "C:\\Windows\\x.pdf", "a/b/c.pdf"}) {
      ConversionResult result =
          parse("{\"Files\":[{\"FileName\":" + quote(hostile) + ",\"FileSize\":1,\"FileData\":\"QQ==\"}]}");
      Path dir = Files.createTempDirectory(temp, "out");
      List<Path> written = result.saveAll(dir);
      assertEquals(dir, written.get(0).getParent(), hostile);
      assertEquals("A", Files.readString(written.get(0)));
    }
    for (String unusable : new String[] {"..", ".", "a/..", "dir/", "x\\"}) {
      ConversionResult result =
          parse("{\"Files\":[{\"FileName\":" + quote(unusable) + ",\"FileSize\":1,\"FileData\":\"QQ==\"}]}");
      assertThrows(InvalidResponseException.class, () -> result.saveAll(temp), unusable);
    }
  }

  @Test
  void safeNames() {
    assertEquals("a.pdf", FileNames.safeName("a.pdf"));
    assertEquals("c.pdf", FileNames.safeName("a/b\\c.pdf"));
    assertEquals("My Photo _1_.jpg", FileNames.safeName("My Photo _1_.jpg"));
    assertEquals("Résumé.pdf", FileNames.safeName("Résumé.pdf"));
    assertEquals("...pdf", FileNames.safeName("...pdf"));
    for (String bad : new String[] {"", ".", "..", "a/", "a\\..", "nul\0.pdf"}) {
      assertThrows(InvalidResponseException.class, () -> FileNames.safeName(bad), bad);
    }
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
