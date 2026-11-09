package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/**
 * The client's own timeout ran out while waiting for or reading a response. It is never retried:
 * the conversion may still be running on the server, and it counts toward the month if it
 * succeeds. Raise the limit with {@link PdfConversionApi.Builder#timeout(java.time.Duration)} or
 * {@link ConvertOptions.Builder#timeout(java.time.Duration)}.
 */
public class ApiTimeoutException extends PdfConversionApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the timeout exception, or null
   */
  public ApiTimeoutException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
