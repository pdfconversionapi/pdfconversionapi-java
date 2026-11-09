package com.pdfconversionapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Turning names from the API into safe paths on disk. */
final class FileNames {
  private FileNames() {}

  /**
   * The part of a name that may be used on disk: anything up to the last '/' or '\' is dropped,
   * and empty names, {@code .}, {@code ..} and names with a NUL are refused. The API never sends
   * such names, but a client must not trust that.
   *
   * @throws InvalidResponseException if nothing usable is left
   */
  static String safeName(String name) {
    int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    String last = name.substring(cut + 1);
    if (last.isEmpty() || last.equals(".") || last.equals("..") || last.indexOf('\0') >= 0) {
      throw new InvalidResponseException(
          "the output file name \"" + name + "\" can't be used as a file name on disk", null, null);
    }
    return last;
  }

  /**
   * {@code dir/name} for a name from the API, checked to stay inside {@code dir}.
   *
   * @throws InvalidResponseException if the name can't be used on disk
   */
  static Path resolveInside(Path dir, String name) {
    String safe = safeName(name);
    Path target;
    try {
      target = dir.resolve(safe);
    } catch (InvalidPathException e) {
      throw new InvalidResponseException(
          "the output file name \"" + name + "\" can't be used as a file name on disk", null, e);
    }
    Path parent = target.getParent();
    if (parent == null || !parent.normalize().equals(dir.normalize()) || target.isAbsolute() != dir.isAbsolute()) {
      throw new InvalidResponseException(
          "the output file name \"" + name + "\" can't be used as a file name on disk", null, null);
    }
    return target;
  }

  /** Creates the folders above a file, if there are any to create. */
  static void createParents(Path file) throws IOException {
    Path parent = file.toAbsolutePath().getParent();
    if (parent != null && !Files.isDirectory(parent)) {
      Files.createDirectories(parent);
    }
  }
}
