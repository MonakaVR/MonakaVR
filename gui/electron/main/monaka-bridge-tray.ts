import type { MenuItemConstructorOptions } from 'electron';

// Navigation only. No policy write or separate Bridge window/process.
export function bridgeTrayEntry(
  show: () => void,
  navigate: () => void
): MenuItemConstructorOptions {
  return {
    label: 'Monaka Bridge…',
    click: () => {
      show();
      navigate();
    },
  };
}
