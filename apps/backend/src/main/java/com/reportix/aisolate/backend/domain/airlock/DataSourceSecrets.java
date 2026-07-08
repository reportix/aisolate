package com.reportix.aisolate.backend.domain.airlock;

import com.reportix.aisolate.backend.infrastructure.cluster.ClusterProperties;
import com.reportix.aisolate.backend.infrastructure.cluster.Credentials;
import com.reportix.aisolate.config.GlobalDocument;
import io.fabric8.kubernetes.api.model.HasMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The Secrets behind the data sources and the models' API keys of a space, an airlock or the
 * install, the same for all ({@link SecretRow}): a row keeps its Secret until a typed credential
 * replaces it, a new Secret is owned by the resource that defines the row, and the Secrets a save
 * stops naming go once it has committed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataSourceSecrets {

  /**
   * Whose data sources these are.
   *
   * @param word what a message calls it: install, space or airlock
   * @param label how logs name it
   * @param prefix its Secrets' names, before {@code -<kind>-<id>-<6 random>} ({@link
   *     SecretRow#secretKind})
   */
  public record Owner(String word, String label, String prefix) {

    /** The install's, owned by its Global resource: {@code aisolate-ds-<id>-<6 random>}. */
    public static Owner ofGlobal() {
      return new Owner("install", GlobalDocument.NAME, GlobalDocument.NAME);
    }

    public static Owner ofSpace(String space) {
      return new Owner("space", space, space);
    }

    /**
     * An airlock's, owned by its Airlock resource: {@code <space>-<airlock>-ds-<id>-<6 random>}.
     */
    public static Owner ofAirlock(String space, String airlock) {
      return new Owner("airlock", space + "/" + airlock, space + "-" + airlock);
    }

    /** The new Secret's name before its random suffix; a Secret name holds no {@code _}. */
    String secretName(SecretRow<?> row) {
      return prefix + "-" + row.secretKind() + "-" + row.id().replace('_', '-');
    }

    /**
     * A name {@link #secretName} could have given this owner for the row. Ids may hold {@code
     * -ds-}, so another owner's name can match too: only a backend without a cluster, whose names
     * reach no pod, relies on this.
     */
    boolean keeps(SecretRow<?> row) {
      return row.secretRef().matches(Pattern.quote(secretName(row) + "-") + "[a-z0-9]{6}");
    }
  }

  private final Credentials credentials;
  private final ClusterProperties cluster;
  private final ApplicationEventPublisher events;

  /** Each row without a Secret name keeps the stored row's of its id; a dropped id loses its. */
  public static <R extends SecretRow<R>> List<R> keepRefs(List<R> stored, List<R> rows) {
    Map<String, String> refs =
        stored.stream().collect(Collectors.toMap(R::id, R::secretRef, (a, b) -> a));
    return rows.stream()
        .map(
            row ->
                row.secretRef().isEmpty()
                    ? row.withSecretRef(refs.getOrDefault(row.id(), ""))
                    : row)
        .toList();
  }

  /**
   * The form's own errors, then its rows' ({@link #problems}).
   *
   * @param stored the owner's rows as saved, none for a create
   * @throws InvalidFormException any of them, all named
   */
  public <R extends SecretRow<R>> void requireValid(
      Owner owner, List<String> formErrors, List<R> rows, List<R> stored) {
    List<String> errors = new ArrayList<>(formErrors);
    errors.addAll(problems(owner, rows, stored));
    if (!errors.isEmpty()) {
      throw new InvalidFormException(errors);
    }
  }

  /**
   * A typed credential needs a cluster. With a cluster, a row keeps only the Secret the stored row
   * of its id names, and only while its URL stays: a sent name could otherwise point the pods at
   * another owner's Secret, and a new host would be sent the old credential. Without one, a kept
   * name need only be one AIsolate would give the owner.
   *
   * @param stored the owner's rows as saved, none for a create
   */
  public <R extends SecretRow<R>> List<String> problems(Owner owner, List<R> rows, List<R> stored) {
    Map<String, R> before =
        stored.stream().collect(Collectors.toMap(R::id, Function.identity(), (a, b) -> a));
    List<String> errors = new ArrayList<>();
    for (int i = 0; i < rows.size(); i++) {
      R row = rows.get(i);
      String at = row.listField() + "[" + i + "].";
      R kept = before.get(row.id());
      boolean storedRef =
          !row.secretRef().isEmpty() && kept != null && kept.secretRef().equals(row.secretRef());
      if (row.bringsCredentials()) {
        if (!cluster.enabled()) {
          errors.add(
              at + row.credentialField() + ": no cluster is configured to keep it in a Secret");
        }
      } else if (row.secretRef().isEmpty()) {
        continue;
      } else if (cluster.enabled() ? !storedRef : !owner.keeps(row)) {
        errors.add(at + "secret_ref: not a Secret of this " + owner.word());
      } else if (cluster.enabled() && !kept.url().equals(row.url())) {
        errors.add(at + row.credentialField() + ": required again with a new url");
      }
    }
    return errors;
  }

  /**
   * The saved owner as it is when no row brings a credential; else a Secret for each row that does,
   * owned by the owner's resource, and the owner again with the rows naming them. A refusal deletes
   * the Secrets already made and refuses the save: the caller's transaction rolls back, so no row
   * names a Secret the cluster does not hold.
   *
   * @param saved the owner's row as the save just wrote it
   * @param resource found or created inside the refusal's reach: a cluster that is gone refuses the
   *     save like one that refuses a Secret
   * @param rows the form's, with the credentials the stored row never holds
   * @param writeRefs writes the rows with their Secret names and returns the owner's row again
   */
  public <T, R extends SecretRow<R>> T storeIfAny(
      Owner owner,
      List<R> rows,
      T saved,
      Supplier<HasMetadata> resource,
      Function<List<R>, T> writeRefs) {
    if (rows.stream().noneMatch(SecretRow::bringsCredentials)) {
      return saved;
    }
    return writeRefs.apply(store(owner, resource, rows));
  }

  private <R extends SecretRow<R>> List<R> store(
      Owner owner, Supplier<HasMetadata> resource, List<R> rows) {
    List<String> created = new ArrayList<>();
    try {
      HasMetadata parent = resource.get();
      List<R> stored = new ArrayList<>();
      for (R row : rows) {
        if (row.bringsCredentials()) {
          String name = credentials.create(parent, owner.secretName(row), row.secretData());
          created.add(name);
          stored.add(row.withSecretRef(name));
        } else {
          stored.add(row);
        }
      }
      return stored;
    } catch (RuntimeException refused) {
      // The cluster's message names the request, never the Secret's data.
      log.warn(
          "{} {}: the cluster did not take the credentials: {}",
          owner.word(),
          owner.label(),
          refused.getMessage());
      // Not left to the rollback alone: a rollback that fails runs no listener.
      created.forEach(this::deleteQuietly);
      throw new InvalidFormException(
          IntStream.range(0, rows.size())
              .filter(i -> rows.get(i).bringsCredentials())
              .mapToObj(
                  i ->
                      rows.get(i).listField()
                          + "["
                          + i
                          + "]."
                          + rows.get(i).credentialField()
                          + ": the cluster did not take the credentials; nothing was saved")
              .toList());
    }
  }

  /** The Secrets {@code before} named and {@code after} does not go once the save committed. */
  public <R extends SecretRow<R>> void publishReplaced(List<R> before, List<R> after) {
    Set<String> kept = after.stream().map(SecretRow::secretRef).collect(Collectors.toSet());
    List<String> replaced =
        before.stream()
            .map(SecretRow::secretRef)
            .filter(ref -> !ref.isEmpty() && !kept.contains(ref))
            .distinct()
            .toList();
    if (!replaced.isEmpty()) {
      events.publishEvent(new SecretsReplaced(replaced));
    }
  }

  private void deleteQuietly(String secret) {
    try {
      credentials.delete(secret);
    } catch (RuntimeException failed) {
      log.warn("secret {} could not be deleted: {}", secret, failed.getMessage());
    }
  }
}
