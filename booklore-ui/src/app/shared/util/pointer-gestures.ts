/** Whether the primary (usually left) button is held; `buttons` is a bitmask of held buttons. */
export function isPrimaryButtonHeld(event: MouseEvent): boolean {
  return (event.buttons & 1) !== 0;
}
