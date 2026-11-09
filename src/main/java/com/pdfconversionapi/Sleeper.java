package com.pdfconversionapi;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Waits between retries. The client uses {@link #SYSTEM} unless you pass another one to {@link
 * PdfConversionApi.Builder#sleeper(Sleeper)}, which is mainly useful in tests: record the waits
 * and return at once.
 *
 * <pre>{@code
 * List<Duration> waits = new ArrayList<>();
 * PdfConversionApi client = PdfConversionApi.builder().sleeper(waits::add).build();
 * }</pre>
 */
@FunctionalInterface
public interface Sleeper {
  /** Really sleeps, with {@link TimeUnit#sleep(long)}. */
  Sleeper SYSTEM = duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos());

  /**
   * Waits for a while before the next attempt.
   *
   * @param duration how long to wait
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  void sleep(Duration duration) throws InterruptedException;
}
