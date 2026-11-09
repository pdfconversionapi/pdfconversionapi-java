package com.pdfconversionapi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class UploadFileTest {
  @Test
  void pathsSendTheirLastPart() {
    UploadFile file = UploadFile.fromPath(Path.of("some", "folder", "report.docx"));
    assertEquals("report.docx", file.getName());
    assertEquals(Path.of("some", "folder", "report.docx"), file.getPath());
    assertNull(file.bytes());
    assertEquals("other.docx", UploadFile.fromPath(Path.of("x"), "dir/other.docx").getName());
    assertThrows(IllegalArgumentException.class, () -> UploadFile.fromPath(Path.of("/")));
  }

  @Test
  void bytesAndStreams() {
    byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
    assertEquals("file", UploadFile.fromBytes(data).getName());
    assertEquals("file", UploadFile.fromBytes(data, null).getName());
    assertEquals("notes.txt", UploadFile.fromBytes(data, "notes.txt").getName());
    assertEquals("x.txt", UploadFile.fromBytes(data, "C:\\Users\\me\\x.txt").getName());
    assertEquals("x.txt", UploadFile.fromBytes(data, "../../x.txt").getName());
    assertThrows(IllegalArgumentException.class, () -> UploadFile.fromBytes(data, ""));
    assertThrows(IllegalArgumentException.class, () -> UploadFile.fromBytes(data, "folder/"));

    UploadFile streamed = UploadFile.fromStream(new ByteArrayInputStream(data), "s.txt");
    assertEquals("s.txt", streamed.getName());
    assertArrayEquals(data, streamed.bytes());
    assertEquals("file", UploadFile.fromStream(new ByteArrayInputStream(data)).getName());
    assertTrue(streamed.toString().contains("3 bytes"));
  }

  @Test
  void aFailingStreamIsAnIoError() {
    InputStream broken =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("boom");
          }
        };
    assertThrows(UncheckedIOException.class, () -> UploadFile.fromStream(broken, "x"));
  }

  @Test
  void severalPaths() {
    List<UploadFile> files = UploadFile.fromPaths(Path.of("a.pdf"), Path.of("dir/b.pdf"));
    assertEquals(2, files.size());
    assertEquals("a.pdf", files.get(0).getName());
    assertEquals("b.pdf", files.get(1).getName());
    assertEquals(1, UploadFile.fromPaths(List.of(Path.of("c.pdf"))).size());
    assertThrows(UnsupportedOperationException.class, () -> files.add(UploadFile.fromBytes(new byte[0])));
  }
}
