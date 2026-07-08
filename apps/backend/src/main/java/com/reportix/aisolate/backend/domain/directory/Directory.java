package com.reportix.aisolate.backend.domain.directory;

import java.util.List;
import java.util.Optional;

/**
 * The people of the company's identity provider, read-only: AIsolate assigns them to spaces and
 * airlocks by e-mail, and never creates, invites or deletes one.
 */
public interface Directory {

  /** The most people one search returns. */
  int MAX_RESULTS = 20;

  /** People whose name, username or e-mail contains the text; at most {@link #MAX_RESULTS}. */
  List<Person> search(String text);

  /** The person signing in with this e-mail, compared without case; empty when none does. */
  Optional<Person> findByEmail(String email);

  /**
   * Who a person is, and nothing else the provider knows of them.
   *
   * @param name first and last name, null when the provider has none
   */
  record Person(String name, String username, String email) {}

  /** The provider did not answer, or refused: the message is the one the caller sees. */
  class UnavailableException extends RuntimeException {
    public UnavailableException() {
      super("the directory did not answer");
    }
  }
}
