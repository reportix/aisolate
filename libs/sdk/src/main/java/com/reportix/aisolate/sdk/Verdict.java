package com.reportix.aisolate.sdk;

/** One verifier's word on the answer, shown next to it. {@code handler} is set by AIsolate. */
public record Verdict(String handler, Status status, String note) {

  public enum Status {
    PASS,
    WARN,
    FAIL
  }

  public Verdict {
    note = note == null ? "" : note;
  }

  public static Verdict pass(String note) {
    return new Verdict(null, Status.PASS, note);
  }

  public static Verdict warn(String note) {
    return new Verdict(null, Status.WARN, note);
  }

  public static Verdict fail(String note) {
    return new Verdict(null, Status.FAIL, note);
  }
}
