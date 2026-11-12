"use client";

import { Section } from "@aisolate/ui";
import { DefinitionRow } from "@/components/Definition";
import { env } from "@/lib/env";

/**
 * Settings: one section so far, About, for everyone signed in: the running version and where its
 * source is, the offer AGPL-3.0 section 13 asks of a program used over a network.
 */
export default function SettingsPage() {
  return (
    <div className="grid gap-6">
      <Section title="About" description="This AIsolate, and where its source is.">
        <dl className="divide-y divide-hairline">
          <DefinitionRow label="Version">AIsolate {env.version}</DefinitionRow>
          <DefinitionRow label="Source">
            <a href={env.sourceUrl} className="underline hover:text-ink">
              {env.sourceUrl}
            </a>
          </DefinitionRow>
          <DefinitionRow label="License">
            GNU Affero General Public License, version 3 only. The source of the version you are
            using is at the address above.
          </DefinitionRow>
        </dl>
      </Section>
    </div>
  );
}
