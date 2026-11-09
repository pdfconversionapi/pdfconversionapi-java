package com.pdfconversionapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What a conversion returned: one or more files, in the API's order. Immutable.
 *
 * <p>The whole response is held in memory. For very large outputs, convert with {@code
 * storeFile(true)} and stream each file to disk with {@link OutputFile#save(Path)}.
 */
public final class ConversionResult {
  private final List<OutputFile> files;
  private final int conversionCost;
  private final double conversionTime;
  private final @Nullable String requestId;

  ConversionResult(
      List<OutputFile> files, int conversionCost, double conversionTime, @Nullable String requestId) {
    this.files = Collections.unmodifiableList(new ArrayList<>(files));
    this.conversionCost = conversionCost;
    this.conversionTime = conversionTime;
    this.requestId = requestId;
  }

  /**
   * Every output file, in the API's order: page images by page, split parts by part.
   *
   * @return an unmodifiable list with at least one file
   */
  public List<OutputFile> getFiles() {
    return files;
  }

  /**
   * The first output file: the only one for most conversions.
   *
   * @return the first file
   */
  public OutputFile getFile() {
    return files.get(0);
  }

  /**
   * Conversions counted for this request: always 1, however many files came back.
   *
   * @return the cost
   */
  public int getConversionCost() {
    return conversionCost;
  }

  /**
   * Seconds the conversion took on the server, not counting the upload, the download or waiting
   * for a free slot.
   *
   * @return the time in seconds
   */
  public double getConversionTime() {
    return conversionTime;
  }

  /**
   * The response's {@code x-request-id} header, for your logs and for support.
   *
   * @return the request id, or null when the response had none
   */
  public @Nullable String getRequestId() {
    return requestId;
  }

  /**
   * Writes every file into a folder under its own name, replacing files that are there. Creates
   * the folder if needed. Stored files are downloaded first.
   *
   * @param dir the folder
   * @return the paths written, in the same order as {@link #getFiles()}
   * @throws UncheckedIOException if writing fails
   */
  public List<Path> saveAll(Path dir) {
    Objects.requireNonNull(dir, "dir");
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("can't create the folder " + dir, e);
    }
    List<Path> written = new ArrayList<>(files.size());
    for (OutputFile file : files) {
      Path target = FileNames.resolveInside(dir, file.getName());
      file.write(target);
      written.add(target);
    }
    return Collections.unmodifiableList(written);
  }

  @Override
  public String toString() {
    return "ConversionResult{files=" + files + ", conversionCost=" + conversionCost
        + ", conversionTime=" + conversionTime + ", requestId=" + requestId + "}";
  }
}
