package com.reportix.aisolate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records what a command prints: info, success and warn lines with their symbol, errors without.
 */
final class FakeConsole implements Console {

  final List<String> lines = new CopyOnWriteArrayList<>();
  final List<String> errors = new ArrayList<>();

  /** The URL behind the last offered choice: what a click opens. */
  volatile String offered;

  @Override
  public void println(String line) {
    lines.add(line);
  }

  @Override
  public void info(String line) {
    lines.add("⏺ " + line);
  }

  @Override
  public void success(String line) {
    lines.add("✔ " + line);
  }

  @Override
  public void warn(String line) {
    lines.add("! " + line);
  }

  @Override
  public void offerChoice(String label, String url) {
    lines.add("❯ " + label);
    offered = url;
  }

  @Override
  public void error(String line) {
    errors.add(line);
  }
}
