package com.reportix.aisolate.toolserver.domain;

import com.reportix.aisolate.config.rules.Rule;
import com.reportix.aisolate.config.rules.RuleSet;
import com.reportix.aisolate.identity.IdentityProperties;
import com.reportix.aisolate.identity.Principal;
import com.reportix.aisolate.sdk.Entity;
import com.reportix.aisolate.sdk.Filter;
import com.reportix.aisolate.sdk.GovernedQuery;
import com.reportix.aisolate.sdk.Result;
import com.reportix.aisolate.sdk.Tool;
import com.reportix.aisolate.sdk.ToolPlugin;
import com.reportix.aisolate.toolserver.domain.rules.Obligations;
import com.reportix.aisolate.toolserver.domain.rules.RulesEvaluator;
import com.reportix.aisolate.toolserver.domain.sql.CompiledQuery;
import com.reportix.aisolate.toolserver.domain.sql.SqlCompiler;
import com.reportix.aisolate.toolserver.domain.sql.ToolArgs;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The data boundary, the same for every plugin: which airlock, is this user admitted to it, does it
 * declare this tool, which plugin answers it, identity join; then, for each query the tool makes,
 * rule evaluation, SQL compilation and execution on the plugin's data source through its read-only
 * role. A denied user gets zero rows, never an error the model could see. Who may call (its space's
 * roles and its own, through its callers) and which tools an airlock declares come from its policy
 * ({@link PolicySource}); the tools, the entities and the rule sets from the airlock's code ({@link
 * PluginCatalog}); each plugin's data source from this pod's {@link CustomerDataSources}. This
 * server has no connection to AIsolate's database.
 *
 * <p>Each call is the span {@code governed query <tool>} of the airlock's trace, each statement a
 * {@code sql <data source>} span under it: names, counts and the outcome, never a row or a
 * statement. Each call also counts once in {@code aisolate.tool.queries} by airlock, tool and
 * outcome; the airlock is the served policy's, {@code none} when the caller named none this server
 * serves, so a caller cannot invent a label.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GovernedQueryService {

  private final PolicySource policies;
  private final CustomerDataSources customerDataSources;
  private final RulesEvaluator evaluator;
  private final SqlCompiler compiler;
  private final PluginCatalog catalog;
  private final ObservationRegistry observations;
  private final MeterRegistry meters;
  private final IdentityProperties identity;

  /**
   * Runs one tool call: the gates first, then the handler of the plugin publishing this tool, with
   * a {@link GovernedQuery} bound to this user, this airlock and that plugin's data source.
   *
   * @param airlockId from the call's {@code _meta}; null when the caller named none
   * @param revision from the call's {@code _meta}; null when the caller named none
   * @param toolName a published tool: {@code tools/list} offers no other
   * @throws IllegalArgumentException the arguments do not fit: a tool error for the model
   * @throws IllegalStateException the handler returned no {@link Result}: a generic tool error
   */
  public Result call(
      UserToken user,
      String airlockId,
      String revision,
      String toolName,
      Map<String, Object> arguments) {
    Observation span =
        Observation.createNotStarted("aisolate.trace.query", observations)
            .contextualName("governed query " + toolName);
    tag(span, "aisolate.airlock", airlockId);
    tag(span, "aisolate.revision", revision);
    tag(span, "aisolate.tool", toolName);
    Policy policy = findPinnedPolicy(airlockId, revision);
    return span.observe(
        () -> {
          String outcome = "error";
          try {
            Result result = answer(user, policy, toolName, arguments, span);
            outcome = outcomeOf(result, span);
            return result;
          } catch (IllegalArgumentException invalid) {
            outcome = "invalid";
            throw invalid;
          } finally {
            tag(span, "aisolate.outcome", outcome);
            meters
                .counter(
                    "aisolate.tool.queries",
                    "airlock",
                    policy == null ? "none" : policy.id(),
                    "tool",
                    toolName,
                    "outcome",
                    outcome)
                .increment();
          }
        });
  }

  private Result answer(
      UserToken user,
      Policy policy,
      String toolName,
      Map<String, Object> arguments,
      Observation span) {
    if (policy == null) {
      return Result.empty("", "");
    }
    if (!admits(user, policy, toolName)) {
      return Result.empty("", policy.ruleVersion());
    }
    ToolPlugin plugin = catalog.findTool(toolName).orElseThrow();
    tag(span, "aisolate.data_source", plugin.dataSource());
    // A plugin without entities reads nothing; the start checked every other one's data source.
    JdbcClient customerDb =
        plugin.entities().isEmpty() ? null : customerDataSources.forId(plugin.dataSource());
    Principal principal =
        Principal.of(
            user.claims(),
            identity.rolesClaim(),
            plugin.identityAttribute(),
            // The identity join's business key is the user's e-mail, as the airlock admits them by.
            resolveIdentity(customerDb, plugin.identityJoin(), user.claims().get("email")));
    Tool tool =
        plugin.tools().stream()
            .filter(candidate -> candidate.name().equals(toolName))
            .findFirst()
            .orElseThrow();
    Object returned =
        tool.handler().call(new Bound(policy, principal, plugin, customerDb), arguments);
    // The airlock reads a Result alone; anything else would reach the model as zero rows.
    if (!(returned instanceof Result result)) {
      throw new IllegalStateException(
          "plugin "
              + plugin.name()
              + ": tool "
              + toolName
              + " returned "
              + (returned == null ? "null" : returned.getClass().getName())
              + ", not a Result");
    }
    return result;
  }

  /**
   * {@code rows}, {@code empty} or {@code denied}: no rule granted anything, which is how a gate or
   * a rule turns a caller away, silently.
   */
  private static String outcomeOf(Result result, Observation span) {
    tag(span, "aisolate.entity", result.entity());
    tag(span, "aisolate.rows", result.rowCount());
    return result.rowCount() > 0 ? "rows" : result.rules().isEmpty() ? "denied" : "empty";
  }

  private static void tag(Observation span, String key, Object value) {
    if (value != null && !String.valueOf(value).isEmpty()) {
      span.highCardinalityKeyValue(key, String.valueOf(value));
    }
  }

  /**
   * The policy of the airlock the caller pins. A caller that names no revision gets nothing,
   * exactly as one that names no airlock: the policy is pinned to both or not read at all.
   *
   * @return null when the pin is incomplete or AIsolate does not serve that airlock
   */
  private Policy findPinnedPolicy(String airlockId, String revision) {
    return airlockId == null || revision == null
        ? null
        : policies.findPolicy(airlockId, revision).orElse(null);
  }

  /**
   * Gate 2 again, on this server's own reading of the token: the user must hold one of its space's
   * roles or the airlock's own, through a calling client it admits, or the token a machine client
   * of the airlock's, not merely named in the call's {@code _meta}. Roles select who may ask and
   * which rules apply. Gate 3 re-run at the data boundary: whatever airlock a caller names, it gets
   * only the tools that airlock declares. Both read the token alone, so a caller turned away costs
   * no lookup and no query.
   */
  private boolean admits(UserToken user, Policy policy, String toolName) {
    Principal caller = Principal.of(user.claims(), identity.rolesClaim());
    return caller
            .admissionFailure(
                policy.access().roles(), policy.access().callers(), policy.access().clients())
            .isEmpty()
        && policy.tools().contains(toolName);
  }

  /**
   * The identity join the plugin declares: the token's business key claim to the customer's own
   * key; null without a join, a data source or a row, and the evaluator then denies every row
   * filter on it.
   */
  private static Object resolveIdentity(JdbcClient customerDb, String identityJoin, Object claim) {
    return customerDb != null && identityJoin != null && claim != null
        ? customerDb
            .sql(identityJoin)
            .param(claim)
            .query((rs, rowNum) -> rs.getObject(1))
            .optional()
            .orElse(null)
        : null;
  }

  /**
   * The queries one tool call may make: each one evaluated, compiled and run under the rules. The
   * plugin is the one whose tool is running: its entities are what the tool reaches, on its data
   * source, and its identity attribute marks the user's own row.
   */
  @RequiredArgsConstructor
  private final class Bound implements GovernedQuery {

    private final Policy policy;
    private final Principal principal;
    private final ToolPlugin plugin;
    private final JdbcClient customerDb;

    @Override
    public Result read(String entityName, List<Filter> filters, int limit) {
      return run(
          entityName,
          Rule.Action.READ,
          (entity, obligations) ->
              compiler.compileRead(
                  entity,
                  obligations,
                  buildArgs(entity, obligations).read(filters, limit),
                  SqlCompiler.ownKey(
                      entity,
                      plugin.identityAttribute(),
                      principal.attribute(plugin.identityAttribute()))));
    }

    @Override
    public Result aggregate(
        String entityName, Fn function, String column, String groupBy, List<Filter> filters) {
      return run(
          entityName,
          Rule.Action.AGGREGATE,
          (entity, obligations) ->
              compiler.compileAggregate(
                  entity,
                  obligations,
                  buildArgs(entity, obligations).aggregate(function, column, groupBy, filters)));
    }

    private Result run(
        String entityName,
        Rule.Action action,
        BiFunction<Entity, Obligations, CompiledQuery> compile) {
      // An entity is reachable only through the plugin declaring it, which names the data source
      // it lives in, and only where a rule set governs it. Anything else is nothing to see.
      Entity entity = plugin.entity(entityName).orElse(null);
      RuleSet ruleSet = catalog.findRuleSet(entityName).orElse(null);
      if (entity == null || ruleSet == null) {
        log.warn(
            "{}: entity '{}' declared={} governed={}; asked by a tool of plugin {}",
            policy.id(),
            entityName,
            entity != null,
            ruleSet != null,
            plugin.name());
        return Result.empty(entityName, policy.ruleVersion());
      }
      Obligations obligations = evaluator.evaluate(principal, ruleSet, action).orElse(null);
      if (obligations == null) {
        return Result.empty(entityName, policy.ruleVersion());
      }
      CompiledQuery query = compile.apply(entity, obligations);
      Observation sql =
          Observation.createNotStarted("aisolate.trace.sql", observations)
              .contextualName("sql " + plugin.dataSource());
      tag(sql, "aisolate.airlock", policy.id());
      tag(sql, "aisolate.revision", policy.revision());
      tag(sql, "aisolate.data_source", plugin.dataSource());
      tag(sql, "aisolate.entity", entity.name());
      List<Map<String, Object>> rows =
          sql.observe(
              () -> customerDb.sql(query.sql()).params(query.params()).query().listOfRows());
      return new Result(
          entity.name(),
          rows.size(),
          query.columns(),
          rows,
          policy.ruleVersion(),
          obligations.matchedRules(),
          query.sqlHash(),
          query.labels());
    }

    private static ToolArgs buildArgs(Entity entity, Obligations obligations) {
      return new ToolArgs(
          entity, entity.queryableColumns(obligations.hidden(), obligations.masked()));
    }
  }
}
