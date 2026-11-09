package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 429: the account's rate limit, its conversions at once, or the month's conversions were reached. Short waits are retried automatically; {@link #getRetryAfter()} says how long to wait. */
public class RateLimitException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public RateLimitException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(429, message, requestId, retryAfter, body);
  }
}
