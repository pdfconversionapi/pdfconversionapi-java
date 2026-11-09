package com.pdfconversionapi;

/**
 * A call that needs an API key was made on a client without one. Nothing was sent.
 *
 * <p>Pass the key to {@link PdfConversionApi.Builder#apiKey(String)} or set the {@code
 * PDFCONVERSIONAPI_KEY} environment variable. {@link PdfConversionApi#conversions()} and
 * downloads work without a key.
 */
public class MissingApiKeyException extends PdfConversionApiException {
  private static final long serialVersionUID = 1L;

  /** Creates the exception with the standard message. */
  public MissingApiKeyException() {
    super(
        "no API key: pass one to PdfConversionApi.builder().apiKey(...) or set the"
            + " PDFCONVERSIONAPI_KEY environment variable (get a key at"
            + " https://app.pdfconversionapi.com)");
  }
}
