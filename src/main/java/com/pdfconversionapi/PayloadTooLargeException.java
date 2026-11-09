package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 413: the request, or an image or page in it, is over a limit. Send less data. Not retried. */
public class PayloadTooLargeException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public PayloadTooLargeException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(413, message, requestId, retryAfter, body);
  }
}
