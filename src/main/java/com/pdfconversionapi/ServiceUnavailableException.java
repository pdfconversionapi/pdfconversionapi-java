package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 503: the servers are busy, or conversions on the plan are paused. Short waits are retried automatically; {@link #getRetryAfter()} says how long to wait. */
public class ServiceUnavailableException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public ServiceUnavailableException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(503, message, requestId, retryAfter, body);
  }
}
