package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs every case in conformance/cases.json against conformance/mock-server.mjs. The case format
 * is described in conformance/README.md.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.MethodName.class)
class ConformanceTest {
  private static final Set<String> CALLS =
      Set.of("convert", "usage", "conversions", "delete_stored_file", "stored_file_flow");

  private Path dir;
  private Path fixtures;
  private Map<?, ?> suite;
  private MockServer mock;
  private final Set<String> ran = Collections.synchronizedSet(new TreeSet<>());

  @TempDir Path temp;

  @BeforeAll
  void start() throws Exception {
    dir = MockServer.conformanceDir();
    fixtures = dir.resolve("fixtures");
    suite = (Map<?, ?>) Json.parse(Files.readAllBytes(dir.resolve("cases.json")));
    mock = MockServer.start();
  }

  @AfterAll
  void stop() throws Exception {
    if (mock != null) {
      mock.close();
    }
  }

  private List<Map<?, ?>> cases() {
    List<Map<?, ?>> cases = new ArrayList<>();
    for (Object c : (List<?>) suite.get("cases")) {
      cases.add((Map<?, ?>) c);
    }
    return cases;
  }

  @TestFactory
  Stream<DynamicTest> a_cases() {
    return cases().stream()
        .map(c -> DynamicTest.dynamicTest(str(c, "id"), () -> {
          run(c);
          ran.add(str(c, "id"));
        }));
  }

  /** Runs after the cases (methods run in name order), to check that every case ran and passed. */
  @Test
  void z_everyCaseRan() {
    Set<String> ids = new TreeSet<>();
    for (Map<?, ?> c : cases()) {
      ids.add(str(c, "id"));
      assertTrue(CALLS.contains(str(c, "call")), "unknown call in case " + str(c, "id"));
    }
    assertEquals(ids, ran, "every conformance case must run and pass");
  }

  // -------------------------------------------------------------------------------------------

  private void run(Map<?, ?> c) throws Exception {
    String id = str(c, "id");
    Map<?, ?> expect = (Map<?, ?>) c.get("expect");
    List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    PdfConversionApi client = client(map(c, "client"), sleeps);
    String mockId = id + "-" + UUID.randomUUID();
    String call = str(c, "call");

    Object outcome;
    try {
      switch (call) {
        case "convert":
        case "stored_file_flow":
          outcome = convert(client, c, mockId);
          break;
        case "usage":
          outcome = client.usage();
          break;
        case "conversions":
          outcome = client.conversions();
          break;
        case "delete_stored_file":
          outcome = client.deleteStoredFile(str(c, "file_id"));
          break;
        default:
          throw new AssertionError("unknown call " + call);
      }
    } catch (RuntimeException e) {
      outcome = e;
    }

    Map<?, ?> error = map(expect, "error");
    if (error != null) {
      if (!(outcome instanceof RuntimeException)) {
        fail(id + ": expected " + str(error, "type") + " but the call returned " + outcome);
      }
      checkError(id, error, (RuntimeException) outcome);
    } else if (outcome instanceof RuntimeException) {
      throw new AssertionError(id + ": unexpected exception " + outcome, (RuntimeException) outcome);
    }

    if (expect.containsKey("attempts")) {
      assertEquals(num(expect, "attempts"), mock.attempts(mockId), id + ": attempts");
    }
    if (expect.containsKey("min_sleeps")) {
      List<?> min = (List<?>) expect.get("min_sleeps");
      assertEquals(min.size(), sleeps.size(), id + ": number of waits " + sleeps);
      for (int i = 0; i < min.size(); i++) {
        double atLeast = ((Json.Num) min.get(i)).doubleValue();
        double waited = sleeps.get(i).toNanos() / 1e9;
        assertTrue(waited >= atLeast, id + ": wait " + i + " was " + waited + "s, expected at least " + atLeast);
      }
    }

    if (outcome instanceof ConversionResult) {
      ConversionResult result = (ConversionResult) outcome;
      Map<?, ?> expected = map(expect, "result");
      if (expected != null) {
        checkResult(id, expected, result);
      }
      Map<?, ?> echo = map(expect, "echo");
      if (echo != null) {
        checkEcho(id, echo, result);
      }
      Map<?, ?> saveAll = map(expect, "save_all");
      if (saveAll != null) {
        checkSaveAll(id, saveAll, result);
      }
      if (call.equals("stored_file_flow")) {
        storedFileFlow(id, expect, client, result);
      }
    }
    if (outcome instanceof Usage) {
      checkUsage(id, map(expect, "usage"), (Usage) outcome);
    }
    if (outcome instanceof List) {
      Map<?, ?> conversions = map(expect, "conversions");
      @SuppressWarnings("unchecked")
      List<Conversion> list = (List<Conversion>) outcome;
      assertEquals(num(conversions, "count"), list.size(), id + ": count");
      Map<?, ?> first = map(conversions, "first");
      Conversion c0 = list.get(0);
      assertEquals(str(first, "from"), c0.getFrom());
      assertEquals(str(first, "to"), c0.getTo());
      assertEquals(str(first, "title"), c0.getTitle());
      assertEquals(str(first, "category"), c0.getCategory());
      assertEquals(str(first, "inputs"), c0.getInputs());
    }
    if (outcome instanceof Boolean) {
      assertEquals(expect.get("deleted"), outcome, id + ": deleted");
    }
  }

