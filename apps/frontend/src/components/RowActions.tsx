import { Button, PencilIcon, TrashIcon } from "@aisolate/ui";

/** Given where the page edits its rows: the pencil opens the row's form above the list, the trash removes at once. */
export type Actions = {
  onEdit: (id: string) => void;
  busy: boolean;
  onRemove: (id: string) => void;
};

/** A row's pencil and trash, named for screen readers by what the row is: "model granite". */
export function RowActions({
  actions,
  what,
}: {
  actions: Actions;
  what: { noun: string; id: string };
}) {
  const name = `${what.noun} ${what.id}`;
  return (
    // The 32px buttons overhang the cell's padding, so the row keeps its height; px-0! beats the
    // button's px-3, which would leave the icon 6px.
    <span className="-my-1.5 flex items-center justify-end gap-2">
      <Button
        variant="secondary"
        className="w-8 px-0!"
        aria-label={`Edit ${name}`}
        title="Edit"
        onClick={() => actions.onEdit(what.id)}
      >
        <PencilIcon size={16} />
      </Button>
      <Button
        variant="secondary"
        className="w-8 px-0!"
        aria-label={`Remove ${name}`}
        title="Remove"
        disabled={actions.busy}
        onClick={() => actions.onRemove(what.id)}
      >
        <TrashIcon size={16} />
      </Button>
    </span>
  );
}
