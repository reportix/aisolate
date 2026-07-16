package com.reportix.aisolate.controller.reconciler.global;

import com.reportix.aisolate.config.ConfigurationException;
import com.reportix.aisolate.config.ConfigurationLoader;
import com.reportix.aisolate.config.GlobalDocument;
import com.reportix.aisolate.config.Metadata;
import com.reportix.aisolate.controller.api.v1.Global;
import com.reportix.aisolate.controller.api.v1.ResourceStatus;
import com.reportix.aisolate.controller.reconciler.Releases;
import com.reportix.aisolate.controller.release.Release;
import com.reportix.aisolate.controller.render.ControllerProperties;
import com.reportix.aisolate.controller.render.Renderer;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ErrorStatusUpdateControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * The Global's status alone: every airlock's reconciler serves it. {@code Invalid} when the loader
 * refuses it (no release serves it then); {@code Ready} otherwise, listing the entries left out of
 * every catalog.
 */
@RequiredArgsConstructor
public class GlobalReconciler implements Reconciler<Global> {

  private final ControllerProperties settings;

  @Override
  public UpdateControl<Global> reconcile(Global global, Context<Global> context) {
    Long generation = global.getMetadata().getGeneration();
    GlobalDocument document =
        new GlobalDocument(new Metadata(global.getMetadata().getName()), global.getSpec());
    try {
      ConfigurationLoader.parse(Renderer.buildFiles(Stream.of(document)));
    } catch (ConfigurationException invalid) {
      return Releases.patchIfChanged(
          global, ResourceStatus.invalid(generation, invalid.problems()));
    }
    List<String> leftOut = Release.ServedGlobal.of(document, settings.egress()).problems();
    return Releases.patchIfChanged(global, ResourceStatus.ready(null, generation, null, leftOut));
  }

  @Override
  public ErrorStatusUpdateControl<Global> updateErrorStatus(
      Global global, Context<Global> context, Exception e) {
    return Releases.patchApplyFailed(global, e);
  }
}
