package com.reportix.aisolate.toolserver.domain.sql;

import com.reportix.aisolate.sdk.Result;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * A parameterised statement. {@code sqlHash} identifies the query shape (not the values) for
 * provenance; {@code labels} say what the columns it returns mean.
 */
public record CompiledQuery(
    String sql, List<Object> params, List<String> columns, Result.Labels labels) {

  public CompiledQuery {
    params = List.copyOf(params);
    columns = List.copyOf(columns);
  }

  public String sqlHash() {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 6);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
