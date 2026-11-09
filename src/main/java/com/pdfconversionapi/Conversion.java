package com.pdfconversionapi;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** One conversion the API offers, from {@code GET /v2/info}. Immutable. */
public final class Conversion {
  private final String from;
  private final String to;
  private final String title;
  private final String category;
  private final String inputs;

  Conversion(String from, String to, String title, String category, String inputs) {
    this.from = from;
    this.to = to;
    this.title = title;
    this.category = category;
    this.inputs = inputs;
  }

  /**
   * The format in the path's first place, such as {@code docx} in {@code docx/to/pdf}.
   *
   * @return the source format
   */
  public String getFrom() {
    return from;
  }

  /**
   * The format or tool in the path's second place, such as {@code pdf} or {@code merge}.
   *
   * @return the target format or tool
   */
  public String getTo() {
    return to;
  }

  /**
   * A human-readable name, such as {@code DOCX to PDF}.
   *
   * @return the title
   */
  public String getTitle() {
    return title;
  }

  /**
   * The group it belongs to, such as {@code Document} or {@code PDF Tools}.
   *
   * @return the category
   */
  public String getCategory() {
    return category;
  }

  /**
   * How many files it takes: {@code one}, {@code many} (in repeated {@code Files} fields) or
   * {@code optional} (it can work from parameters alone, such as {@code data/to/qrcode}).
   *
   * @return the inputs
   */
  public String getInputs() {
    return inputs;
  }

  @Override
  public boolean equals(@Nullable Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof Conversion)) {
      return false;
    }
    Conversion that = (Conversion) other;
    return from.equals(that.from) && to.equals(that.to) && title.equals(that.title)
        && category.equals(that.category) && inputs.equals(that.inputs);
  }

  @Override
  public int hashCode() {
    return Objects.hash(from, to, title, category, inputs);
  }

  @Override
  public String toString() {
    return "Conversion{" + from + "/to/" + to + ", title=" + title + ", category=" + category
        + ", inputs=" + inputs + "}";
  }
}
