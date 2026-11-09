package com.pdfconversionapi;

import java.net.http.HttpHeaders;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Turns an error response into the right {@link ApiException}. */
final class ApiErrors {
  /** A text/plain body up to this many characters becomes the message. */
  static final int MAX_TEXT_MESSAGE = 1000;

  private ApiErrors() {}

  static @Nullable String requestId(HttpHeaders headers) {
    String id = headers.firstValue("x-request-id").orElse(null);
    return id == null || id.trim().isEmpty() ? null : id.trim();
  }

  /**
   * The Retry-After header in whole seconds, or null when it is missing or unreadable. A number
   * too large for a long is read as {@link Long#MAX_VALUE}.
   */
  static @Nullable Long parseRetryAfter(@Nullable String header) {
    if (header == null) {
      return null;
    }
    String value = header.trim();
    if (value.isEmpty()) {
      return null;
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return Long.MAX_VALUE;
    }
  }

  /** The wait a 429 or 503 asks for: the Retry-After header, or 1 second without a usable one. */
  static long retryAfterOrDefault(HttpHeaders headers) {
    Long seconds = parseRetryAfter(headers.firstValue("retry-after").orElse(null));
    return seconds == null ? 1 : seconds;
  }

  static ApiException fromResponse(int status, HttpHeaders headers, byte[] body) {
    String contentType = headers.firstValue("content-type").orElse(null);
    String text = decode(body, contentType);
    String message = message(contentType, body, text);
    String requestId = requestId(headers);
    Long retryAfter =
        status == 429 || status == 503
            ? Long.valueOf(retryAfterOrDefault(headers))
            : parseRetryAfter(headers.firstValue("retry-after").orElse(null));
    switch (status) {
      case 400:
        return new BadRequestException(message, requestId, retryAfter, text);
      case 401:
        return new AuthenticationException(message, requestId, retryAfter, text);
      case 403:
        return new PermissionDeniedException(message, requestId, retryAfter, text);
      case 404:
        return new NotFoundException(message, requestId, retryAfter, text);
      case 413:
        return new PayloadTooLargeException(message, requestId, retryAfter, text);
      case 422:
        return new UnprocessableEntityException(message, requestId, retryAfter, text);
      case 429:
        return new RateLimitException(message, requestId, retryAfter, text);
      case 500:
        return new InternalServerException(message, requestId, retryAfter, text);
      case 503:
        return new ServiceUnavailableException(message, requestId, retryAfter, text);
      case 504:
        return new GatewayTimeoutException(message, requestId, retryAfter, text);
      default:
        return new ApiException(status, message, requestId, retryAfter, text);
    }
  }

  /**
   * The message of an error response: the JSON body's {@code Message}; {@code empty response};
   * a short text/plain body, trimmed; otherwise {@code non-JSON response (<content type>)}.
   */
  static String message(@Nullable String contentType, byte[] body, String text) {
    if (text.trim().isEmpty()) {
      return "empty response";
    }
    try {
      Object json = Json.parse(body);
      if (json instanceof Map) {
        Object message = ((Map<?, ?>) json).get("Message");
        if (message instanceof Json.Str) {
          return message.toString();
        }
      }
    } catch (Json.JsonException e) {
      // Not JSON: fall through to the content type.
    }
    String mediaType = mediaType(contentType);
    if (mediaType != null && mediaType.toLowerCase(Locale.ROOT).equals("text/plain")
        && text.codePointCount(0, text.length()) <= MAX_TEXT_MESSAGE) {
      return text.trim();
    }
    return "non-JSON response (" + (mediaType == null ? "no content type" : mediaType) + ")";
  }

  /** The content type without its parameters, or null when there is none. */
  static @Nullable String mediaType(@Nullable String contentType) {
    if (contentType == null) {
      return null;
    }
    int semicolon = contentType.indexOf(';');
    String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim();
    return type.isEmpty() ? null : type;
  }

  /** The body as text, in the content type's charset when it names one, else UTF-8. */
  static String decode(byte[] body, @Nullable String contentType) {
    Charset charset = StandardCharsets.UTF_8;
    if (contentType != null) {
      for (String param : contentType.split(";")) {
        String p = param.trim();
        if (p.regionMatches(true, 0, "charset=", 0, 8)) {
          String name = p.substring(8).trim().replace("\"", "");
          try {
            charset = Charset.forName(name);
          } catch (RuntimeException e) {
            charset = StandardCharsets.UTF_8;
          }
        }
      }
    }
    return new String(body, charset);
  }
}
