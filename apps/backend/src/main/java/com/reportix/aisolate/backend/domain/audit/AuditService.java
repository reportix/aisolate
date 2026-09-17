package com.reportix.aisolate.backend.domain.audit;

import com.reportix.aisolate.backend.infrastructure.audit.AuditProperties;
import com.reportix.aisolate.config.AuditIngestToken;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import tools.jackson.databind.json.JsonMapper;

/**
 * The audit trail: lines in from the airlock pods, each chained to its airlock's last; read back by
 * the AIsolate UI, the CLI and auditors; and the chain walked again on demand.
 */
@Service
@RequiredArgsConstructor
public class AuditService {

  /**
   * A line is one exchange, not a document: the airlock's question limit, an answer, its figures.
   */
  static final int MAX_LINE = 1024 * 1024;

  static final int MAX_PAGE = 200;
  private static final int CHAIN_PAGE = 500;

  private final AuditRepository repository;
  private final AuditProperties properties;
  private final TransactionOperations transactions;

  public record Appended(long id, boolean repeat) {}

  /**
   * @param token the bearer the pod sent; null without one
   * @throws AuditException 401 without a token, 400 for a body that is no audit line, 403 for a
   *     token that is not the ingest token of the airlock the line names
   */
  public Appended ingest(String token, String json) {
    if (token == null || token.isBlank()) {
      throw new AuditException(HttpStatus.UNAUTHORIZED, "an ingest token is required");
    }
    if (json.length() > MAX_LINE) throw AuditException.invalid("larger than 1 MiB");
    AuditLine line = AuditLine.parse(json);
    AuditLine.Columns c = line.columns();
    if (!AuditIngestToken.matches(properties.ingestKey(), token, c.space(), c.airlock())) {
      throw new AuditException(HttpStatus.FORBIDDEN, "forbidden");
    }
    return transactions.execute(
        status -> {
          repository.becomeWriter();
          repository.lockChain(c.space(), c.airlock());
          return repository
              .findRepeat(c.space(), c.airlock(), line.digest())
              .map(id -> new Appended(id, true))
              .orElseGet(
                  () -> {
                    String prev = repository.head(c.space(), c.airlock()).orElse(AuditLine.GENESIS);
                    return new Appended(
                        repository.insert(line, prev, AuditLine.chain(prev, line.body())), false);
                  });
        });
  }

  public record Page(List<AuditRepository.Row> events, String nextCursor) {}

  /**
   * @param spaces the only spaces to list, a developer's; null for every space
   * @param question only lines whose question holds it, case ignored
   * @param search only lines whose space, airlock, user, client, trace id or question holds it,
   *     case ignored
   * @param from inclusive, a date (its first instant, UTC) or an instant
   * @param to inclusive, a date (all of it) or an instant
   */
  public Page list(
      String space,
      List<String> spaces,
      String airlock,
      String user,
      String outcome,
      String traceId,
      String question,
      String search,
      String from,
      String to,
      String cursor,
      int limit) {
    if (outcome != null && !AuditLine.OUTCOMES.contains(outcome)) {
      throw AuditException.invalid("outcome: answered, dont_know, refused or error");
    }
    if (limit < 1 || limit > MAX_PAGE) throw AuditException.invalid("limit: 1 to " + MAX_PAGE);
    Long before = null;
    if (cursor != null) {
      try {
        before = Long.parseLong(cursor);
      } catch (NumberFormatException e) {
        throw AuditException.invalid("cursor: not one this API gave");
      }
    }
    AuditRepository.Filter filter =
        new AuditRepository.Filter(
            blankToNull(space),
            spaces,
            blankToNull(airlock),
            blankToNull(user),
            outcome,
            blankToNull(traceId),
            blankToNull(question),
            blankToNull(search),
            bound("from", from, false),
            bound("to", to, true));
    Long page = before;
    List<AuditRepository.Row> rows =
        transactions.execute(
            status -> {
              repository.becomeWriter();
              return repository.list(filter, page, limit + 1);
            });
    boolean more = rows.size() > limit;
    List<AuditRepository.Row> shown = more ? rows.subList(0, limit) : rows;
    return new Page(shown, more ? String.valueOf(shown.getLast().id()) : null);
  }

  /**
   * Every line {@link #list} finds for the search and the period, newest first, with its body: the
   * export's. All in memory, as the PDF and the zip are built; stream if exports outgrow the heap.
   */
  public List<AuditRepository.Row> all(
      String space, List<String> spaces, String search, String from, String to) {
    List<AuditRepository.Row> rows = new ArrayList<>();
    String cursor = null;
    do {
      Page page =
          list(space, spaces, null, null, null, null, null, search, from, to, cursor, MAX_PAGE);
      Map<Long, String> bodies =
          bodies(page.events().stream().map(AuditRepository.Row::id).toList());
      for (AuditRepository.Row r : page.events()) {
        rows.add(
            new AuditRepository.Row(
                r.id(), r.receivedAt(), r.columns(), r.prevHash(), r.hash(), bodies.get(r.id())));
      }
      cursor = page.nextCursor();
    } while (cursor != null);
    return rows;
  }

