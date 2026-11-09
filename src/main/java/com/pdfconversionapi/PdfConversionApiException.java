package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/**
 * The base of every exception this client throws for API and network problems.
 *
 * <p>Catch it to handle every failure of a call in one place. Bad arguments throw {@link
 * IllegalArgumentException} instead, and local file errors (a missing input, a folder you can't
 * write to) throw {@link java.io.UncheckedIOException}. No message ever contains the API key.
 */
public class PdfConversionApiException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message.
   *
   * @param message what went wrong
   */
  public PdfConversionApiException(String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and the exception that caused it.
   *
   * @param message what went wrong
   * @param cause the underlying exception, or null
   */
  public PdfConversionApiException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
