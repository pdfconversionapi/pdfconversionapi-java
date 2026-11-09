package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/** HTTP 404: the conversion pair isn't supported, or a stored file's link expired or was deleted. Not retried. */
public class NotFoundException extends ApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message the API's message
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public NotFoundException(
      String message, @Nullable String requestId, @Nullable Long retryAfter, String body) {
    super(404, message, requestId, retryAfter, body);
  }
}
