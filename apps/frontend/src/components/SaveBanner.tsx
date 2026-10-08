import { Banner } from "@aisolate/ui";
import { STALE } from "@/lib/api";

/** The banner of a page that saves: a save refused as stale offers the reload it asks for. */
export function SaveBanner({ message }: { message: string }) {
  return message === STALE ? (
    <Banner message={message} actionLabel="Reload" onAction={() => window.location.reload()} />
  ) : (
    <Banner message={message} />
  );
}
