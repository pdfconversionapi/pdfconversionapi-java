package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

/**
 * Against the real API. Reads PDFCONVERSIONAPI_LOCAL_BASE_URL and PDFCONVERSIONAPI_LOCAL_KEY from
 * conformance/.local-api.env (the API running locally), else PDFCONVERSIONAPI_LIVE_BASE_URL and
 * PDFCONVERSIONAPI_KEY from the environment (the release workflow's run against the production
 * API), and skips without either (or with -Dpdfconversionapi.skipLive=true). The key alone doesn't
 * start them, and it is never printed. The input files are in conformance/samples. About 5
 * conversions a run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveTest {
  private PdfConversionApi client;
  private String key;
  private String baseUrl;
  private Path samples;

  @TempDir Path temp;

  @BeforeAll
  void setUp() throws IOException {
    assumeTrue(!Boolean.getBoolean("pdfconversionapi.skipLive"), "live tests skipped by request");
    Path conformance = MockServer.conformanceDir();
    Path envFile = conformance.resolve(".local-api.env");
    if (Files.isRegularFile(envFile)) {
      Map<String, String> env = readEnv(envFile);
      baseUrl = env.get("PDFCONVERSIONAPI_LOCAL_BASE_URL");
      key = env.get("PDFCONVERSIONAPI_LOCAL_KEY");
      assumeTrue(isSet(baseUrl) && isSet(key), envFile + " lacks the base URL or the key");
    } else {
      baseUrl = System.getenv("PDFCONVERSIONAPI_LIVE_BASE_URL");
      key = System.getenv("PDFCONVERSIONAPI_KEY");
      assumeTrue(
          isSet(baseUrl) && isSet(key),
          "no " + envFile + ", and PDFCONVERSIONAPI_LIVE_BASE_URL or PDFCONVERSIONAPI_KEY is not"
              + " set: live tests skipped");
    }
    String samplesDir = System.getenv("PDFCONVERSIONAPI_SAMPLES_DIR");
    samples =
        (isSet(samplesDir) ? Path.of(samplesDir.trim()) : conformance.resolve("samples"))
            .toAbsolutePath()
            .normalize();
    assertTrue(Files.isDirectory(samples), "the live tests' sample files are missing: " + samples);
    client = PdfConversionApi.builder().apiKey(key).baseUrl(baseUrl).build();
  }

  private static boolean isSet(String value) {
    return value != null && !value.trim().isEmpty();
  }

  static Map<String, String> readEnv(Path file) throws IOException {
    Map<String, String> values = new HashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#")) {
        continue;
      }
      if (trimmed.startsWith("export ")) {
        trimmed = trimmed.substring(7).trim();
      }
      int eq = trimmed.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      String value = trimmed.substring(eq + 1).trim();
      if (value.length() >= 2
          && (value.startsWith("\"") && value.endsWith("\"") || value.startsWith("'") && value.endsWith("'"))) {
        value = value.substring(1, value.length() - 1);
      }
      values.put(trimmed.substring(0, eq).trim(), value);
    }
    return values;
  }

  private void assertNoKey(Throwable e) {
    assertFalse(String.valueOf(e.getMessage()).contains(key), "the key leaked into a message");
    assertFalse(e.toString().contains(key), "the key leaked into toString");
  }

  @Test
  void docxToPdf() throws IOException {
    ConversionResult result =
        client.convertFile(samples.resolve("docx-to-pdf/input-quarterly-report.docx"), "pdf");
    OutputFile pdf = result.getFile();
    assertEquals(1, result.getFiles().size());
    assertEquals("input-quarterly-report.pdf", pdf.getName());
    assertEquals("pdf", pdf.getExt());
    assertEquals(1, result.getConversionCost());
    assertNotNull(result.getRequestId());
    byte[] data = pdf.getData();
    assertEquals(pdf.getSize(), data.length);
    assertEquals("%PDF-", new String(data, 0, 5, StandardCharsets.US_ASCII));
    List<Path> written = result.saveAll(temp.resolve("docx"));
    assertEquals(List.of(temp.resolve("docx").resolve("input-quarterly-report.pdf")), written);
    assertArrayEquals(data, Files.readAllBytes(written.get(0)));
  }

  @Test
  void pdfToJpgTwoPages() {
    ConversionResult result =
        client.convert(
            "pdf", "jpg", samples.resolve("pdf-to-jpg/input-season-guide.pdf"), Map.of("ImageResolution", 72));
    assertEquals(2, result.getFiles().size());
    assertEquals("input-season-guide-1.jpg", result.getFiles().get(0).getName());
    assertEquals("input-season-guide-2.jpg", result.getFiles().get(1).getName());
    for (OutputFile jpg : result.getFiles()) {
      byte[] data = jpg.getData();
      assertEquals(jpg.getSize(), data.length);
      assertEquals(0xFF, data[0] & 0xFF);
      assertEquals(0xD8, data[1] & 0xFF);
    }
  }

  @Test
  void mergeThreePdfs() {
    Path dir = samples.resolve("merge");
    ConversionResult result =
        client.convert(
            "pdf",
            "merge",
            UploadFile.fromPaths(
                dir.resolve("input-invoice.pdf"), dir.resolve("input-terms.pdf"), dir.resolve("input-timesheet.pdf")),
            Map.of(),
            ConvertOptions.builder().fileName("october-billing").build());
    assertEquals(1, result.getFiles().size());
    assertEquals("october-billing.pdf", result.getFile().getName());
    assertTrue(result.getFile().getSize() > 50_000);
  }

  @Test
  void unicodeAndQuoteInFileName() throws IOException {
    byte[] docx = Files.readAllBytes(samples.resolve("docx-to-pdf/input-quarterly-report.docx"));
    ConversionResult result = client.convert("docx", "pdf", UploadFile.fromBytes(docx, "Résumé \"final\".docx"));
    assertEquals("Résumé _final_.pdf", result.getFile().getName());
  }

  @Test
  void storedFileDownloadAndDelete() throws IOException {
    ConversionResult result =
        client.convert(
            "pdf",
            "jpg",
            UploadFile.fromPath(samples.resolve("pdf-to-jpg/input-season-guide.pdf")),
            Map.of("ImageResolution", 72, "PageRange", "1"),
            ConvertOptions.builder().storeFile(true).build());
    OutputFile stored = result.getFile();
    assertTrue(stored.isStored());
    assertNotNull(stored.getUrl());
    assertTrue(stored.getFileId().matches("[0-9a-f]{32}"));
    assertThrows(StoredFileException.class, stored::getData);

    byte[] downloaded = stored.download();
    assertEquals(stored.getSize(), downloaded.length);
    Path saved = stored.save(temp);
    assertEquals(temp.resolve(stored.getName()), saved);
    assertArrayEquals(downloaded, Files.readAllBytes(saved));

    assertTrue(client.deleteStoredFile(stored.getFileId()));
    assertFalse(client.deleteStoredFile(stored.getFileId()));
    NotFoundException gone = assertThrows(NotFoundException.class, stored::download);
    assertEquals(404, gone.getStatus());
  }

  @Test
  void unsupportedPairIsNotFound() {
    NotFoundException e =
        assertThrows(
            NotFoundException.class,
            () -> client.convert("docx", "dwg", samples.resolve("docx-to-pdf/input-quarterly-report.docx")));
    assertEquals(404, e.getStatus());
    assertEquals("conversion from docx to dwg is not supported", e.getMessage());
    assertNotNull(e.getRequestId());
    assertNoKey(e);
  }

  @Test
  void wrongKeyIsAuthenticationError() {
    PdfConversionApi wrong = PdfConversionApi.builder().apiKey("wrong-key-0000000000").baseUrl(baseUrl).build();
    AuthenticationException e =
        assertThrows(
            AuthenticationException.class,
            () -> wrong.convertFile(samples.resolve("docx-to-pdf/input-quarterly-report.docx"), "pdf"));
    assertEquals(401, e.getStatus());
    assertTrue(e.getMessage().contains("valid API key"), e.getMessage());
    assertNoKey(e);
  }

  @Test
  void invalidPageRangeIsBadRequest() {
    BadRequestException e =
        assertThrows(
            BadRequestException.class,
            () ->
                client.convert(
                    "pdf", "jpg", samples.resolve("pdf-to-jpg/input-season-guide.pdf"), Map.of("PageRange", "3-1")));
    assertEquals(400, e.getStatus());
    assertEquals("PageRange '3-1' is not valid (example: 1-3,5)", e.getMessage());
    assertNoKey(e);
  }

  @Test
  void conversionsList() {
    List<Conversion> conversions = PdfConversionApi.builder().baseUrl(baseUrl).environment(n -> null).build().conversions();
    assertTrue(conversions.size() > 100, "only " + conversions.size() + " conversions");
    assertTrue(
        conversions.stream().anyMatch(c -> c.getFrom().equals("docx") && c.getTo().equals("pdf") && c.getInputs().equals("one")));
    assertTrue(conversions.stream().anyMatch(c -> c.getTo().equals("merge") && c.getInputs().equals("many")));
  }
}
