package com.reportix.aisolate.backend.domain.airlock;

import java.util.List;
import lombok.Getter;

/** One message per problem, each naming its field: the developer needs them to fix the form. */
@Getter
public class InvalidFormException extends RuntimeException {

  private final List<String> errors;

  public InvalidFormException(List<String> errors) {
    super("invalid form");
    this.errors = List.copyOf(errors);
  }
}
