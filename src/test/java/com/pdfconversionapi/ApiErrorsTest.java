package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiErrorsTest {
  private static HttpHeaders headers(String... pairs) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], List.of(pairs[i + 1]));
    }
    return HttpHeaders.of(map, (a, b) -> true);
  }

  private static ApiException error(int status, String body, String... headerPairs) {
    return ApiErrors.fromResponse(status, headers(headerPairs), body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void statusesMapToClasses() {
    Map<Integer, Class<?>> expected = new LinkedHashMap<>();
    expected.put(400, BadRequestException.class);
    expected.put(401, AuthenticationException.class);
    expected.put(403, PermissionDeniedException.class);
    expected.put(404, NotFoundException.class);
    expected.put(413, PayloadTooLargeException.class);
    expected.put(422, UnprocessableEntityException.class);
    expected.put(429, RateLimitException.class);
    expected.put(500, InternalServerException.class);
    expected.put(503, ServiceUnavailableException.class);
    expected.put(504, GatewayTimeoutException.class);
    expected.put(402, ApiException.class);
    expected.put(409, ApiException.class);
    expected.put(502, ApiException.class);
    expected.put(301, ApiException.class);
    expected.forEach(
        (status, type) -> {
          ApiException e = error(status, "{\"Code\":" + status + ",\"Message\":\"m\"}");
          assertSame(type, e.getClass(), "status " + status);
          assertEquals(status, e.getStatus());
          assertTrue(e instanceof PdfConversionApiException);
        });
  }

  @Test
  void theJsonMessageIsKeptAsItIs() {
    ApiException e =
        error(400, "{\"Code\":400,\"Message\":\"PageRange is not supported yet for DOCX \\u2192 PDF  \"}", "x-request-id", "abc");
    assertEquals("PageRange is not supported yet for DOCX → PDF  ", e.getMessage());
    assertEquals("abc", e.getRequestId());
    assertEquals("{\"Code\":400,\"Message\":\"PageRange is not supported yet for DOCX \\u2192 PDF  \"}", e.getBody());
    assertNull(e.getRetryAfter());
  }

  @Test
  void anEmptyBodySaysSo() {
    assertEquals("empty response", error(504, "", "content-type", "application/json").getMessage());
    assertEquals("empty response", error(502, "  \n").getMessage());
  }

  @Test
  void shortPlainTextBecomesTheMessage() {
    assertEquals(
        "Invalid `boundary` for `multipart/form-data` request",
        error(400, "Invalid `boundary` for `multipart/form-data` request\n", "content-type", "text/plain; charset=utf-8").getMessage());
    assertEquals("x".repeat(1000), error(400, "x".repeat(1000), "content-type", "Text/Plain").getMessage());
    assertEquals("non-JSON response (text/plain)", error(400, "x".repeat(1001), "content-type", "text/plain").getMessage());
  }

  @Test
  void otherBodiesNameTheirContentType() {
    ApiException html = error(413, "<html><body><h1>413 Request Entity Too Large</h1></body></html>", "content-type", "text/html; charset=UTF-8");
    assertEquals("non-JSON response (text/html)", html.getMessage());
    assertNull(html.getRequestId());
    assertTrue(html.getBody().startsWith("<html>"));
    assertEquals("non-JSON response (no content type)", error(502, "<html>bad gateway</html>").getMessage());
    assertEquals("non-JSON response (application/json)", error(500, "{not json", "content-type", "application/json").getMessage());
    assertEquals("non-JSON response (application/json)", error(500, "{\"Message\":5}", "content-type", "application/json").getMessage());
    assertEquals("non-JSON response (application/json)", error(500, "[\"Message\"]", "content-type", "application/json").getMessage());
  }

  @Test
  void retryAfter() {
    assertEquals(10L, error(429, "{}", "retry-after", "10").getRetryAfter());
    assertEquals(1L, error(429, "{}").getRetryAfter(), "missing on a 429 means 1");
    assertEquals(1L, error(503, "{}", "retry-after", "Wed, 21 Oct 2026 07:28:00 GMT").getRetryAfter(), "unreadable means 1");
    assertEquals(2_000_000L, error(429, "{}", "retry-after", " 2000000 ").getRetryAfter());
    assertNull(error(400, "{}").getRetryAfter());
    assertEquals(5L, error(400, "{}", "retry-after", "5").getRetryAfter(), "reported when present");

    assertEquals(0L, ApiErrors.parseRetryAfter("0"));
    assertNull(ApiErrors.parseRetryAfter("-1"));
    assertNull(ApiErrors.parseRetryAfter("1.5"));
    assertNull(ApiErrors.parseRetryAfter(""));
    assertNull(ApiErrors.parseRetryAfter(null));
    assertEquals(Long.MAX_VALUE, ApiErrors.parseRetryAfter("99999999999999999999999"));
  }

  @Test
  void toStringAddsTheStatusAndTheRequestId() {
    ApiException e = error(429, "{\"Code\":429,\"Message\":\"too many requests; slow down\"}", "x-request-id", "1a2b");
    assertEquals("com.pdfconversionapi.RateLimitException: HTTP 429: too many requests; slow down (request id: 1a2b)", e.toString());
    ApiException noId = error(413, "<html></html>", "content-type", "text/html");
    assertEquals("com.pdfconversionapi.PayloadTooLargeException: HTTP 413: non-JSON response (text/html)", noId.toString());
    InvalidResponseException invalid = new InvalidResponseException("bad", "r1", null);
    assertEquals("com.pdfconversionapi.InvalidResponseException: bad (request id: r1)", invalid.toString());
  }

  @Test
  void blankRequestIdsAreNull() {
    assertNull(error(400, "{}", "x-request-id", "  ").getRequestId());
  }

  @Test
  void theCharsetIsHonored() {
    byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
    ApiException e = ApiErrors.fromResponse(400, headers("content-type", "text/plain; charset=ISO-8859-1"), latin1);
    assertEquals("café", e.getMessage());
    ApiException unknown = ApiErrors.fromResponse(400, headers("content-type", "text/plain; charset=nope"), "ok".getBytes(StandardCharsets.UTF_8));
    assertEquals("ok", unknown.getMessage());
  }

  @Test
  void otherExceptionsHaveUsefulMessages() {
    assertTrue(new MissingApiKeyException().getMessage().contains("PDFCONVERSIONAPI_KEY"));
    assertTrue(new StoredFileException("a.pdf").getMessage().contains("download()"));
    assertFalse(new StoredFileException("a.pdf").getMessage().isEmpty());
  }
}
