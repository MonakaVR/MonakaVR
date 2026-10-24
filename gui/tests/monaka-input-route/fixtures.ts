import type { Page, WebSocketRoute } from '@playwright/test';
import type {
  InputAssignment,
  InputCandidate,
  InputSnapshot,
} from '../../src/monaka-input/controller';

export const routeUrl = '/?ip=127.0.0.1&port=29992#/settings/monaka-input';

export function candidate(
  id: string,
  capabilities: string[] | null = ['position', 'orientation']
): InputCandidate {
  return {
    id,
    publisher: id,
    source: 'opaque-source',
    tracker: 'same-name',
    origin: 'mvip_pose',
    capabilities,
    conflict: false,
    qSelectable: capabilities?.includes('orientation') ?? false,
    sixDofSelectable: capabilities?.includes('position') ?? false,
    qUsableNow: false,
    sixDofUsableNow: false,
  };
}

/** Public consumer GUI RPC fixture. Not an MVIP producer or replacement codec. */
export class CoreUiFixture {
  snapshot: InputSnapshot = {
    generation: '0',
    bodies: ['HIP', 'CHEST'],
    candidates: [
      candidate('publisher-a'),
      candidate('publisher-b'),
      candidate('q-only', ['orientation']),
      candidate('unknown', null),
      {
        ...candidate('slime-q', ['orientation']),
        origin: 'slime_orientation_stream',
      },
    ],
    assignments: [],
  };
  requests: Record<string, unknown>[] = [];
  connections: WebSocketRoute[] = [];
  restartRequired = false;
  connected = true;
  async install(page: Page) {
    await page.addInitScript(() => {
      localStorage.setItem(
        'config.json',
        JSON.stringify({
          uuid: 'issue-15-software-fixture',
          doneOnboarding: true,
          lang: 'en',
          errorTracking: false,
          discordPresence: false,
          skeletonPreview: false,
          homeLayout: 'table',
        })
      );
      localStorage.setItem('i18nextLng', 'en');
    });
    await page.routeWebSocket('ws://127.0.0.1:29992', (socket) => {
      if (!this.connected) {
        socket.close();
        return;
      }
      this.connections.push(socket);
      socket.onMessage((raw) => {
        if (typeof raw !== 'string') return; // Unchanged normal SolarXR channel.
        const request = JSON.parse(raw);
        if (request.type !== 'monaka_inputs_v1') throw new Error('Unexpected text RPC');
        this.requests.push(request);
        const response = { type: request.type, id: request.id };
        if (request.action === 'save') {
          if (this.restartRequired || request.generation !== this.snapshot.generation) {
            socket.send(
              JSON.stringify({
                ...response,
                ok: false,
                error: this.restartRequired
                  ? 'Saved mode requires restart before changing inputs'
                  : 'Assignment generation changed',
              })
            );
            return;
          }
          const assignment: InputAssignment = {
            body: request.body,
            qSource: request.qSource,
            sixDofSource: request.sixDofSource,
            manualSix: request.manualSix,
            mode: 'ik',
          };
          this.snapshot.assignments = [
            ...this.snapshot.assignments.filter((row) => row.body !== assignment.body),
            assignment,
          ];
          this.snapshot.generation = String(BigInt(this.snapshot.generation) + 1n);
        } else if (request.action !== 'snapshot') {
          throw new Error('Unexpected input action');
        }
        socket.send(JSON.stringify({ ...response, ok: true, ...this.snapshot }));
      });
    });
  }
  disconnect() {
    this.connected = false;
    this.connections.forEach((socket) => socket.close());
    this.connections = [];
  }
  reconnect() {
    this.connected = true;
  }
  get saves() {
    return this.requests.filter((request) => request.action === 'save');
  }
}

export async function forbidBridgeApi(page: Page) {
  await page.addInitScript(() => {
    Object.defineProperty(window, 'electronAPI', {
      configurable: true,
      value: undefined,
    });
  });
}
