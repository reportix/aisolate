package com.reportix.aisolate.controller.api.v1;

import com.reportix.aisolate.config.AirlockVersionDocument;
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
 * <p>The {@code AirlockVersion} document as a custom resource, named {@code <airlock>-<version>};
 * one pod pair per version.
 */
@ValidationRule(
    value = "self.metadata.name == self.spec.airlock + '-' + self.spec.version",
    message = "metadata.name must be <airlock>-<version>")
@ValidationRule(
    value = "self.spec.version.matches('^[a-z0-9]([a-z0-9-]{0,29}[a-z0-9])?$')",
    message =
        "spec.version must be 1 to 31 lowercase letters, digits or dashes, starting and ending with"
            + " a letter or a digit")
// size(), not trim(): the API server's CEL cost budget; the loader still refuses a blank one.
@ValidationRule(value = "size(self.spec.llm) > 0", message = "spec.llm is required")
@ValidationRule(value = "size(self.spec.prompt) > 0", message = "spec.prompt is required")
@Group("aisolate.dev")
@Version("v1")
@Kind("AirlockVersion")
@Plural("airlockversions")
@AdditionalPrinterColumn(name = "AIRLOCK", jsonPath = ".spec.airlock")
@AdditionalPrinterColumn(name = "VERSION", jsonPath = ".spec.version")
@AdditionalPrinterColumn(name = "PHASE", jsonPath = ".status.phase")
@AdditionalPrinterColumn(
    name = "READY",
    jsonPath = ".status.readyReplicas",
    type = AdditionalPrinterColumn.Type.INTEGER)
@AdditionalPrinterColumn(name = "REVISION", jsonPath = ".status.revision")
public class AirlockVersion extends CustomResource<AirlockVersionDocument.Spec, ResourceStatus>
    implements Namespaced {}
