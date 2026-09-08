package com.reportix.aisolate.cli;

import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** The developer's verbs: sign in and out. */
@Command(
    name = "aisolate",
    mixinStandardHelpOptions = true,
    versionProvider = AIsolateCli.Version.class,
    description = "AIsolate projects: sign in to your space's AIsolate and out.")
public final class AIsolateCli implements Runnable {

  static final class Version implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
      return new String[] {"aisolate " + VersionConstants.VERSION};
    }
  }

  @Spec CommandSpec spec;

  // Subcommands are added as instances so they take their collaborators through the constructor.
  static CommandLine commandLine(Console console) {
    // HTTP/1.1 only: on plain http the default (HTTP/2) sends an h2c Upgrade, which an identity
    // provider behind a reverse proxy may accept and the proxy then garbles.
    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    Path home = Session.home();
    java.io.Console terminal = System.console();
    boolean interactive = terminal != null && terminal.isTerminal();
    // Without a terminal `login` waits for no Enter: an open pipe (ssh without -t, CI) would
    // otherwise hold the browser back for the whole timeout.
    InputStream keys = interactive ? System.in : InputStream.nullInputStream();
    return new CommandLine(new AIsolateCli())
        .addSubcommand(
            "login",
            new LoginCommand(console, http, home, BrowserLogin::open, keys, BrowserLogin.TIMEOUT))
        .addSubcommand("logout", new LogoutCommand(console, http, home));
  }

  @Override
  public void run() {
    spec.commandLine().usage(spec.commandLine().getOut());
  }

  public static void main(String[] args) {
    System.exit(execute(args, System.out, System.err, SystemConsole.colorful()));
  }

  static int execute(String[] args, PrintStream out, PrintStream err, boolean color) {
    return commandLine(new SystemConsole(out, err, color))
        .setOut(new PrintWriter(out, true))
        .setErr(new PrintWriter(err, true))
        .execute(args);
  }
}