  /**
   * @throws AuditException 404 when no line has this id
   */
  public AuditRepository.Row find(long id) {
    return transactions
        .execute(
            status -> {
              repository.becomeWriter();
              return repository.find(id);
            })
        .orElseThrow(() -> new AuditException(HttpStatus.NOT_FOUND, "unknown audit event"));
  }

  /**
   * A data source the airlock could not read: its id, why ({@code login_refused} or {@code
   * unreachable}, as the airlock recorded on the failed call) and when.
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record DataError(String dataSource, String error, Instant ts) {}

  /** What the data source's owner can fix; any other failure is no setting of theirs. */
  private static final Set<String> DATA_ERRORS = Set.of("login_refused", "unreachable");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * The first data source the airlock's newest exchange that called a tool could not read; empty
   * when that exchange read its data, so a fixed password clears it with the next question.
   */
  public Optional<DataError> dataError(String space, String airlock) {
    Optional<AuditRepository.Row> row =
        transactions.execute(
            status -> {
              repository.becomeWriter();
              return repository.latestToolExchange(space, airlock);
            });
    return row.flatMap(r -> dataErrorOf(r.body(), r.columns().ts()));
  }

  static Optional<DataError> dataErrorOf(String body, Instant ts) {
    for (JsonNode tool : JSON.readTree(body).path("tools")) {
      String error = tool.path("error").asString("");
      if (DATA_ERRORS.contains(error)) {
        JsonNode source = tool.path("data_source");
        return Optional.of(new DataError(source.isString() ? source.asString() : null, error, ts));
      }
    }
    return Optional.empty();
  }

  /** The stored lines of these ids, by id: a page's, which the list gives without them. */
  public Map<Long, String> bodies(List<Long> ids) {
    return transactions.execute(
        status -> {
          repository.becomeWriter();
          return repository.bodies(ids);
        });
  }

  public record Broken(long id, String reason) {}

  // snake_case like the rest of the audit JSON: the UI reads first_id and last_id.
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Verification(
      String space,
      String airlock,
      String status,
      long events,
      Long firstId,
      Long lastId,
      String head,
      Broken broken) {}

  /**
   * Oldest to newest: each line's prev_hash must be the hash of the line before it ({@code removed}
   * otherwise), and its hash and columns what its body gives ({@code altered}). The oldest line
   * kept anchors the walk: retention drops whole months before it.
   */
  public Verification verify(String space, String airlock) {
    if (blankToNull(space) == null || blankToNull(airlock) == null) {
      throw AuditException.invalid("space and airlock are required");
    }
    return transactions.execute(
        status -> {
          repository.becomeWriter();
          AuditRepository.Extent extent = repository.extent(space, airlock);
          String head = repository.head(space, airlock).orElse(null);
          AuditRepository.Row last = null;
          List<AuditRepository.Row> page;
          while (!(page =
                  repository.chain(space, airlock, last == null ? 0 : last.id(), CHAIN_PAGE))
              .isEmpty()) {
            for (AuditRepository.Row row : page) {
              String reason = check(last, row);
              if (reason != null) {
                return new Verification(
                    space,
                    airlock,
                    "broken",
                    extent.events(),
                    extent.firstId(),
                    extent.lastId(),
                    head,
                    new Broken(row.id(), reason));
              }
              last = row;
            }
          }
          return new Verification(
              space, airlock, "ok", extent.events(), extent.firstId(), extent.lastId(), head, null);
        });
  }

  private static String check(AuditRepository.Row previous, AuditRepository.Row row) {
    if (previous != null && !previous.hash().equals(row.prevHash())) return "removed";
    boolean intact =
        AuditLine.chain(row.prevHash(), row.body()).equals(row.hash())
            && AuditLine.columnsOf(row.body()).equals(row.columns());
    return intact ? null : "altered";
  }

  /** A date or an instant; an upper bound becomes exclusive: the next day, the next microsecond. */
  private static Instant bound(String name, String value, boolean upper) {
    if (blankToNull(value) == null) return null;
    try {
      if (value.length() == 10) {
        LocalDate day = LocalDate.parse(value);
        return (upper ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
      }
      Instant instant = Instant.parse(value).truncatedTo(ChronoUnit.MICROS);
      return upper ? instant.plus(1, ChronoUnit.MICROS) : instant;
    } catch (DateTimeParseException e) {
      throw AuditException.invalid(name + ": a date (2026-10-01) or an instant");
    }
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }
}
