package com.pdfconversionapi;

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for one conversion that the client knows by type: the output name, storing the result
 * behind a link, and a timeout for this call. Immutable; build one with {@link #builder()}.
 *
 * <pre>{@code
 * ConvertOptions options = ConvertOptions.builder().fileName("march").storeFile(true).build();
 * }</pre>
 *
 * <p>When the parameters map also sets {@code FileName} or {@code StoreFile}, these options win.
 */
public final class ConvertOptions {
  private static final ConvertOptions DEFAULTS = new ConvertOptions(null, null, null);

  private final @Nullable String fileName;
  private final @Nullable Boolean storeFile;
  private final @Nullable Duration timeout;

  private ConvertOptions(
      @Nullable String fileName, @Nullable Boolean storeFile, @Nullable Duration timeout) {
    this.fileName = fileName;
    this.storeFile = storeFile;
    this.timeout = timeout;
  }

  /**
   * Options with nothing set.
   *
   * @return the empty options
   */
  public static ConvertOptions defaults() {
    return DEFAULTS;
  }

  /**
   * Starts building options.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * A builder that starts from these options.
   *
   * @return a new builder
   */
  public Builder toBuilder() {
    Builder builder = new Builder();
    builder.fileName = fileName;
    builder.storeFile = storeFile;
    builder.timeout = timeout;
    return builder;
  }

  /**
   * The output name, sent as {@code FileName}.
   *
   * @return the name, or null when not set
   */
  public @Nullable String getFileName() {
    return fileName;
  }

  /**
   * Whether to store the output behind a download link, sent as {@code StoreFile=true}.
   *
   * @return true or false when set, null when not set
   */
  public @Nullable Boolean getStoreFile() {
    return storeFile;
  }

  /**
   * The timeout for this call, replacing the client's.
   *
   * @return the timeout, or null for the client's
   */
  public @Nullable Duration getTimeout() {
    return timeout;
  }

  @Override
  public String toString() {
    return "ConvertOptions{fileName=" + fileName + ", storeFile=" + storeFile + ", timeout="
        + timeout + "}";
  }

  /** Builds {@link ConvertOptions}. */
  public static final class Builder {
    private @Nullable String fileName;
    private @Nullable Boolean storeFile;
    private @Nullable Duration timeout;

    private Builder() {}

    /**
     * Names the output, without the extension: {@code march} gives {@code march.pdf}, and {@code
     * march-2.jpg} for page images. Sent as {@code FileName}.
     *
     * @param fileName the name, or null to leave it unset
     * @return this builder
     */
    public Builder fileName(@Nullable String fileName) {
      this.fileName = fileName;
      return this;
    }

    /**
     * With true, the API keeps each output for 3 hours and answers with a download link instead
     * of the data: use {@link OutputFile#download()} or {@link OutputFile#save(java.nio.file.Path)}
     * to fetch it. Sent as {@code StoreFile=true}; false sends nothing.
     *
     * @param storeFile whether to store the output
     * @return this builder
     */
    public Builder storeFile(boolean storeFile) {
      this.storeFile = storeFile;
      return this;
    }

    /**
     * A timeout for this call only, for the whole response of each attempt.
     *
     * @param timeout a positive duration, or null for the client's timeout
     * @return this builder
     * @throws IllegalArgumentException if the duration is zero or negative
     */
    public Builder timeout(@Nullable Duration timeout) {
      if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
        throw new IllegalArgumentException("timeout must be positive, got " + timeout);
      }
      this.timeout = timeout;
      return this;
    }

    /**
     * Builds the options.
     *
     * @return the options
     */
    public ConvertOptions build() {
      if (fileName == null && storeFile == null && timeout == null) {
        return DEFAULTS;
      }
      return new ConvertOptions(fileName, storeFile, timeout);
    }

    @Override
    public String toString() {
      return "ConvertOptions.Builder{fileName=" + fileName + ", storeFile=" + storeFile
          + ", timeout=" + Objects.toString(timeout) + "}";
    }
  }
}
