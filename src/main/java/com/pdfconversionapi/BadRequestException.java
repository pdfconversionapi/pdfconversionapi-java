package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 400: the request is wrong, for example a parameter value the conversion doesn't accept, or two files sent to a conversion that takes one. Not retried. */
public class BadRequestException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public BadRequestException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(400, message, requestId, retryAfter, body);
  }
}
