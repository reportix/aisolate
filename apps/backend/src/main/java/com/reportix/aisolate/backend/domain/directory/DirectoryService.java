package com.reportix.aisolate.backend.domain.directory;

import com.reportix.aisolate.config.Names;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * The directory as the AIsolate UI uses it, when one is configured: search people to add, name the
 * users a space or an airlock lists, and refuse adding an e-mail nobody signs in with. Without one,
 * users are typed e-mails, as before. Admission never asks it: the lists are the truth.
 */
@Service
public class DirectoryService {

  private final Optional<Directory> directory;

  public DirectoryService(Optional<Directory> directory) {
    this.directory = directory;
  }

  public boolean configured() {
    return directory.isPresent();
  }

  /**
   * @throws NotConfiguredException no directory is configured
   */
  public List<Directory.Person> search(String text) {
    Directory people = directory.orElseThrow(NotConfiguredException::new);
    return text == null || text.isBlank() ? List.of() : people.search(text.strip());
  }

  /**
   * A space's users, or an airlock's own and those it inherits, each with who they are. One listed
   * on both counts as its own; e-mails compare without case.
   *
   * @param fromSpace the space's users for an airlock; empty for the space itself
   */
  public UsersDTO users(List<String> own, List<String> fromSpace) {
    Set<String> owned = own.stream().map(DirectoryService::key).collect(Collectors.toSet());
    List<String> inherited =
        fromSpace.stream().filter(email -> !owned.contains(key(email))).toList();
    return new UsersDTO(configured(), describe(own), describe(inherited));
  }

  // One lookup per e-mail; a batch query if lists grow past a few dozen.
  private List<UserDTO> describe(List<String> emails) {
    return emails.stream()
        .map(
            email ->
                directory
                    .map(
                        people ->
                            people
                                .findByEmail(email)
                                .map(p -> new UserDTO(email, p.name(), p.username(), true, null))
                                .orElse(new UserDTO(email, null, null, false, null)))
                    .orElse(new UserDTO(email, null, null, null, null)))
        .toList();
  }

  /**
   * One error per e-mail the save adds that nobody signs in with; users kept from before pass even
   * when they have left. Malformed lists are the form's own error, so they are not looked up.
   */
  public List<String> notInDirectory(Collection<String> before, List<String> after) {
    return notInDirectory("users", before, after);
  }

  /** {@link #notInDirectory(Collection, List)}, the errors on {@code field}. */
  public List<String> notInDirectory(String field, Collection<String> before, List<String> after) {
    if (directory.isEmpty() || !Names.userProblems(after).isEmpty()) {
      return List.of();
    }
    Set<String> kept = before.stream().map(DirectoryService::key).collect(Collectors.toSet());
    return after.stream()
        .filter(email -> !kept.contains(key(email)))
        .distinct()
        .filter(email -> directory.get().findByEmail(email).isEmpty())
        .map(email -> field + ": " + email + " is not in the directory")
        .toList();
  }

  private static String key(String email) {
    return email.toLowerCase(Locale.ROOT);
  }

  /**
   * @param directory whether a directory is configured; without one the UI asks for e-mails
   * @param inherited an airlock's users that come from its space; empty for a space
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UsersDTO(boolean directory, List<UserDTO> users, List<UserDTO> inherited) {

    /** A space's members: each listed user with its role. */
    public UsersDTO withRoles(Function<String, String> roleOf) {
      return new UsersDTO(
          directory,
          users.stream().map(u -> u.withRole(roleOf.apply(u.email()))).toList(),
          inherited);
    }
  }

  /**
   * @param inDirectory false when the e-mail no longer resolves; null without a directory
   * @param role a space member's role; null on an airlock's rows, which have none
   */
  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record UserDTO(
      String email, String name, String username, Boolean inDirectory, String role) {

    UserDTO withRole(String role) {
      return new UserDTO(email, name, username, inDirectory, role);
    }
  }

  public static class NotConfiguredException extends RuntimeException {
    public NotConfiguredException() {
      super("no directory is connected");
    }
  }
}
