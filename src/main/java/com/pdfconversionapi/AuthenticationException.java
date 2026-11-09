package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 401: the API key is missing, unknown, or no longer valid. Not retried. */
public class AuthenticationException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public AuthenticationException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(401, message, requestId, retryAfter, body);
  }
}
