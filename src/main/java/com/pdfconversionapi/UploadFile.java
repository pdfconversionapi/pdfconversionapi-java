package com.pdfconversionapi;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A file to upload: its bytes and the name sent with them.
 *
 * <p>Make one from a {@linkplain #fromPath(Path) path}, from {@linkplain #fromBytes(byte[],
 * String) bytes in memory}, or from an {@linkplain #fromStream(InputStream, String) input
 * stream}. The API names its output after the upload, so {@code report.docx} comes back as
 * {@code report.pdf}; bytes and streams without a name are sent as {@code file}.
 *
 * <p>Only the file's own name is sent, never its folders. A path is read when the request is
 * sent, again for each retry. A stream is read to its end once, here, so retries can resend it.
 * Instances are immutable and can be reused across calls and threads; don't change a byte array
 * after passing it in.
 */
public final class UploadFile {
  /** The name sent for bytes or a stream without a name. */
  public static final String DEFAULT_NAME = "file";

  private final String name;
  private final @Nullable Path path;
  private final byte @Nullable [] bytes;

  private UploadFile(String name, @Nullable Path path, byte @Nullable [] bytes) {
    this.name = name;
    this.path = path;
    this.bytes = bytes;
  }

  /**
   * A file on disk, sent under its own name (the path's last part).
   *
   * @param path the file
   * @return the upload
   * @throws IllegalArgumentException if the path has no file name, such as a root folder
   */
  public static UploadFile fromPath(Path path) {
    Objects.requireNonNull(path, "path");
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException("the path " + path + " has no file name");
    }
    return new UploadFile(cleanName(fileName.toString()), path, null);
  }

  /**
   * A file on disk, sent under another name. The API reads the format of {@code file/to/pdf} and
   * similar conversions from the name, so this helps with a file saved without its extension.
   *
   * @param path the file
   * @param name the name to send; folders in it are dropped
   * @return the upload
   * @throws IllegalArgumentException if the name is empty
   */
  public static UploadFile fromPath(Path path, String name) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(name, "name");
    return new UploadFile(cleanName(name), path, null);
  }

  /**
   * Several files on disk, in this order, for a conversion that takes many, such as {@code
   * pdf/to/merge}.
   *
   * @param paths the files
   * @return one upload per path
   */
  public static List<UploadFile> fromPaths(Path... paths) {
    Objects.requireNonNull(paths, "paths");
    return fromPaths(java.util.Arrays.asList(paths));
  }

  /**
   * Several files on disk, in the collection's iteration order.
   *
   * @param paths the files
   * @return one upload per path
   */
  public static List<UploadFile> fromPaths(Collection<? extends Path> paths) {
    Objects.requireNonNull(paths, "paths");
    List<UploadFile> uploads = new ArrayList<>(paths.size());
    for (Path path : paths) {
      uploads.add(fromPath(path));
    }
    return Collections.unmodifiableList(uploads);
  }

  /**
   * Bytes in memory, sent as {@code file}.
   *
   * @param data the file's content; not copied, so don't change it while the upload is in use
   * @return the upload
   */
  public static UploadFile fromBytes(byte[] data) {
    return fromBytes(data, null);
  }

  /**
   * Bytes in memory with a name, such as {@code report.docx}.
   *
   * @param data the file's content; not copied, so don't change it while the upload is in use
   * @param name the name to send, or null for {@code file}; folders in it are dropped
   * @return the upload
   * @throws IllegalArgumentException if the name is empty
   */
  public static UploadFile fromBytes(byte[] data, @Nullable String name) {
    Objects.requireNonNull(data, "data");
    return new UploadFile(name == null ? DEFAULT_NAME : cleanName(name), null, data);
  }

  /**
   * A stream, sent as {@code file}. See {@link #fromStream(InputStream, String)}.
   *
   * @param stream the content; read to its end now, and not closed
   * @return the upload
   * @throws UncheckedIOException if reading the stream fails
   */
  public static UploadFile fromStream(InputStream stream) {
    return fromStream(stream, null);
  }

  /**
   * A stream with a name. The stream is read to its end now, into memory, so that retries can
   * send the same bytes again. It is not closed; that stays the caller's job.
   *
   * @param stream the content
   * @param name the name to send, or null for {@code file}; folders in it are dropped
   * @return the upload
   * @throws UncheckedIOException if reading the stream fails
   * @throws IllegalArgumentException if the name is empty
   */
  public static UploadFile fromStream(InputStream stream, @Nullable String name) {
    Objects.requireNonNull(stream, "stream");
    String cleaned = name == null ? DEFAULT_NAME : cleanName(name);
    try {
      return new UploadFile(cleaned, null, stream.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException("reading the stream for " + cleaned + " failed", e);
    }
  }

  /** Keeps only the last part of a name: anything up to the last '/' or '\' is a folder. */
  static String cleanName(String name) {
    int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    String last = name.substring(cut + 1);
    if (last.isEmpty()) {
      throw new IllegalArgumentException("the file name \"" + name + "\" is empty");
    }
    return last;
  }

  /**
   * The name sent with the file.
   *
   * @return the name, without folders
   */
  public String getName() {
    return name;
  }

  /**
   * The file on disk, when this upload was made from a path.
   *
   * @return the path, or null
   */
  public @Nullable Path getPath() {
    return path;
  }

  /** The bytes in memory, or null for a path. Not copied. */
  byte @Nullable [] bytes() {
    return bytes;
  }

  @Override
  public String toString() {
    if (path != null) {
      return "UploadFile{name=" + name + ", path=" + path + "}";
    }
    return "UploadFile{name=" + name + ", " + Objects.requireNonNull(bytes).length + " bytes}";
  }
}
