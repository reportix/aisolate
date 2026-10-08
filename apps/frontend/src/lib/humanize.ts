/**
 * A class name as words for a title: "HrDocumentsContextPreparator" becomes "Hr documents
 * context preparator". Splits on case changes and digits, keeps an acronym ("LLM")
 * whole, sentence case.
 */
export function humanize(name: string): string {
  const words = name.match(/\p{Lu}+(?!\p{Ll})|\p{Lu}?\p{Ll}+|\d+/gu) ?? [];
  const sentence = words.map((w) => (/^\p{Lu}+$/u.test(w) ? w : w.toLowerCase())).join(" ");
  return sentence.charAt(0).toUpperCase() + sentence.slice(1);
}