  private PdfConversionApi client(@Nullable Map<?, ?> config, List<Duration> sleeps) {
    String key = str(suite, "key");
    String suffix = "";
    PdfConversionApi.Builder builder =
        PdfConversionApi.builder().sleeper(sleeps::add).environment(name -> null);
    if (config != null) {
      if (config.containsKey("api_key")) {
        Object k = config.get("api_key");
        key = k == null ? null : k.toString();
      }
      if (config.containsKey("base_url_suffix")) {
        suffix = str(config, "base_url_suffix");
      }
      if (config.containsKey("max_retries")) {
        builder.maxRetries((int) num(config, "max_retries"));
      }
      if (config.containsKey("timeout_seconds")) {
        builder.timeout(Duration.ofMillis((long) (((Json.Num) config.get("timeout_seconds")).doubleValue() * 1000)));
      }
    }
    return builder.apiKey(key).baseUrl(mock.url() + suffix).build();
  }

  private ConversionResult convert(PdfConversionApi client, Map<?, ?> c, String mockId) throws IOException {
    Map<String, Object> params = new LinkedHashMap<>();
    Map<?, ?> given = map(c, "params");
    if (given != null) {
      for (Map.Entry<?, ?> e : given.entrySet()) {
        Object value = e.getValue();
        if (value instanceof Map) {
          value = upload((Map<?, ?>) value);
        } else if (value instanceof Json.Str) {
          value = value.toString();
        } else if (value instanceof Json.Num) {
          Json.Num n = (Json.Num) value;
          value = n.isIntegral() ? (Object) n.longValue() : (Object) n.doubleValue();
        }
        params.put(e.getKey().toString(), value);
      }
    }
    params.put("MockId", mockId);

    ConvertOptions.Builder options = ConvertOptions.builder();
    Map<?, ?> opts = map(c, "options");
    if (opts != null) {
      if (opts.containsKey("file_name")) {
        options.fileName(str(opts, "file_name"));
      }
      if (opts.containsKey("store_file")) {
        options.storeFile((Boolean) opts.get("store_file"));
      }
    }

    String from = str(c, "from");
    String to = str(c, "to");
    if (!c.containsKey("files")) {
      return client.convert(from, to, params, options.build());
    }
    List<?> files = (List<?>) c.get("files");
    if (files.size() == 1) {
      return client.convert(from, to, upload((Map<?, ?>) files.get(0)), params, options.build());
    }
    List<UploadFile> uploads = new ArrayList<>();
    for (Object f : files) {
      uploads.add(upload((Map<?, ?>) f));
    }
    return client.convert(from, to, uploads, params, options.build());
  }

