package com.reportix.aisolate.airlock.infrastructure.plugins;

import com.reportix.aisolate.airlock.domain.airlock.AirlockDefinition;
import com.reportix.aisolate.airlock.domain.chat.ExchangeTracing;
import com.reportix.aisolate.airlock.domain.chat.UnknownDataSourceException;
import com.reportix.aisolate.airlock.infrastructure.config.AirlockProperties;
import com.reportix.aisolate.airlock.infrastructure.config.Llms;
import com.reportix.aisolate.config.DataSourceLogin;
import com.reportix.aisolate.identity.IdentityProperties;
import com.reportix.aisolate.sdk.AIsolateManager;
import com.reportix.aisolate.sdk.AirlockRef;
import com.reportix.aisolate.sdk.Llm;
import com.reportix.aisolate.sdk.Principal;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Builds what a handler is handed for one exchange: the asking principal from the validated token's
 * claims, the airlock, its LLMs and its data sources by id, opened once per pod from the env the
 * controller rendered. Built here, outside the chat package, so the chat runtime keeps seeing only
 * the SDK interface.
 */
@Component
public class ExchangeManagerFactory {

  private final Llms llms;
  private final Map<String, DataSource> dataSources;
  private final ExchangeTracing tracing;
  private final String rolesClaim;

  public ExchangeManagerFactory(
      Llms llms,
      AirlockProperties properties,
      IdentityProperties identity,
      ExchangeTracing tracing) {
    this.llms = llms;
    this.tracing = tracing;
    this.rolesClaim = identity.rolesClaim();
    DataSourceLogin.check(properties.datasource());
    this.dataSources =
        properties.datasource().stream()
            .collect(
                Collectors.toUnmodifiableMap(DataSourceLogin::id, ExchangeManagerFactory::open));
  }

  public AIsolateManager createFor(AirlockDefinition airlock, Jwt jwt) {
    com.reportix.aisolate.identity.Principal principal =
        com.reportix.aisolate.identity.Principal.of(jwt.getClaims(), rolesClaim);
    return new ExchangeManager(
        new Principal(principal.sub(), principal.email(), principal.roles()),
        new AirlockRef(airlock.spaceId(), airlock.id(), airlock.revision()),
        llms,
        dataSources,
        tracing);
  }

  // One connection per call; a pool once handlers get hot.
  private static DataSource open(DataSourceLogin entry) {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(entry.url());
    dataSource.setUser(entry.username());
    dataSource.setPassword(entry.password());
    // Seconds: a handler's query fits inside the exchange's budget or fails.
    dataSource.setConnectTimeout(5);
    dataSource.setSocketTimeout(10);
    return dataSource;
  }

  /** One exchange's view; no token, no key: a data source hands out connections. */
  record ExchangeManager(
      Principal principal,
      AirlockRef airlock,
      Llms llms,
      Map<String, DataSource> dataSources,
      ExchangeTracing tracing)
      implements AIsolateManager {

    @Override
    public Llm llm(String id) {
      return llms.get(id);
    }

    @Override
    public DataSource dataSource(String id) {
      DataSource dataSource = id == null ? null : dataSources.get(id);
      if (dataSource == null) {
        throw new UnknownDataSourceException(
            airlock.space() + "/" + airlock.airlock() + " has no data source " + id);
      }
      tracing.readsDataSource(id);
      return dataSource;
    }

    @Override
    public System.Logger log() {
      return System.getLogger("aisolate.handlers");
    }
  }
}
