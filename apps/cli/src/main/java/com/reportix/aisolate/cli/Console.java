package com.reportix.aisolate.cli;

/** Where a command talks to the developer; tests swap in a fake. */
interface Console {

  /** A line as is: the endpoints under a deploy, the next step after create. */
  void println(String line);

  /** Progress. */
  void info(String line);

  /** Done. */
  void success(String line);

  /** Worth knowing; the command goes on. */
  void warn(String line);

  /** Why the command stops; on stderr. */
  void error(String line);

  /**
   * A choice taken by Enter or a click: the URL is what the click opens, as text only where no
   * click can.
   */
  void offerChoice(String label, String url);
}
