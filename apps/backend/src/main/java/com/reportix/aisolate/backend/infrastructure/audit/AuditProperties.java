package com.reportix.aisolate.backend.infrastructure.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The audit trail's settings ({@code aisolate.audit.*}).
 *
 * @param ingestKey the install's key every airlock's ingest token is derived from, shared with the
 *     controller only; blank: no line is admitted
 * @param ingestPort the port the airlock pods hand their lines over on, apart from the API's
 * @param retentionMonths whole months kept before the current one; older months are dropped
 * @param entity the organisation the export's XBRL report is of: an LEI or {@code <scheme>:<id>}
 */
@ConfigurationProperties("aisolate.audit")
public record AuditProperties(
    String ingestKey,
    @DefaultValue("8085") int ingestPort,
    @DefaultValue("84") int retentionMonths,
    String entity) {}
