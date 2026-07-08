package com.reportix.aisolate.backend.domain.airlock;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * A create that names an id already taken, or a save after another one: 409, on the field it names.
 */
public class ConflictException extends RuntimeException {

  private final String field;

  public ConflictException(String field, String message) {
    super(message);
    this.field = field;
  }

  /** The airlock id is global: taken in this space or, as the message says, another one. */
  public static ConflictException airlockTaken(String id, String spaceId, String takenIn) {
    return new ConflictException(
        "id",
        "An airlock with the id "
            + id
            + " already exists in "
            + (takenIn.equals(spaceId) ? "this space." : "space " + takenIn + "."));
  }

  /**
   * The value would name its cluster objects like {@code other}'s.
   *
   * @param word what the form calls the value: id or name
   * @param other what holds the name already: {@code the airlock ef in space ab-cd}
   */
  public static ConflictException collision(String field, String word, String value, String other) {
    return new ConflictException(
        field,
        "The "
            + word
            + " "
            + value
            + " can't be used here: it would collide with "
            + other
            + ". Choose another "
            + word
            + ".");
  }

  /**
   * A save sent with {@code If-Match}, the {@code updated_at} or {@code revision} it was read at,
   * is refused when another save came between: a tab opened before a user was removed would
   * otherwise put the user back. Without the header the last save wins.
   *
   * @param ifMatch the header, quoted or not; null when not sent
   * @param stored the row's {@code updated_at} or {@code revision} now
   */
  public static void requireUnchanged(String field, String ifMatch, Object stored) {
    if (ifMatch == null) {
      return;
    }
    String expected = ifMatch.strip().replaceAll("^(W/)?\"|\"$", "");
    boolean unchanged;
    try {
      unchanged =
          stored instanceof OffsetDateTime at
              ? OffsetDateTime.parse(expected).isEqual(at)
              : expected.equals(String.valueOf(stored));
    } catch (DateTimeParseException notADate) {
      unchanged = false;
    }
    if (!unchanged) {
      throw new ConflictException(
          field, "Saved by someone else since you opened it; reload it and save again.");
    }
  }

  /** As a form error reads, {@code field: message}. */
  public String fieldError() {
    return field + ": " + getMessage();
  }
}
