package com.reportix.aisolate.controller.api.v1;

import com.reportix.aisolate.config.GlobalDocument;
import io.fabric8.crd.generator.annotation.AdditionalPrinterColumn;
import io.fabric8.generator.annotation.ValidationRule;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * The {@code Global} document as a custom resource: the LLMs and data sources every space inherits,
 * one per install, named {@code aisolate}. Its status holds the entries the controller left out of
 * every catalog.
 */
@ValidationRule(
    value = "self.metadata.name == '" + GlobalDocument.NAME + "'",
    message = "metadata.name must be " + GlobalDocument.NAME)
@Group("aisolate.dev")
@Version("v1")
@Kind("Global")
@Plural("globals")
@AdditionalPrinterColumn(name = "PHASE", jsonPath = ".status.phase")
public class Global extends CustomResource<GlobalDocument.Spec, ResourceStatus>
    implements Namespaced {}
