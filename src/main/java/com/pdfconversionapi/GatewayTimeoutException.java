package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 504: the conversion ran out of time on the server. Not retried: split the file or lower the resolution. */
public class GatewayTimeoutException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public GatewayTimeoutException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(504, message, requestId, retryAfter, body);
  }
}
