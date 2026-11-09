package com.pdfconversionapi;

import org.jspecify.annotations.Nullable;

/**
 * The API answered with an error status. Subclasses name the common statuses ({@link
 * BadRequestException} for 400, {@link RateLimitException} for 429 and so on); any other status
 * is thrown as a plain {@code ApiException}.
 *
 * <p>{@link #getMessage()} is the API's own message, such as {@code PageRange '3-1' is not valid
 * (example: 1-3,5)}. {@link #toString()} adds the status and the request id: {@code HTTP 400:
 * PageRange '3-1' is not valid (example: 1-3,5) (request id: 1a2b...)}. Branch on the class or
 * {@link #getStatus()}, not on the message text, which can change.
 */
public class ApiException extends PdfConversionApiException {
  private static final long serialVersionUID = 1L;

  /** The HTTP status. */
  private final int status;

  /** The {@code x-request-id} header, or null. */
  private final @Nullable String requestId;

  /** The {@code Retry-After} wait in seconds, or null. */
  private final @Nullable Long retryAfter;

  /** The raw response body as text. */
  private final String body;

  /**
   * Creates the exception.
   *
   * @param status the HTTP status
   * @param message the API's message (see the class description)
   * @param requestId the {@code x-request-id} header, or null
   * @param retryAfter the {@code Retry-After} header in seconds, or null
   * @param body the raw response body as text, possibly empty
   */
  public ApiException(
      int status,
      String message,
      @Nullable String requestId,
      @Nullable Long retryAfter,
      String body) {
    super(message);
    this.status = status;
    this.requestId = requestId;
    this.retryAfter = retryAfter;
    this.body = body;
  }

  /**
   * The HTTP status code, such as 400 or 429.
   *
   * @return the status
   */
  public int getStatus() {
    return status;
  }

  /**
   * The response's {@code x-request-id} header. Send it to support with any question about a
   * failed request. Responses from the edge network in front of the API (an HTML 413 or 5xx page)
   * have none.
   *
   * @return the request id, or null
   */
  public @Nullable String getRequestId() {
    return requestId;
  }

  /**
   * How many seconds to wait before trying again, from the {@code Retry-After} header. Always set
   * on {@link RateLimitException} and {@link ServiceUnavailableException} (1 when the header was
   * missing or unreadable); otherwise set only when the response had the header.
   *
   * @return the wait in seconds, or null
   */
  public @Nullable Long getRetryAfter() {
    return retryAfter;
  }

  /**
   * The raw response body as text, for debugging. Empty when the response had no body.
   *
   * @return the body
   */
  public String getBody() {
    return body;
  }

  @Override
  public String toString() {
    String text = getClass().getName() + ": HTTP " + status + ": " + getMessage();
    return requestId == null ? text : text + " (request id: " + requestId + ")";
  }
}
