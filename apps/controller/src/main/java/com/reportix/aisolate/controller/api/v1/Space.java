package com.reportix.aisolate.controller.api.v1;

import com.reportix.aisolate.config.SpaceDocument;
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
 * <p>The {@code Space} document as a custom resource: the roles every one of its airlocks admits,
 * the LLMs and data sources its airlocks share; one release with the Airlocks naming it.
 */
@ValidationRule(
    value = "self.metadata.name.matches('^[a-z][a-z0-9-]{1,40}$')",
    message =
        "metadata.name must be a slug: 2 to 41 lowercase letters, digits or hyphens, starting with"
            + " a letter")
@Group("aisolate.dev")
@Version("v1")
@Kind("Space")
@Plural("spaces")
@AdditionalPrinterColumn(name = "PHASE", jsonPath = ".status.phase")
@AdditionalPrinterColumn(name = "REVISION", jsonPath = ".status.revision")
public class Space extends CustomResource<SpaceDocument.Spec, ResourceStatus>
    implements Namespaced {}