  private UploadFile upload(Map<?, ?> d) throws IOException {
    String name = d.containsKey("name") ? str(d, "name") : null;
    if (d.containsKey("path")) {
      return UploadFile.fromPath(fixtures.resolve(str(d, "path")));
    }
    if (d.containsKey("bytes")) {
      byte[] data = Files.readAllBytes(fixtures.resolve(str(d, "bytes")));
      return name == null ? UploadFile.fromBytes(data) : UploadFile.fromBytes(data, name);
    }
    if (d.containsKey("stream")) {
      byte[] data = Files.readAllBytes(fixtures.resolve(str(d, "stream")));
      return name == null
          ? UploadFile.fromStream(new ByteArrayInputStream(data))
          : UploadFile.fromStream(new ByteArrayInputStream(data), name);
    }
    if (d.containsKey("bytes_utf8")) {
      byte[] data = str(d, "bytes_utf8").getBytes(StandardCharsets.UTF_8);
      return name == null ? UploadFile.fromBytes(data) : UploadFile.fromBytes(data, name);
    }
    throw new AssertionError("unknown input descriptor " + d);
  }

  // -------------------------------------------------------------------------------------------

  private static Class<? extends RuntimeException> errorType(String canonical) {
    switch (canonical) {
      case "ArgumentError":
        return IllegalArgumentException.class;
      case "PdfConversionApiError":
        return PdfConversionApiException.class;
      case "MissingApiKeyError":
        return MissingApiKeyException.class;
      case "StoredFileError":
        return StoredFileException.class;
      case "ConnectionError":
        return ApiConnectionException.class;
      case "TimeoutError":
        return ApiTimeoutException.class;
      case "InvalidResponseError":
        return InvalidResponseException.class;
      case "ApiError":
        return ApiException.class;
      case "BadRequestError":
        return BadRequestException.class;
      case "AuthenticationError":
        return AuthenticationException.class;
      case "PermissionDeniedError":
        return PermissionDeniedException.class;
      case "NotFoundError":
        return NotFoundException.class;
      case "PayloadTooLargeError":
        return PayloadTooLargeException.class;
      case "UnprocessableEntityError":
        return UnprocessableEntityException.class;
      case "RateLimitError":
        return RateLimitException.class;
      case "InternalServerError":
        return InternalServerException.class;
      case "ServiceUnavailableError":
        return ServiceUnavailableException.class;
      case "GatewayTimeoutError":
        return GatewayTimeoutException.class;
      default:
        throw new AssertionError("unknown error type " + canonical);
    }
  }

  private void checkError(String id, Map<?, ?> expected, RuntimeException actual) {
    Class<? extends RuntimeException> type = errorType(str(expected, "type"));
    if (!type.isInstance(actual)) {
      throw new AssertionError(id + ": expected " + type.getSimpleName() + " but got " + actual, actual);
    }
    String key = str(suite, "key");
    assertFalse(String.valueOf(actual.getMessage()).contains(key), id + ": the key leaked into the message");
    assertFalse(actual.toString().contains(key), id + ": the key leaked into toString");
    if (expected.containsKey("message")) {
      assertEquals(str(expected, "message"), actual.getMessage(), id + ": message");
    }
    if (expected.containsKey("message_contains")) {
      assertTrue(actual.getMessage().contains(str(expected, "message_contains")), id + ": message " + actual.getMessage());
    }
    if (actual instanceof ApiException) {
      ApiException api = (ApiException) actual;
      if (expected.containsKey("status")) {
        assertEquals(num(expected, "status"), api.getStatus(), id + ": status");
      }
      if (expected.containsKey("retry_after")) {
        assertEquals(Long.valueOf(num(expected, "retry_after")), api.getRetryAfter(), id + ": retry_after");
      }
      if (expected.containsKey("request_id")) {
        if (Boolean.TRUE.equals(expected.get("request_id"))) {
          assertNotNull(api.getRequestId(), id + ": request_id");
          assertFalse(api.getRequestId().isEmpty(), id + ": request_id");
          assertTrue(api.toString().contains("(request id: " + api.getRequestId() + ")"), id + ": toString");
        } else {
          assertNull(api.getRequestId(), id + ": request_id");
        }
      }
      assertTrue(api.toString().contains("HTTP " + api.getStatus() + ": " + api.getMessage()), id + ": toString");
    } else {
      for (String k : new String[] {"status", "retry_after", "request_id"}) {
        assertFalse(expected.containsKey(k), id + ": " + k + " expected on a non-API error");
      }
    }
  }

