package com.pdfconversionapi;

import static com.pdfconversionapi.ScriptedServer.error;
import static com.pdfconversionapi.ScriptedServer.json;
import static com.pdfconversionapi.ScriptedServer.ok;
import static com.pdfconversionapi.ScriptedServer.reply;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ClientTest {
  private static final String KEY = "sk_test_0123456789abcdef";

  private ScriptedServer server;
  private final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());

  @TempDir Path temp;

  @BeforeEach
  void start() throws Exception {
    server = new ScriptedServer();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private PdfConversionApi.Builder builder() {
    return PdfConversionApi.builder()
        .apiKey(KEY)
        .baseUrl(server.url())
        .sleeper(sleeps::add)
        .environment(name -> null);
  }

  private PdfConversionApi client() {
    return builder().build();
  }

  private static UploadFile hello() {
    return UploadFile.fromBytes("hello".getBytes(StandardCharsets.UTF_8), "hello.txt");
  }

  // ------------------------------------------------------------------------------- requests

  @Test
  void convertSendsTheDocumentedRequest() {
    server.then(ok("hello.pdf", "converted"));
    ConversionResult result = client().convert("TXT", "Pdf", hello(), Map.of("PageRange", "1-3"));
    assertEquals("converted", new String(result.getFile().getData(), StandardCharsets.UTF_8));
    assertEquals("req-200", result.getRequestId());
    assertEquals(0.012, result.getConversionTime());

    ScriptedServer.Request request = server.requests().get(0);
    assertEquals("POST", request.method);
    assertEquals("/v2/convert/txt/to/pdf", request.path);
    assertNull(request.query);
    assertEquals("Bearer " + KEY, request.header("Authorization"));
    assertEquals("application/json", request.header("Accept"));
    assertTrue(request.header("User-Agent").matches("pdfconversionapi-java/0\\.1\\.0 java/\\S+"), request.header("User-Agent"));
    assertTrue(request.header("Content-Type").startsWith("multipart/form-data; boundary=pdfconversionapi-"));
    assertEquals(String.valueOf(request.body.length), request.header("Content-Length"));
    String body = new String(request.body, StandardCharsets.UTF_8);
    assertTrue(body.contains("name=\"File\"; filename=\"hello.txt\""), body);
    assertTrue(body.contains("name=\"PageRange\"\r\n\r\n1-3\r\n"), body);
  }

  @Test
  void downloadsAndConversionsSendNoKey() {
    server.then(reply(200).body("file bytes"), json(200, "[]"), reply(200).body("again"));
    PdfConversionApi client = client();
    assertArrayEquals("file bytes".getBytes(StandardCharsets.UTF_8), client.download(server.url() + "/v2/d/abc/x.pdf"));
    assertEquals(List.of(), client.conversions());
    client.downloadTo(server.url() + "/v2/d/abc/x.pdf", temp.resolve("x.pdf"));
    for (ScriptedServer.Request request : server.requests()) {
      assertNull(request.header("Authorization"), request.path);
      assertNotNull(request.header("User-Agent"));
    }
    assertEquals("application/json", server.requests().get(1).header("Accept"));
    assertEquals("/v2/info", server.requests().get(1).path);
  }

  @Test
  void usageIsParsed() {
    server.then(
        json(
            200,
            "{\"Plan\":\"Pro\",\"Included\":10000,\"Used\":12,\"Remaining\":null,\"Overage\":false,\"Running\":2,"
                + "\"ConcurrentLimit\":4,\"MaxUploadMB\":100,\"PeriodStart\":\"2026-11-01T00:00:00.000Z\","
                + "\"PeriodEnd\":\"2026-12-01T00:00:00+00:00\"}"));
    Usage usage = client().usage();
    assertEquals("Pro", usage.getPlan());
    assertEquals(10000, usage.getIncluded());
    assertEquals(12, usage.getUsed());
    assertNull(usage.getRemaining());
    assertFalse(usage.isOverage());
    assertEquals(2, usage.getRunning());
    assertEquals(4, usage.getConcurrentLimit());
    assertEquals(100, usage.getMaxUploadMb());
    assertEquals(Instant.parse("2026-11-01T00:00:00Z"), usage.getPeriodStart());
    assertEquals(Instant.parse("2026-12-01T00:00:00Z"), usage.getPeriodEnd());
    assertEquals("Bearer " + KEY, server.requests().get(0).header("Authorization"));
    assertEquals("/v2/usage", server.requests().get(0).path);
  }

  @Test
  void deleteStoredFile() {
    server.then(reply(204), error(404, "not found"), error(401, "a valid API key is required"));
    PdfConversionApi client = client();
    String id = "0123456789abcdef0123456789ABCDEF";
    assertTrue(client.deleteStoredFile(id));
    assertFalse(client.deleteStoredFile(id));
    assertThrows(AuthenticationException.class, () -> client.deleteStoredFile(id));
    assertEquals("DELETE", server.requests().get(0).method);
    assertEquals("/v2/d/" + id, server.requests().get(0).path);
    assertEquals("Bearer " + KEY, server.requests().get(0).header("Authorization"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "abc", "0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdefa", "0123456789abcdef0123456789abcdeg", "../../etc/passwd/0123456789abcd"})
  void deleteRefusesBadIds(String id) {
    assertThrows(IllegalArgumentException.class, () -> client().deleteStoredFile(id));
    assertTrue(server.requests().isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "a/b", "../etc", "pdf?x=1", "abcdefghijklmnopqrstu", "pdf ", "pdf.v2", "pdfé"})
  void formatsAreValidatedBeforeSending(String format) {
    assertThrows(IllegalArgumentException.class, () -> client().convert(format, "pdf", hello()));
    assertThrows(IllegalArgumentException.class, () -> client().convert("pdf", format, hello()));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void formatsMayUseTheirFullRange() {
    server.then(ok("a.pdf", "x"), ok("a.pdf", "x"));
    client().convert("abcdefghijklmnopqrst", "a-1", hello());
    assertEquals("/v2/convert/abcdefghijklmnopqrst/to/a-1", server.requests().get(0).path);
  }

  @Test
  void convertFileReadsTheFormatFromTheName() throws Exception {
    server.then(ok("Report.pdf", "pdf"));
    Path docx = Files.writeString(temp.resolve("Report.DOCX"), "docx bytes");
    ConversionResult result = client().convertFile(docx, "pdf");
    assertEquals("Report.pdf", result.getFile().getName());
    assertEquals("/v2/convert/docx/to/pdf", server.requests().get(0).path);
    String body = new String(server.requests().get(0).body, StandardCharsets.UTF_8);
    assertTrue(body.contains("filename=\"Report.DOCX\"\r\n"), body);
    assertTrue(body.contains("\r\n\r\ndocx bytes\r\n"), body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"report", ".bashrc", "report.", "report.formatnamethatistoolong"})
  void convertFileNeedsAnExtension(String name) throws Exception {
    Path file = Files.writeString(temp.resolve(name), "x");
    assertThrows(IllegalArgumentException.class, () -> client().convertFile(file, "pdf"));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void aMissingInputFileFailsBeforeSending() {
    Path missing = temp.resolve("missing.pdf");
    assertThrows(UncheckedIOException.class, () -> client().convertFile(missing, "jpg"));
    assertThrows(UncheckedIOException.class, () -> client().convert("pdf", "png", Map.of("ImageFile", missing)));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void parameterNamesMustNotBeNull() {
    Map<String, Object> params = new java.util.HashMap<>();
    params.put(null, "x");
    assertThrows(IllegalArgumentException.class, () -> client().convert("txt", "pdf", hello(), params));
    assertThrows(IllegalArgumentException.class, () -> client().convert("txt", "pdf", Collections.singletonList(null)));
  }

  @Test
  void missingKeyFailsBeforeSending() {
    PdfConversionApi client = builder().apiKey(null).build();
    assertFalse(client.hasApiKey());
    assertThrows(MissingApiKeyException.class, () -> client.convert("txt", "pdf", hello()));
    assertThrows(MissingApiKeyException.class, client::usage);
    assertThrows(MissingApiKeyException.class, () -> client.deleteStoredFile("0123456789abcdef0123456789abcdef"));
    assertTrue(server.requests().isEmpty());
    server.then(json(200, "[]"));
    assertEquals(List.of(), client.conversions());
  }

  // ------------------------------------------------------------------------------- retries

  @Test
  void retryAfterIsHonoredWithJitter() {
    server.then(
        error(429, "too many requests; slow down").header("Retry-After", "0"),
        error(503, "busy"), // no Retry-After: 1 second
        error(503, "busy").header("Retry-After", "soon"), // unreadable: 1 second
        error(429, "slow down").header("Retry-After", "3"),
        ok("a.pdf", "x"));
    client().convert("txt", "pdf", hello());
    assertEquals(5, server.requests().size());
    assertEquals(4, sleeps.size());
    assertBetween(sleeps.get(0), 0, 2);
    assertBetween(sleeps.get(1), 1, 1 + 4);
    assertBetween(sleeps.get(2), 1, 1 + 8);
    assertBetween(sleeps.get(3), 3, 3 + 16);
  }

  @Test
  void aLongRetryAfterIsRaisedAtOnce() {
    server.then(error(429, "used up").header("Retry-After", "61"));
    RateLimitException e = assertThrows(RateLimitException.class, () -> client().convert("txt", "pdf", hello()));
    assertEquals(61L, e.getRetryAfter());
    assertEquals(1, server.requests().size());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void maxRetryWaitCanBeRaised() {
    server.then(error(503, "paused").header("Retry-After", "61"), ok("a.pdf", "x"));
    builder().maxRetryWait(Duration.ofSeconds(120)).build().convert("txt", "pdf", hello());
    assertEquals(2, server.requests().size());
    assertBetween(sleeps.get(0), 61, 63);
  }

  @Test
  void aMissingRetryAfterIsReportedAsOne() {
    server.then(error(503, "busy"));
    ServiceUnavailableException e =
        assertThrows(ServiceUnavailableException.class, () -> builder().maxRetries(0).build().convert("txt", "pdf", hello()));
    assertEquals(1L, e.getRetryAfter());
    assertEquals(1, server.requests().size());
  }

  @Test
  void retriesStopAfterMaxRetries() {
    for (int i = 0; i < 3; i++) {
      server.then(error(503, "busy").header("Retry-After", "1"));
    }
    assertThrows(ServiceUnavailableException.class, () -> builder().maxRetries(2).build().convert("txt", "pdf", hello()));
    assertEquals(3, server.requests().size());
    assertEquals(2, sleeps.size());
  }

  @Test
  void aServerErrorIsRetriedOncePerCall() {
    server.then(error(500, "internal error"), error(503, "busy").header("Retry-After", "1"), error(500, "internal error"));
    InternalServerException e = assertThrows(InternalServerException.class, () -> client().convert("txt", "pdf", hello()));
    assertEquals("internal error", e.getMessage());
    assertEquals(3, server.requests().size());
    assertBetween(sleeps.get(0), 1, 3);
  }

  @ParameterizedTest
  @ValueSource(ints = {400, 401, 403, 404, 413, 422, 502, 504})
  void otherErrorsAreNotRetried(int status) {
    server.then(error(status, "nope"));
    ApiException e = assertThrows(ApiException.class, () -> client().convert("txt", "pdf", hello()));
    assertEquals(status, e.getStatus());
    assertEquals(1, server.requests().size());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void downloadsAreRetriedToo() {
    server.then(error(503, "busy").header("Retry-After", "1"), reply(200).body("ok"));
    assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), client().download(server.url() + "/v2/d/x/y"));
    assertEquals(2, server.requests().size());
  }

  @Test
  void refusedConnectionsAreRetriedWithGrowingJitter() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    PdfConversionApi client = builder().baseUrl("http://127.0.0.1:" + port).build();
    ApiConnectionException e = assertThrows(ApiConnectionException.class, () -> client.convert("txt", "pdf", hello()));
    assertTrue(e.getMessage().contains("after 5 attempts"), e.getMessage());
    assertFalse(e.getMessage().contains(KEY));
    assertEquals(4, sleeps.size());
    for (int i = 0; i < 4; i++) {
      assertBetween(sleeps.get(i), 0, Math.pow(2, i + 1));
    }
  }

  @Test
  void aClientTimeoutIsNotRetried() {
    server.then(ok("a.pdf", "x").delay(3000), ok("a.pdf", "x"));
    PdfConversionApi client = builder().timeout(Duration.ofMillis(300)).build();
    long start = System.nanoTime();
    ApiTimeoutException e = assertThrows(ApiTimeoutException.class, () -> client.convert("txt", "pdf", hello()));
    assertTrue((System.nanoTime() - start) / 1e9 < 2.5, "the timeout took too long");
    assertTrue(e.getMessage().contains("may still be running"), e.getMessage());
    assertEquals(1, server.requests().size());
    assertTrue(sleeps.isEmpty());
  }

  @Test
  void aPerCallTimeoutWins() {
    server.then(ok("a.pdf", "x").delay(3000));
    ConvertOptions options = ConvertOptions.builder().timeout(Duration.ofMillis(300)).build();
    assertThrows(ApiTimeoutException.class, () -> client().convert("txt", "pdf", hello(), null, options));
  }

  @Test
  void anInterruptStopsTheRetries() {
    server.then(error(503, "busy").header("Retry-After", "1"));
    PdfConversionApi client =
        builder()
            .sleeper(
                d -> {
                  throw new InterruptedException();
                })
            .build();
    PdfConversionApiException e = assertThrows(PdfConversionApiException.class, () -> client.convert("txt", "pdf", hello()));
    assertTrue(Thread.interrupted(), "the interrupt flag is kept");
    assertTrue(e.getMessage().contains("interrupted"));
  }

  @Test
  void jitterStaysInItsRange() {
    for (int attempt = 1; attempt <= 6; attempt++) {
      for (int i = 0; i < 200; i++) {
        assertBetween(PdfConversionApi.jitter(attempt), 0, Math.pow(2, attempt));
      }
    }
    assertBetween(PdfConversionApi.jitter(1000), 0, Math.pow(2, 30));
  }

  // ------------------------------------------------------------------------------- downloads

  @Test
  void downloadToReplacesTheFileOnlyWhenComplete() throws Exception {
    Path target = temp.resolve("sub/dir/out.pdf");
    server.then(reply(200).body("new content"), error(404, "not found"));
    PdfConversionApi client = client();
    assertEquals(target, client.downloadTo(server.url() + "/v2/d/a/out.pdf", target));
    assertEquals("new content", Files.readString(target));
    if (target.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      Path plain = Files.writeString(temp.resolve("plain.txt"), "x");
      assertEquals(
          Files.getPosixFilePermissions(plain), Files.getPosixFilePermissions(target), "same permissions as a plain write");
    }

    NotFoundException e = assertThrows(NotFoundException.class, () -> client.downloadTo(server.url() + "/v2/d/a/out.pdf", target));
    assertEquals("not found", e.getMessage());
    assertEquals("new content", Files.readString(target), "a failed download leaves the old file alone");
    try (Stream<Path> files = Files.list(target.getParent())) {
      assertEquals(List.of(target), files.collect(java.util.stream.Collectors.toList()));
    }
  }

  @Test
  void downloadToRefusesAFolderAndBadUrls() {
    assertThrows(IllegalArgumentException.class, () -> client().downloadTo(server.url() + "/v2/d/a/b", temp));
    assertThrows(IllegalArgumentException.class, () -> client().download("not a url"));
    assertThrows(IllegalArgumentException.class, () -> client().download("/v2/d/a/b"));
    assertThrows(IllegalArgumentException.class, () -> client().download("ftp://example.com/x"));
    assertTrue(server.requests().isEmpty());
  }

  @Test
  void storedOutputsAreDownloadedWhenSaved() throws Exception {
    String url = server.url() + "/v2/d/0123456789abcdef0123456789abcdef/page%201.jpg";
    server.then(
        json(
            200,
            "{\"ConversionCost\":1,\"ConversionTime\":0.1,\"Files\":[{\"FileName\":\"page 1.jpg\",\"FileExt\":\"jpg\","
                + "\"FileSize\":4,\"Url\":\"" + url + "\",\"FileId\":\"0123456789abcdef0123456789abcdef\"}]}"),
        reply(200).body("jpg1"),
        reply(200).body("jpg1"),
        reply(200).body("jpg1"));
    ConversionResult result = client().convert("pdf", "jpg", hello(), null, ConvertOptions.builder().storeFile(true).build());
    OutputFile file = result.getFile();
    assertTrue(file.isStored());
    assertThrows(StoredFileException.class, file::getData);
    assertEquals(List.of(temp.resolve("all/page 1.jpg")), result.saveAll(temp.resolve("all")));
    assertEquals("jpg1", Files.readString(temp.resolve("all/page 1.jpg")));
    assertEquals(temp.resolve("page 1.jpg"), file.save(temp));
    assertArrayEquals("jpg1".getBytes(StandardCharsets.UTF_8), file.download());
    for (ScriptedServer.Request request : server.requests().subList(1, 4)) {
      assertEquals("/v2/d/0123456789abcdef0123456789abcdef/page%201.jpg", request.path);
      assertNull(request.header("Authorization"));
    }
  }

  @Test
  void inlineOutputsAreSavedWithoutDownloading() throws Exception {
    server.then(ok("report.pdf", "pdf bytes"));
    OutputFile file = client().convert("txt", "pdf", hello()).getFile();
    assertEquals(temp.resolve("report.pdf"), file.save(temp));
    Path explicit = temp.resolve("nested/name.pdf");
    assertEquals(explicit, file.downloadTo(explicit));
    assertEquals("pdf bytes", Files.readString(explicit));
    byte[] data = file.getData();
    data[0] = 'X';
    assertEquals('p', file.getData()[0], "getData returns a copy");
    assertEquals(1, server.requests().size());
  }

  // ------------------------------------------------------------------------------- builder

  @Test
  void theEnvironmentSuppliesTheKeyAndTheBaseUrl() {
    server.then(ok("a.pdf", "x"));
    Map<String, String> env = Map.of("PDFCONVERSIONAPI_KEY", " env-key-0123456789 \n", "PDFCONVERSIONAPI_BASE_URL", server.url() + "/");
    PdfConversionApi client = PdfConversionApi.builder().environment(env::get).sleeper(sleeps::add).build();
    assertEquals(server.url(), client.getBaseUrl());
    assertTrue(client.hasApiKey());
    client.convert("txt", "pdf", hello());
    assertEquals("Bearer env-key-0123456789", server.requests().get(0).header("Authorization"));

    PdfConversionApi explicit = PdfConversionApi.builder().environment(env::get).apiKey("other").baseUrl("https://example.com").build();
    assertEquals("https://example.com", explicit.getBaseUrl());

    PdfConversionApi defaults = PdfConversionApi.builder().environment(name -> null).build();
    assertEquals("https://api.pdfconversionapi.com", defaults.getBaseUrl());
    assertFalse(defaults.hasApiKey());
    assertFalse(PdfConversionApi.builder().environment(name -> "  ").build().hasApiKey());
  }

  @ParameterizedTest
  @ValueSource(strings = {"api.pdfconversionapi.com", "ftp://x.com", "https://", "https://user:pass@x.com", "https://x.com/?a=1", "https://x.com/#f", "http://exa mple.com"})
  void badBaseUrlsAreRefused(String url) {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> PdfConversionApi.builder().baseUrl(url).environment(n -> null).build());
    assertFalse(e.getMessage().contains("user:pass"), "the message must not echo credentials");
  }

  @Test
  void baseUrlsMayHaveAPathAndTrailingSlashes() {
    assertEquals("https://x.com/api", PdfConversionApi.builder().baseUrl("https://x.com/api//").environment(n -> null).build().getBaseUrl());
  }

  @Test
  void malformedKeysAreRefusedWithoutEchoingThem() {
    for (String key : new String[] {"abc def", "abc\u0000def", "abcé"}) {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> PdfConversionApi.builder().apiKey(key).build());
      assertFalse(e.getMessage().contains(key), e.getMessage());
    }
  }

  @Test
  void builderArgumentsAreChecked() {
    PdfConversionApi.Builder b = PdfConversionApi.builder();
    assertThrows(IllegalArgumentException.class, () -> b.maxRetries(-1));
    assertThrows(IllegalArgumentException.class, () -> b.timeout(Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> b.connectTimeout(Duration.ofSeconds(-1)));
    assertThrows(IllegalArgumentException.class, () -> b.maxRetryWait(Duration.ofSeconds(-1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> b.httpClient(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()));
    b.httpClient(HttpClient.newHttpClient());
    b.maxRetryWait(Duration.ZERO);
    assertThrows(IllegalArgumentException.class, () -> ConvertOptions.builder().timeout(Duration.ZERO));
  }

  @Test
  void aCustomHttpClientIsUsed() {
    server.then(ok("a.pdf", "x"));
    builder().httpClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()).build().convert("txt", "pdf", hello());
    assertEquals(1, server.requests().size());
  }

  @Test
  void theKeyNeverShowsInToString() {
    PdfConversionApi client = client();
    assertFalse(client.toString().contains(KEY), client.toString());
    assertTrue(client.toString().contains("apiKey=set"));
    assertFalse(builder().toString().contains(KEY));
  }

  @Test
  void theVersionMatchesThePom() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));
    assertTrue(pom.contains("<artifactId>pdfconversionapi</artifactId>\n  <version>" + PdfConversionApi.VERSION + "</version>"), "pom version");
  }

  @Test
  void optionsAreImmutableAndHaveDefaults() {
    ConvertOptions defaults = ConvertOptions.builder().build();
    assertSame(ConvertOptions.defaults(), defaults);
    ConvertOptions a = ConvertOptions.builder().fileName("x").build();
    ConvertOptions b = a.toBuilder().storeFile(true).build();
    assertNull(a.getStoreFile());
    assertEquals("x", b.getFileName());
    assertEquals(Boolean.TRUE, b.getStoreFile());
    assertInstanceOf(String.class, b.toString());
  }

  private static void assertBetween(Duration d, double lowSeconds, double highSeconds) {
    double s = d.toNanos() / 1e9;
    assertTrue(s >= lowSeconds && s < highSeconds, s + "s is outside [" + lowSeconds + ", " + highSeconds + ")");
  }
}
