package com.acme.hr.pre_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.Answer;
import com.reportix.aisolate.sdk.ConversationMemory;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Exchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last three questions of each user, kept in this process for its lifetime: enough for "and
 * what about last year?" to make sense. Per user, never across users; gone when the airlock
 * restarts. Each question is kept to its first 200 characters.
 */
@Description("Remember the conversation")
public final class SessionMemory implements ConversationMemory {
  @Override
  public int order() {
    return 1;
  }

  static final int KEEP = 3;
  static final int MAX_QUESTION_LENGTH = 200;

  // Immutable lists, swapped atomically per user: concurrent exchanges never see a half-written
  // one. One entry per user for the life of the process, never evicted; evict idle users
  // when an airlock serves more people than fit in memory.
  private final Map<String, List<String>> lastQuestions = new ConcurrentHashMap<>();

  @Override
  public void recall(Exchange exchange, AIsolateManager aisolate) {
    List<String> previous = lastQuestions.getOrDefault(aisolate.principal().sub(), List.of());
    if (!previous.isEmpty()) {
      exchange.addContext(
          "The user's recent questions, oldest first: " + String.join(" | ", previous));
    }
  }

  @Override
  public void remember(Exchange exchange, Answer answer, AIsolateManager aisolate) {
    lastQuestions.merge(
        aisolate.principal().sub(),
        List.of(shorten(exchange.question())),
        (kept, latest) -> {
          List<String> all = new ArrayList<>(kept);
          all.addAll(latest);
          return List.copyOf(all.subList(Math.max(0, all.size() - KEEP), all.size()));
        });
  }

  static String shorten(String question) {
    return question.length() <= MAX_QUESTION_LENGTH
        ? question
        : question.substring(0, MAX_QUESTION_LENGTH);
  }
}
