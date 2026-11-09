# Changelog

## 0.1.0 (unreleased)

- First release: `PdfConversionApi` client for Java 11+ and Kotlin, following SPEC 1.3.
- `convert`, `convertFile`, `usage`, `conversions`, `download`, `downloadTo`, `deleteStoredFile`.
- Inputs from a `Path`, bytes or an `InputStream`; named file parameters; typed `ConvertOptions`.
- Retries with backoff and jitter, typed exceptions, no runtime dependencies.
