package com.pdfconversionapi;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Your plan, its limits and this month's count, from {@code GET /v2/usage}. Immutable. */
public final class Usage {
  private final String plan;
  private final long included;
  private final long used;
  private final @Nullable Long remaining;
  private final boolean overage;
  private final int running;
  private final int concurrentLimit;
  private final int maxUploadMb;
  private final Instant periodStart;
  private final Instant periodEnd;

  Usage(
      String plan,
      long included,
      long used,
      @Nullable Long remaining,
      boolean overage,
      int running,
      int concurrentLimit,
      int maxUploadMb,
      Instant periodStart,
      Instant periodEnd) {
    this.plan = plan;
    this.included = included;
    this.used = used;
    this.remaining = remaining;
    this.overage = overage;
    this.running = running;
    this.concurrentLimit = concurrentLimit;
    this.maxUploadMb = maxUploadMb;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
  }

  /**
   * The plan's name, such as {@code Free}.
   *
   * @return the plan
   */
  public String getPlan() {
    return plan;
  }

  /**
   * Successful conversions included each calendar month.
   *
   * @return the number included
   */
  public long getIncluded() {
    return included;
  }

  /**
   * Successful conversions so far this month.
   *
   * @return the number used
   */
  public long getUsed() {
    return used;
  }

  /**
   * What is left of {@link #getIncluded()} this month.
   *
   * @return the number left, or null when the API doesn't say
   */
  public @Nullable Long getRemaining() {
    return remaining;
  }

  /**
   * Whether the plan keeps converting past {@link #getIncluded()}.
   *
   * @return true if it does
   */
  public boolean isOverage() {
    return overage;
  }

  /**
   * Conversions running for the account right now.
   *
   * @return the number running
   */
  public int getRunning() {
    return running;
  }

  /**
   * How many conversions the plan runs at the same time.
   *
   * @return the limit
   */
  public int getConcurrentLimit() {
    return concurrentLimit;
  }

  /**
   * The largest request the plan accepts, all files together, in MB.
   *
   * @return the limit in MB
   */
  public int getMaxUploadMb() {
    return maxUploadMb;
  }

  /**
   * When this month's count started (UTC).
   *
   * @return the start
   */
  public Instant getPeriodStart() {
    return periodStart;
  }

  /**
   * When the count starts again (UTC).
   *
   * @return the end
   */
  public Instant getPeriodEnd() {
    return periodEnd;
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Usage)) {
      return false;
    }
    Usage that = (Usage) other;
    return included == that.included && used == that.used && overage == that.overage
        && running == that.running && concurrentLimit == that.concurrentLimit
        && maxUploadMb == that.maxUploadMb && plan.equals(that.plan)
        && Objects.equals(remaining, that.remaining) && periodStart.equals(that.periodStart)
        && periodEnd.equals(that.periodEnd);
  }

  @Override
  public int hashCode() {
    return Objects.hash(plan, included, used, remaining, overage, running, concurrentLimit,
        maxUploadMb, periodStart, periodEnd);
  }

  @Override
  public String toString() {
    return "Usage{plan=" + plan + ", included=" + included + ", used=" + used + ", remaining="
        + remaining + ", overage=" + overage + ", running=" + running + ", concurrentLimit="
        + concurrentLimit + ", maxUploadMb=" + maxUploadMb + ", periodStart=" + periodStart
        + ", periodEnd=" + periodEnd + "}";
  }
}
