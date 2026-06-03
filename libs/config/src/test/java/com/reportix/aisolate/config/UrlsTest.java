package com.reportix.aisolate.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlsTest {

  @ParameterizedTest
  @CsvSource({
    "jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr, acme-db.aisolate.svc",
    "jdbc:sqlserver://sql1:1433;databaseName=hr, sql1",
    "https://reader@db.example.com/hr, db.example.com",
    "'http://[::1]:8080/', ::1",
    "JDBC:postgresql://10.30.1.7/hr, 10.30.1.7"
  })
  void givenUrl_whenHost_thenTheHostWithoutPortOrUserinfo(String url, String host) {
    // Arrange / Act
    String found = Urls.host(url);

    // Assert
    assertThat(found).isEqualTo(host);
  }

  @ParameterizedTest
  @ValueSource(strings = {"localhost", ""})
  void givenUrlWithoutHost_whenHost_thenNull(String url) {
    // Arrange / Act
    String found = Urls.host(url);

    // Assert
    assertThat(found).isNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://db/hr?user=r&password=x",
        "jdbc:postgresql://db/hr?PWD=x",
        "jdbc:sqlserver://db:1433;databaseName=hr;password=x",
        "jdbc:mysql://db/hr?passwd=x",
        "https://api.example.com/v1?secret=x",
        "https://api.example.com/v1?token=x",
        "https://api.example.com/v1?access_token=x",
        "https://api.example.com/v1?apiKey=x",
        "https://api.example.com/v1?api_key=x",
        "https://api.example.com/v1?key=x",
        "jdbc:postgresql://db/hr?sslpassword=x",
        "jdbc:postgresql://db/hr?sslkey=/k",
        "postgresql://reader:x@db/hr",
        "jdbc:postgresql://db/hr?user=reader&sslmode=require",
        "jdbc:postgresql://db/hr;username=reader",
        "https://reader@db.example.com/hr"
      })
  void givenUrlCarryingACredential_whenChecked_thenFound(String url) {
    // Arrange / Act
    String credential = Urls.findCredential(url);

    // Assert
    assertThat(credential)
        .isNotNull()
        .doesNotContain("=x")
        .doesNotContain(":x@")
        .doesNotContain("reader");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "jdbc:postgresql://acme-db.aisolate.svc:5432/acme_hr",
        "jdbc:postgresql://db/hr?sslmode=require"
      })
  void givenUrlWithoutCredential_whenChecked_thenNone(String url) {
    // Arrange / Act
    String credential = Urls.findCredential(url);

    // Assert
    assertThat(credential).isNull();
  }
}
