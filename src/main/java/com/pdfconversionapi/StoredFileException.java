package com.pdfconversionapi;

/**
 * The data of a stored file was read. A file converted with {@code storeFile(true)} comes back as
 * a download link, not as data: call {@link OutputFile#download()}, {@link
 * OutputFile#downloadTo(java.nio.file.Path)} or {@link OutputFile#save(java.nio.file.Path)}.
 */
public class StoredFileException extends PdfConversionApiException {
  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception for one stored file.
   *
   * @param fileName the stored file's name
   */
  public StoredFileException(String fileName) {
    super(
        fileName
            + " is stored behind a download link (StoreFile=true), so the response has no data for"
            + " it; call download(), downloadTo(path) or save(path) instead");
  }
}