  private void checkResult(String id, Map<?, ?> expected, ConversionResult result) throws IOException {
    if (expected.containsKey("conversion_cost")) {
      assertEquals(num(expected, "conversion_cost"), result.getConversionCost(), id + ": conversion_cost");
    }
    if (Boolean.TRUE.equals(expected.get("request_id"))) {
      assertNotNull(result.getRequestId(), id + ": request_id");
      assertFalse(result.getRequestId().isEmpty());
    }
    List<?> files = (List<?>) expected.get("files");
    if (files == null) {
      return;
    }
    assertEquals(files.size(), result.getFiles().size(), id + ": number of files");
    assertTrue(result.getFile() == result.getFiles().get(0));
    for (int i = 0; i < files.size(); i++) {
      Map<?, ?> f = (Map<?, ?>) files.get(i);
      OutputFile out = result.getFiles().get(i);
      if (f.containsKey("name")) {
        assertEquals(str(f, "name"), out.getName(), id + ": name");
      }
      if (f.containsKey("ext")) {
        assertEquals(str(f, "ext"), out.getExt(), id + ": ext");
      }
      if (f.containsKey("size")) {
        assertEquals(num(f, "size"), out.getSize(), id + ": size");
      }
      if (f.containsKey("data_fixture")) {
        assertArrayEquals(Files.readAllBytes(fixtures.resolve(str(f, "data_fixture"))), out.getData(), id + ": data");
      }
      if (f.containsKey("data_utf8")) {
        assertEquals(str(f, "data_utf8"), new String(out.getData(), StandardCharsets.UTF_8), id + ": data");
      }
      if (Boolean.TRUE.equals(f.get("stored"))) {
        assertTrue(out.isStored(), id + ": stored");
        assertNotNull(out.getUrl(), id + ": url");
        assertNotNull(out.getFileId(), id + ": file_id");
        assertThrows(StoredFileException.class, out::getData, id + ": data of a stored file");
      } else {
        assertFalse(out.isStored(), id + ": stored");
        assertNull(out.getUrl());
        assertNull(out.getFileId());
      }
    }
  }

  private void checkEcho(String id, Map<?, ?> expected, ConversionResult result) throws Exception {
    Map<?, ?> echo = (Map<?, ?>) Json.parse(result.getFile().download());
    Map<?, ?> headers = (Map<?, ?>) echo.get("headers");
    for (String k : new String[] {"method", "path", "query"}) {
      if (expected.containsKey(k)) {
        assertEquals(str(expected, k), str(echo, k), id + ": " + k);
      }
    }
    if (expected.containsKey("authorization")) {
      assertEquals(str(expected, "authorization"), str(headers, "authorization"), id + ": authorization");
    }
    if (expected.containsKey("user_agent_prefix")) {
      String ua = str(headers, "user-agent");
      assertTrue(ua.startsWith(str(expected, "user_agent_prefix")), id + ": user-agent " + ua);
      assertTrue(ua.startsWith("pdfconversionapi-java/" + PdfConversionApi.VERSION + " java/"), id + ": user-agent " + ua);
    }
    if (expected.containsKey("content_type_prefix")) {
      assertTrue(str(headers, "content-type").startsWith(str(expected, "content_type_prefix")), id + ": content-type");
    }
    assertEquals("application/json", str(headers, "accept"), id + ": accept");

    List<?> fields = (List<?>) echo.get("fields");
    List<?> wanted = (List<?>) expected.get("fields");
    if (wanted != null) {
      int from = 0;
      for (Object w : wanted) {
        Map<?, ?> want = (Map<?, ?>) w;
        int found = -1;
        for (int i = from; i < fields.size(); i++) {
          if (matches(want, (Map<?, ?>) fields.get(i))) {
            found = i;
            break;
          }
        }
        assertTrue(found >= 0, id + ": field " + want + " not found in order in " + fields);
        from = found + 1;
      }
    }
    List<?> absent = (List<?>) expected.get("absent_fields");
    if (absent != null) {
      for (Object name : absent) {
        for (Object f : fields) {
          assertFalse(name.toString().equals(str((Map<?, ?>) f, "name")), id + ": field " + name + " must be absent");
        }
      }
    }
  }

  private static boolean matches(Map<?, ?> want, Map<?, ?> field) {
    for (Map.Entry<?, ?> e : want.entrySet()) {
      Object actual = field.get(e.getKey());
      if (actual == null || !actual.toString().equals(e.getValue().toString())) {
        return false;
      }
    }
    return true;
  }

