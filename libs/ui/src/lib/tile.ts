/** A stable hue (0..359) for an id, so a space or airlock always gets the same tile wash. */
export function tileHue(id: string): number {
  let hue = 0;
  for (const ch of id) hue = (hue * 31 + (ch.codePointAt(0) ?? 0)) % 360;
  return hue;
}
