package com.reportix.aisolate.cli;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;

/**
 * The welcome box: the version, the project in the directory, the commands that matter. Sized on
 * the plain text and colored after, so escape codes never push a border.
 */
@RequiredArgsConstructor
final class Banner {

  // Box drawing and the ellipsis where the console takes Unicode, ASCII where it would print "?".
  private static final String CORNER_TOP_LEFT = SystemConsole.UNICODE ? "╭" : "+";
  private static final String CORNER_TOP_RIGHT = SystemConsole.UNICODE ? "╮" : "+";
  private static final String CORNER_BOTTOM_LEFT = SystemConsole.UNICODE ? "╰" : "+";
  private static final String CORNER_BOTTOM_RIGHT = SystemConsole.UNICODE ? "╯" : "+";
  private static final String LINE = SystemConsole.UNICODE ? "─" : "-";
  private static final String BAR = SystemConsole.UNICODE ? "│" : "|";
  private static final String MORE = SystemConsole.UNICODE ? "…" : "~";

  private static final int MIN_WIDTH = 40;
  private static final int MAX_WIDTH = 80;
  private static final String BLUE = "38;5;33";
  private static final String GRAY = "38;5;245";
  private static final String BOLD = "1";
  private static final List<String> QUICK_START =
      List.of("aisolate create -s=<space> -t=company-hr", "aisolate login", "aisolate deploy");

  private final String version;
  private final int width;
  private final boolean color;

  private record Text(String value, String style) {}

  /** {@code COLUMNS} when the shell exports it, else 80. */
  static int readTerminalWidth() {
    try {
      int columns = Integer.parseInt(System.getenv().getOrDefault("COLUMNS", ""));
      return columns > 20 ? columns : MAX_WIDTH;
    } catch (NumberFormatException unset) {
      return MAX_WIDTH;
    }
  }

  String render(Path dir) {
    int outer = Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
    int rightWidth = QUICK_START.getFirst().length();
    int leftWidth = outer - rightWidth - 8; // "│  " left " │ " right " │"
    boolean twoColumns = leftWidth >= 24;
    int textWidth = twoColumns ? leftWidth : outer - 5;

    List<Text> left = new ArrayList<>();
    left.add(new Text("", null));
    left.add(new Text("Welcome to AIsolate", BOLD));
    left.add(new Text("", null));
    findSpace(dir).ifPresent(space -> left.add(new Text("Space  " + space, null)));
    left.add(new Text("Repo   " + keepTail(abbreviateHome(dir), textWidth - 7), null));
    List<Text> right = new ArrayList<>();
    right.add(new Text("", null));
    right.add(new Text("Quick start", BOLD));
    QUICK_START.forEach(command -> right.add(new Text(command, GRAY)));

    String title = "AIsolate v" + version;
    List<String> rows = new ArrayList<>();
    rows.add(
        paint(CORNER_TOP_LEFT + LINE + " ", BLUE)
            + paint(title, BOLD)
            + paint(" " + LINE.repeat(outer - 5 - title.length()) + CORNER_TOP_RIGHT, BLUE));
    String bar = paint(BAR, BLUE);
    if (twoColumns) {
      for (int i = 0; i < Math.max(left.size(), right.size()); i++) {
        rows.add(
            bar
                + "  "
                + renderCell(getRowOrBlank(left, i), leftWidth)
                + " "
                + bar
                + " "
                + renderCell(getRowOrBlank(right, i), rightWidth)
                + " "
                + bar);
      }
    } else {
      List<Text> stacked = new ArrayList<>(left);
      stacked.addAll(right);
      stacked.forEach(text -> rows.add(bar + "  " + renderCell(text, textWidth) + " " + bar));
    }
    rows.add(paint(CORNER_BOTTOM_LEFT + LINE.repeat(outer - 2) + CORNER_BOTTOM_RIGHT, BLUE));
    return String.join(System.lineSeparator(), rows);
  }

  private static Text getRowOrBlank(List<Text> column, int row) {
    return row < column.size() ? column.get(row) : new Text("", null);
  }

  private String renderCell(Text text, int width) {
    String value =
        text.value().length() <= width ? text.value() : text.value().substring(0, width - 1) + MORE;
    String padding = " ".repeat(width - value.length());
    return (text.style() == null ? value : paint(value, text.style())) + padding;
  }

  private String paint(String text, String sgr) {
    return color ? "\u001b[" + sgr + "m" + text + "\u001b[0m" : text;
  }

  private static Optional<String> findSpace(Path dir) {
    try {
      return Optional.of(ProjectFile.read(dir).space());
    } catch (IOException | IllegalStateException notAProject) {
      return Optional.empty();
    }
  }

  private static String abbreviateHome(Path dir) {
    String path = dir.toAbsolutePath().normalize().toString();
    String home = System.getProperty("user.home");
    return path.equals(home) || path.startsWith(home + File.separator)
        ? "~" + path.substring(home.length())
        : path;
  }

  // A path too long keeps its end: the project's own directory is the part worth seeing.
  private static String keepTail(String path, int width) {
    return path.length() <= width ? path : MORE + path.substring(path.length() - width + 1);
  }
}
