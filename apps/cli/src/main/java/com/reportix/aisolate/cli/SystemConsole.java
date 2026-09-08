package com.reportix.aisolate.cli;

import java.io.PrintStream;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;

/** Stdout and stderr, a symbol per kind of line, colored on a terminal. */
@RequiredArgsConstructor
final class SystemConsole implements Console {

  /**
   * Whether the terminal takes Unicode: a Windows console on its default code page (437, 850, 1252)
   * prints {@code ?} for these glyphs, so it gets ASCII instead.
   */
  static final boolean UNICODE = utf8(System.getProperty("stdout.encoding"));

  // U+FE0E asks for the text glyph, not an emoji: one column wide, and it takes the color.
  static final String INFO = UNICODE ? "⏺︎" : "*";
  static final String SUCCESS = UNICODE ? "✔︎" : "+";
  static final String WARNING = "!";
  static final String ERROR = UNICODE ? "✘︎" : "x";
  static final String CHOICE = UNICODE ? "❯" : ">";

  private static final String BLUE = "38;5;33";
  private static final String GREEN = "38;5;42";
  private static final String YELLOW = "38;5;221";
  private static final String RED = "38;5;203";

  private final PrintStream out;
  private final PrintStream err;
  private final boolean color;

  /** No encoding named: the JDK's default, UTF-8 since JDK 18. */
  static boolean utf8(String encoding) {
    return encoding == null || encoding.replace("-", "").equalsIgnoreCase("UTF8");
  }

  /**
   * Picocli's verdict (NO_COLOR, CLICOLOR, TERM=dumb) and a real terminal: since JDK 22 {@code
   * System.console()} is there even when the output is piped.
   */
  static boolean colorful() {
    java.io.Console terminal = System.console();
    return CommandLine.Help.Ansi.AUTO.enabled() && terminal != null && terminal.isTerminal();
  }

  @Override
  public void println(String line) {
    out.println(line);
  }

  @Override
  public void info(String line) {
    out.println(mark(INFO, BLUE) + "  " + line);
  }

  @Override
  public void success(String line) {
    out.println(mark(SUCCESS, GREEN) + "  " + line);
  }

  @Override
  public void warn(String line) {
    out.println(mark(WARNING, YELLOW) + "  " + line);
  }

  @Override
  public void error(String line) {
    err.println(mark(ERROR, RED) + "  " + line);
  }

  // OSC 8, the terminal's hyperlink: a click opens the URL without the URL on screen. A terminal
  // without color links nothing, so there the URL follows on its own line, to copy.
  @Override
  public void offerChoice(String label, String url) {
    if (color) {
      out.println(
          " "
              + mark(CHOICE, BLUE)
              + " \u001b]8;;"
              + url
              + "\u001b\\"
              + label
              + "\u001b]8;;\u001b\\");
    } else {
      out.println(" " + CHOICE + " " + label);
      out.println("   " + url);
    }
  }

  private String mark(String symbol, String sgr) {
    return color ? "\u001b[" + sgr + "m" + symbol + "\u001b[0m" : symbol;
  }
}
