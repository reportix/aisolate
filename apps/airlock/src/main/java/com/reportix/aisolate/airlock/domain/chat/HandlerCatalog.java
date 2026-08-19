package com.reportix.aisolate.airlock.domain.chat;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;

/**
 * The handlers of an airlock, resolved from its own code. The implementation loads developer code;
 * the chat runtime only calls what it is handed.
 */
public interface HandlerCatalog {

  /**
   * @throws RuntimeException the code could not be loaded or resolved: the exchange fails closed
   */
  Handlers resolveHandlers(AirlockDefinition airlock);
}
