package com.reportix.aisolate.airlock.domain.chat;

import java.util.Set;
import lombok.Getter;

/**
 * The tool returned an error result (a data source that refused or could not be reached, invalid
 * arguments, an unknown tool), or the tool server could not be asked.
 */
@Getter
public class ToolCallFailedException extends RuntimeException {

  public static final String LOGIN_REFUSED = "login_refused";
  public static final String UNREACHABLE = "unreachable";
  public static final String FAILED = "failed";

  private static final Set<String> CATEGORIES = Set.of(LOGIN_REFUSED, UNREACHABLE, FAILED);

  /** {@code login_refused}, {@code unreachable} or {@code failed}: all the chat and audit learn. */
  private final String error;

  /** The data source the tool server named; null when it named none. */
  private final String dataSource;

  public ToolCallFailedException(String message) {
    this(message, FAILED, null);
  }

  /**
   * @param error the tool server's category; anything but the three known ones is {@code failed}
   */
  public ToolCallFailedException(String message, Object error, Object dataSource) {
    super(message);
    this.error = error instanceof String known && CATEGORIES.contains(known) ? known : FAILED;
    this.dataSource = dataSource instanceof String id ? id : null;
  }

  /** The data source could not be read: the user should hear so, not "I don't know". */
  public boolean dataUnreadable() {
    return LOGIN_REFUSED.equals(error) || UNREACHABLE.equals(error);
  }
}
