package com.acme.sales.pre_llm;

import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.ContextPreparator;
import com.reportix.aisolate.sdk.Description;
import com.reportix.aisolate.sdk.Exchange;
import java.time.LocalDate;

/**
 * Today's date, for the model: renewals "in the next 90 days" and overdue invoices need it, and a
 * model knows no date on its own. Runs first; adds one line of context and no evidence.
 */
@Description("Add today's date")
public final class TodayContext implements ContextPreparator {

  @Override
  public void prepare(Exchange exchange, AIsolateManager aisolate) {
    exchange.addContext("Today is " + LocalDate.now() + " (ISO date).");
  }
}
