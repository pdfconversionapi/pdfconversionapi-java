/**
 * The official Java and Kotlin client for the <a href="https://pdfconversionapi.com/docs">PDF
 * Conversion API</a>.
 *
 * <p>Start with {@link com.pdfconversionapi.PdfConversionApi#builder()}:
 *
 * <pre>{@code
 * PdfConversionApi client = PdfConversionApi.builder().build();   // reads PDFCONVERSIONAPI_KEY
 * ConversionResult result = client.convertFile(Path.of("report.docx"), "pdf");
 * result.saveAll(Path.of("out"));                                  // writes out/report.pdf
 * }</pre>
 *
 * <p>Every type in this package is non-null unless marked {@code @Nullable}. Every exception the
 * client throws is unchecked: {@link com.pdfconversionapi.PdfConversionApiException} and its
 * subclasses for API and network problems, {@link IllegalArgumentException} for bad arguments,
 * and {@link java.io.UncheckedIOException} for local file errors.
 */
@NullMarked
package com.pdfconversionapi;

import org.jspecify.annotations.NullMarked;
