import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { FluentBundle, FluentResource } from '@fluent/bundle';
import { HomeInputCatalog } from '../../src/components/home/HomeInputCatalog';
import type { InputSnapshot } from '../../src/monaka-input/controller';
import { snapshot } from './fixtures';
import '../../src/index.scss';

declare global {
  interface Window {
    homeInputTest: {
      snapshot: InputSnapshot;
      pause: boolean;
      requests: Record<string, unknown>[];
      urls: string[];
      disconnect: () => void;
    };
  }
}
const sockets: FakeSocket[] = [];
class FakeSocket {
  static OPEN = 1;
  readyState = 0;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  constructor(url: string) {
    window.homeInputTest.urls.push(url);
    sockets.push(this);
    setTimeout(() => {
      this.readyState = 1;
      this.onopen?.();
    }, 0);
  }
  send(data: string) {
    const request = JSON.parse(data);
    window.homeInputTest.requests.push(request);
    if (window.homeInputTest.pause) return;
    setTimeout(
      () =>
        this.onmessage?.({
          data: JSON.stringify({
            ...request,
            ok: true,
            ...window.homeInputTest.snapshot,
          }),
        }),
      0
    );
  }
  close() {
    this.readyState = 3;
    this.onclose?.();
  }
}
window.homeInputTest = {
  snapshot: snapshot(),
  pause: false,
  requests: [],
  urls: [],
  disconnect: () => sockets.at(-1)?.close(),
};
window.WebSocket = FakeSocket as unknown as typeof WebSocket;
const bundle = new FluentBundle('ja', { useIsolating: false });
bundle.addResource(
  new FluentResource(await (await fetch('/i18n/ja/translation.ftl')).text())
);
createRoot(document.getElementById('root')!).render(
  <LocalizationProvider l10n={new ReactLocalization([bundle])}>
    <MemoryRouter>
      <main className="p-4">
        <p>SOFTWARE FIXTURE — fake Core WebSocket, no devices</p>
        <HomeInputCatalog />
      </main>
    </MemoryRouter>
  </LocalizationProvider>
);
