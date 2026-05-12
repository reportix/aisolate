package com.reportix.aisolate.config.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RowFilterTest {

  @ParameterizedTest
  @CsvSource({
    "audience=='hr', audience, hr",
    "dept == 'r-d_1', dept, r-d_1",
  })
  void givenLiteralComparison_whenParse_thenColumnAndLiteral(
      String text, String column, String literal) {
    // Arrange / Act
    RowFilter filter = RowFilter.parse(text);

    // Assert
    assertThat(filter.column()).isEqualTo(column);
    assertThat(filter.literal()).isEqualTo(literal);
    assertThat(filter.attribute()).isNull();
    assertThat(filter.matchesAll()).isFalse();
  }

  @Test
  void givenPrincipalComparison_whenParse_thenColumnAndAttribute() {
    // Arrange / Act
    RowFilter filter = RowFilter.parse("owner_email == principal.email");

    // Assert
    assertThat(filter.column()).isEqualTo("owner_email");
    assertThat(filter.attribute()).isEqualTo("email");
    assertThat(filter.literal()).isNull();
  }

  @Test
  void givenTrue_whenParse_thenMatchesAll() {
    // Arrange / Act
    RowFilter filter = RowFilter.parse(" true ");

    // Assert
    assertThat(filter.matchesAll()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "audience == \"all\"",
        "audience == 'All'",
        "audience == 'a b'",
        "audience == ''",
        "1=1 OR true",
        "audience == 'all' OR true"
      })
  void givenTextOutsideTheGrammar_whenParse_thenRejected(String text) {
    // Arrange / Act
    Throwable thrown = catchThrowable(() -> RowFilter.parse(text));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("row_filter");
  }

  @Test
  void givenNull_whenParse_thenRequired() {
    // Arrange / Act
    Throwable thrown = catchThrowable(() -> RowFilter.parse(null));

    // Assert
    assertThat(thrown)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("required");
  }
}
