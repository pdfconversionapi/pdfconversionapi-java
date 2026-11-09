package com.pdfconversionapi

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * How the library reads from Kotlin: getters as properties, JSpecify nullability (String vs
 * String?), SAM conversion for the sleeper, and maps and lists from the Kotlin standard library.
 * Runs against conformance/mock-server.mjs. Compiled only with -Pkotlin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KotlinUsageTest {
    private lateinit var mock: Process
    private lateinit var baseUrl: String
    private lateinit var fixtures: Path

    @TempDir
    lateinit var temp: Path

    @BeforeAll
    fun start() {
        // Same lookup as the Java tests: -Dconformance.dir, PDFCONVERSIONAPI_CONFORMANCE_DIR,
        // ../conformance, ./conformance.
        val dir = listOfNotNull(
            System.getProperty("pdfconversionapi.conformanceDir")?.takeIf { it.isNotBlank() },
            System.getenv("PDFCONVERSIONAPI_CONFORMANCE_DIR")?.takeIf { it.isNotBlank() },
            "../conformance",
            "conformance",
        ).map { Paths.get(it).toAbsolutePath().normalize() }
            .first { Files.isRegularFile(it.resolve("cases.json")) }
        fixtures = dir.resolve("fixtures")
        mock = ProcessBuilder("node", dir.resolve("mock-server.mjs").toString(), "--port", "0").start()
        val line = BufferedReader(InputStreamReader(mock.inputStream)).readLine()
        baseUrl = line.removePrefix("LISTENING ").trim()
    }

    @AfterAll
    fun stop() {
        mock.destroy()
    }

    private fun client(): PdfConversionApi =
        PdfConversionApi.builder()
            .apiKey("test-key")
            .baseUrl(baseUrl)
            .timeout(Duration.ofSeconds(30))
            .build()

    @Test
    fun convertsAndReadsResultsAsProperties() {
        val result = client().convertFile(fixtures.resolve("hello.txt"), "pdf")
        val file: OutputFile = result.file
        val name: String = file.name
        val requestId: String? = result.requestId
        val url: String? = file.url
        assertEquals("hello.pdf", name)
        assertEquals(38L, file.size)
        assertEquals(file.size.toInt(), file.data.size)
        assertTrue(requestId!!.isNotEmpty())
        assertNull(url)
        assertEquals(false, file.isStored)
        assertEquals(1, result.conversionCost)

        val written: List<Path> = result.saveAll(temp.resolve("out"))
        assertEquals(listOf("hello.pdf"), written.map { it.fileName.toString() })
    }

    @Test
    fun parametersFromKotlinMaps() {
        val params = mapOf(
            "MockScenario" to "echo",
            "ImageResolution" to 72,
            "Scale" to 1.5,
            "MultiPage" to false,
            "Empty" to null,
            "ImageFile" to fixtures.resolve("logo.png"),
        )
        val result = client().convert("pdf", "jpg", UploadFile.fromPath(fixtures.resolve("hello.txt")), params)
        val echo = String(result.file.data)
        assertTrue(echo.contains("\"value\": \"72\""), echo)
        assertTrue(echo.contains("\"value\": \"1.5\""), echo)
        assertTrue(echo.contains("\"value\": \"false\""), echo)
        assertTrue(echo.contains("\"filename\": \"logo.png\""), echo)
        assertTrue(!echo.contains("\"Empty\""), echo)
    }

    @Test
    fun severalFilesAndNoFiles() {
        val merged = client().convert("pdf", "merge", UploadFile.fromPaths(listOf("a.txt", "b.txt", "c.txt").map { fixtures.resolve(it) }))
        assertEquals("A\nB\nC\n", String(merged.file.data))

        val qr = client().convert("data", "qrcode", mapOf("Data" to "hello-kotlin"))
        assertEquals("qrcode.svg", qr.file.name)

        val options = ConvertOptions.builder().fileName("custom").storeFile(true).build()
        val stored = client().convert("txt", "pdf", UploadFile.fromBytes("x".toByteArray(), "x.txt"), null, options)
        val fileId: String = stored.file.fileId!!
        assertThrows<StoredFileException> { stored.file.data }
        assertEquals("x", String(stored.file.download()))
        assertTrue(client().deleteStoredFile(fileId))
    }

    @Test
    fun errorsCarryTypedFields() {
        val waits = mutableListOf<Duration>()
        val client = PdfConversionApi.builder()
            .apiKey("test-key")
            .baseUrl(baseUrl)
            .maxRetries(1)
            .sleeper { waits += it }
            .build()
        val e = assertThrows<ServiceUnavailableException> {
            client.convert("txt", "pdf", fixtures.resolve("hello.txt"), mapOf("MockScenario" to "503-busy"))
        }
        val retryAfter: Long? = e.retryAfter
        assertEquals(503, e.status)
        assertEquals(1L, retryAfter)
        assertEquals(1, waits.size)

        val outcome = try {
            client.convert("txt", "pdf", fixtures.resolve("hello.txt"), mapOf("MockScenario" to "404"))
            "converted"
        } catch (e: NotFoundException) {
            "not found: ${e.message}"
        } catch (e: PdfConversionApiException) {
            "other: $e"
        }
        assertEquals("not found: conversion from docx to dwg is not supported", outcome)
    }

    @Test
    fun usageAndConversions() {
        val usage = client().usage()
        val remaining: Long? = usage.remaining
        assertEquals("Free", usage.plan)
        assertEquals(238L, remaining)
        assertEquals(false, usage.isOverage)
        val conversions: List<Conversion> = client().conversions()
        assertEquals("docx", conversions.first().from)
        assertTrue(Files.isDirectory(temp))
    }
}
