package com.reportix.aisolate.controller.api.v1;

import com.reportix.aisolate.config.AirlockDocument;
import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.generator.annotation.ValidationRule;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The API server refuses at admission what the loader would refuse a moment later: the CEL rules
 * below are the loader's field checks; references stay the reconciler's.
 *
 * <p>The {@code Airlock} document as a custom resource: the Space it belongs to, who may ask it
 * besides the space's users (none of its own is fine; a calling client is required), the model that
 * answers with its prompt and tools, its code, and the LLMs and data sources it sees beside the
 * space's. One pod pair per airlock; no {@code llm} or {@code prompt} is admitted (the AIsolate UI
 * creates it so, a deploy brings them) and runs nothing.
 */
@ValidationRule(
    value = "self.metadata.name.matches('^[a-z][a-z0-9-]{1,40}$')",
    message =
        "metadata.name must be a slug: 2 to 41 lowercase letters, digits or hyphens, starting with"
            + " a letter")
@ValidationRule(value = "size(self.spec.space) > 0", message = "spec.space is required")
@ValidationRule(
    value = "has(self.spec.access) && size(self.spec.access.callers) > 0",
    message = "spec.access.callers must name at least one calling client")
@Group("aisolate.dev")
@Version("v1")
@Kind("Airlock")
@Plural("airlocks")
@AdditionalPrinterColumn(name = "SPACE", jsonPath = ".spec.space")
@AdditionalPrinterColumn(name = "PHASE", jsonPath = ".status.phase")
@AdditionalPrinterColumn(
    name = "READY",
    jsonPath = ".status.readyReplicas",
    type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "REVISION", jsonPath = ".status.revision")
public class Airlock extends CustomResource<AirlockDocument.Spec, ResourceStatus>
    implements Namespaced {}
