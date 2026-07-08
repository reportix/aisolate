package com.reportix.aisolate.backend.domain.airlock;

import java.util.Map;

/**
 * A form row whose credentials become a Secret ({@link DataSourceSecrets}): a data source's login,
 * a model's API key. The row names its Secret; the credentials never reach a stored row or a
 * response.
 */
public interface SecretRow<R extends SecretRow<R>> {

  String id();

  String url();

  /** The Secret's name; empty for none. */
  String secretRef();

  /** A typed credential replaces the row's Secret; none keeps it. */
  boolean bringsCredentials();

  R withSecretRef(String ref);

  /** What its Secret holds, by key, as the pods read it. */
  Map<String, String> secretData();

  /** The form's list of such rows, as messages name it: {@code data_sources}. */
  String listField();

  /** The credential's field, as messages name it: {@code password}. */
  String credentialField();

  /** What its Secrets' names say it is: {@code <owner>-<kind>-<id>-<6 random>}. */
  String secretKind();
}