  private void checkSaveAll(String id, Map<?, ?> expected, ConversionResult result) throws IOException {
    Path out = temp.resolve("save-all-" + id);
    List<Path> written = result.saveAll(out);
    assertEquals(expected.size(), written.size(), id + ": paths written");
    Set<String> names = new TreeSet<>();
    try (Stream<Path> listing = Files.list(out)) {
      listing.forEach(p -> names.add(p.getFileName().toString()));
    }
    Set<String> wantedNames = new TreeSet<>();
    for (Object k : expected.keySet()) {
      wantedNames.add(k.toString());
    }
    assertEquals(wantedNames, names, id + ": files in the folder");
    for (Map.Entry<?, ?> e : expected.entrySet()) {
      assertEquals(e.getValue().toString(), Files.readString(out.resolve(e.getKey().toString())), id + ": " + e.getKey());
    }
  }

  private void storedFileFlow(String id, Map<?, ?> expect, PdfConversionApi client, ConversionResult result) throws IOException {
    OutputFile file = result.getFile();
    assertThrows(errorType(str(expect, "data_access_error")), file::getData, id + ": data access");
    byte[] fixture = Files.readAllBytes(fixtures.resolve(str(expect, "download_fixture")));
    assertArrayEquals(fixture, file.download(), id + ": download()");
    // The same file streamed to disk, by the file and by the client, into a folder and to a path.
    Path folder = Files.createDirectories(temp.resolve("stored-" + id));
    assertArrayEquals(fixture, Files.readAllBytes(file.save(folder)), id + ": save(folder)");
    Path direct = folder.resolve("direct.bin");
    assertEquals(direct, client.downloadTo(file.getUrl(), direct));
    assertArrayEquals(fixture, Files.readAllBytes(direct), id + ": downloadTo");

    assertEquals(expect.get("first_delete"), client.deleteStoredFile(file.getFileId()), id + ": first delete");
    assertEquals(expect.get("second_delete"), client.deleteStoredFile(file.getFileId()), id + ": second delete");
    RuntimeException after = assertThrows(RuntimeException.class, file::download, id + ": download after delete");
    assertTrue(errorType(str(expect, "download_after_delete_error")).isInstance(after), id + ": " + after);
    // A failed download to disk leaves nothing behind, not even a partial file.
    Path missing = folder.resolve("after-delete.bin");
    assertThrows(NotFoundException.class, () -> client.downloadTo(file.getUrl(), missing));
    assertFalse(Files.exists(missing));
    try (Stream<Path> listing = Files.list(folder)) {
      assertTrue(listing.noneMatch(p -> p.getFileName().toString().endsWith(".part")), id + ": leftover .part file");
    }
  }

  private void checkUsage(String id, Map<?, ?> expected, Usage usage) {
    assertEquals(str(expected, "plan"), usage.getPlan(), id);
    assertEquals(num(expected, "included"), usage.getIncluded(), id);
    assertEquals(num(expected, "used"), usage.getUsed(), id);
    assertEquals(Long.valueOf(num(expected, "remaining")), usage.getRemaining(), id);
    assertEquals(expected.get("overage"), usage.isOverage(), id);
    assertEquals(num(expected, "running"), usage.getRunning(), id);
    assertEquals(num(expected, "concurrent_limit"), usage.getConcurrentLimit(), id);
    assertEquals(num(expected, "max_upload_mb"), usage.getMaxUploadMb(), id);
    assertEquals(instant(str(expected, "period_start")), usage.getPeriodStart(), id);
    assertEquals(instant(str(expected, "period_end")), usage.getPeriodEnd(), id);
  }

  // -------------------------------------------------------------------------------------------

  private static Instant instant(String text) {
    return OffsetDateTime.parse(text).toInstant();
  }

  private static @Nullable Map<?, ?> map(@Nullable Map<?, ?> m, String key) {
    return m == null ? null : (Map<?, ?>) m.get(key);
  }

  private static String str(Map<?, ?> m, String key) {
    Object value = m.get(key);
    if (value == null) {
      throw new AssertionError("missing " + key + " in " + m);
    }
    return value.toString();
  }

  private static long num(Map<?, ?> m, String key) {
    return ((Json.Num) m.get(key)).longValue();
  }
}
