package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/**
 * A successful status came with a body the client can't use: not JSON, no {@code Files}, file
 * data that isn't base64, or data whose decoded size differs from {@code FileSize}.
 */
public class InvalidResponseException extends PdfConversionApiException {
  private static final long serialVersionUID = 1L;

  /** The {@code x-request-id} header, or null. */
  private final @Nullable String requestId;

  /**
   * Creates the exception.
   *
   * @param message what was wrong with the response
   * @param requestId the response's {@code x-request-id}, or null
   * @param cause the parsing exception, or null
   */
  public InvalidResponseException(
      String message, @Nullable String requestId, @Nullable Throwable cause) {
    super(message, cause);
    this.requestId = requestId;
  }

  /**
   * The response's {@code x-request-id} header, to quote when you write to support.
   *
   * @return the request id, or null when the response had none
   */
  public @Nullable String getRequestId() {
    return requestId;
  }

  @Override
  public String toString() {
    String text = getClass().getName() + ": " + getMessage();
    return requestId == null ? text : text + " (request id: " + requestId + ")";
  }
}
