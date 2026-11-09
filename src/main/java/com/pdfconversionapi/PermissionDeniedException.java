package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 403: the key's plan isn't set up; write to support. Not retried. */
public class PermissionDeniedException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public PermissionDeniedException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(403, message, requestId, retryAfter, body);
  }
}
