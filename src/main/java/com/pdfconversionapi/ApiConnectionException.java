package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/**
 * No usable response came back: the connection was refused, reset or timed out while opening, or
 * it dropped before or while the response arrived.
 *
 * <p>Failures before any response are retried with backoff first, so this is thrown once the
 * retries are used up. A connection that drops after the response started is thrown at once,
 * because the conversion may already have run and counted.
 */
public class ApiConnectionException extends PdfConversionApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the network exception, or null
   */
  public ApiConnectionException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
