package com.pdfconversionapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One file in a {@link ConversionResult}. Immutable.
 *
 * <p>Usually it holds the file's data, decoded from the response. With {@code storeFile(true)} it
 * is <em>stored</em> instead: {@link #isStored()} is true, {@link #getUrl()} and {@link
 * #getFileId()} are set, and the data must be fetched with {@link #download()}, {@link
 * #downloadTo(Path)} or {@link #save(Path)}. The link works for 3 hours and needs no API key.
 */
public final class OutputFile {
  private final String name;
  private final String ext;
  private final long size;
  private final byte @Nullable [] data;
  private final @Nullable String url;
  private final @Nullable String fileId;
  private final PdfConversionApi client;

  OutputFile(
      String name,
      String ext,
      long size,
      byte @Nullable [] data,
      @Nullable String url,
      @Nullable String fileId,
      PdfConversionApi client) {
    this.name = name;
    this.ext = ext;
    this.size = size;
    this.data = data;
    this.url = url;
    this.fileId = fileId;
    this.client = client;
  }

  /**
   * The file's name with its extension, such as {@code season-guide-1.jpg}.
   *
   * @return the name
   */
  public String getName() {
    return name;
  }

  /**
   * The extension in lower case, without the dot, such as {@code jpg}.
   *
   * @return the extension
   */
  public String getExt() {
    return ext;
  }

  /**
   * The file's size in bytes.
   *
   * @return the size
   */
  public long getSize() {
    return size;
  }

  /**
   * Whether the file is stored behind a download link ({@code storeFile(true)}) rather than held
   * in memory.
   *
   * @return true for a stored file
   */
  public boolean isStored() {
    return data == null;
  }

  /**
   * The download link of a stored file. It works for 3 hours, needs no API key, and should be
   * treated like a password.
   *
   * @return the link, or null when the file isn't stored
   */
  public @Nullable String getUrl() {
    return url;
  }

  /**
   * The id of a stored file (32 hex characters), for {@link PdfConversionApi#deleteStoredFile}.
   *
   * @return the id, or null when the file isn't stored
   */
  public @Nullable String getFileId() {
    return fileId;
  }

  /**
   * The file's content. Returns a new copy on every call; {@link #save(Path)} writes the file
   * without one.
   *
   * @return the bytes
   * @throws StoredFileException if the file is stored: use {@link #download()} instead
   */
  public byte[] getData() {
    byte[] bytes = data;
    if (bytes == null) {
      throw new StoredFileException(name);
    }
    return bytes.clone();
  }

  /**
   * The file's content, downloaded first if the file is stored (without the API key).
   *
   * @return the bytes
   * @throws PdfConversionApiException if the download fails, such as {@link NotFoundException}
   *     once the link expired or the file was deleted
   */
  public byte[] download() {
    byte[] bytes = data;
    if (bytes != null) {
      return bytes.clone();
    }
    return client.download(Objects.requireNonNull(url));
  }

  /**
   * Writes the file; the same as {@link #save(Path)}.
   *
   * @param path the file to write, or an existing folder to write into under {@link #getName()}
   * @return the path written
   */
  public Path downloadTo(Path path) {
    return save(path);
  }

  /**
   * Writes the file to disk, replacing what is there. If {@code path} is an existing folder, the
   * file is written inside it under its own name. A stored file is downloaded and streamed to
   * disk. Missing parent folders are created.
   *
   * @param path the file to write, or an existing folder
   * @return the path written
   * @throws UncheckedIOException if writing fails
   * @throws InvalidResponseException if the file's name can't be used on disk
   */
  public Path save(Path path) {
    Objects.requireNonNull(path, "path");
    Path target = Files.isDirectory(path) ? FileNames.resolveInside(path, name) : path;
    write(target);
    return target;
  }

  /** Writes the file to exactly this path. */
  void write(Path target) {
    byte[] bytes = data;
    if (bytes == null) {
      client.downloadTo(Objects.requireNonNull(url), target);
      return;
    }
    try {
      FileNames.createParents(target);
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("writing " + target + " failed", e);
    }
  }

  @Override
  public String toString() {
    return "OutputFile{name=" + name + ", ext=" + ext + ", size=" + size
        + (data == null ? ", stored, fileId=" + fileId : "") + "}";
  }
}
