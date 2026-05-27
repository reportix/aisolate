package com.reportix.aisolate.sdk;

import javax.sql.DataSource;

/** What the airlock pod knows and a handler may use; no key, no token, no credential. */
public interface AIsolateManager {

  /** The asking user as the validated token describes them. */
  Principal principal();

  /** Which airlock this exchange runs on. */
  AirlockRef airlock();

  /**
   * The LLM {@code id} of this airlock's catalog: its own, its space's or a global one.
   *
   * @throws LlmException the catalog has no model {@code id}; the message reads {@code no model
   *     named <id> in this airlock's space or globally}
   */
  Llm llm(String id);

  /**
   * The data source {@code id} of this airlock (its own or its space's), opened with the
   * credentials AIsolate keeps.
   *
   * @throws IllegalArgumentException the airlock defines no data source {@code id}; the message
   *     reads {@code <space>/<airlock> has no data source <id>}
   */
  DataSource dataSource(String id);

  /** Where a handler logs; lines land in the airlock's own log. */
  System.Logger log();
}
