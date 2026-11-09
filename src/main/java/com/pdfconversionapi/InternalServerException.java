package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 500: an unexpected failure on the API's side. Retried once automatically; quote {@link #getRequestId()} to support if it persists. */
public class InternalServerException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public InternalServerException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(500, message, requestId, retryAfter, body);
  }
}
