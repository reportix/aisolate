package com.reportix.aisolate.sdk;

import java.util.Map;

/**
 * One tool a plugin publishes on AIsolate's tool server. The server authenticates the caller,
 * checks that the airlock admits them and declares this tool, then hands the handler a {@link
 * GovernedQuery} bound to that user: the only way to the data.
 *
 * @param inputSchema JSON Schema of the arguments, as the model sees it
 */
public record Tool(
    String name, String description, Map<String, Object> inputSchema, Handler handler) {

  @FunctionalInterface
  public interface Handler {
    /**
     * Returns what the model gets: a {@link Result}, the governed query's or one built from its
     * rows. Anything else, null included, fails the call with a generic error and is logged under
     * the plugin's name. Throw {@link IllegalArgumentException} for arguments that do not fit: the
     * model sees that message and nothing else.
     */
    Object call(GovernedQuery query, Map<String, Object> arguments);
  }
}
