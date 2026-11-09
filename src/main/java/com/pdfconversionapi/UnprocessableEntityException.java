package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 422: the file can't be read or converted as asked, for example it is password-protected. Not retried. */
public class UnprocessableEntityException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public UnprocessableEntityException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(422, message, requestId, retryAfter, body);
  }
}
