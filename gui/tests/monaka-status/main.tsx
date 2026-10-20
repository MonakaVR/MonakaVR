import { Profiler, StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { FluentBundle } from '@fluent/bundle';
import { MonakaTrackingStatusPanel } from '../../src/components/monaka-status/MonakaTrackingStatusPanel';
import type { InputSnapshot } from '../../src/monaka-input/controller';
import type { TrackingConnection } from '../../src/monaka-status/view-model';
import { fixtures } from './fixtures';
import '../../src/index.scss';

declare global {
  interface Window {
    monakaStatusTest: {
      update: (
        snapshot: InputSnapshot | null,
        connection: TrackingConnection
      ) => void;
      repeat: () => void;
      commits: () => number;
    };
  }
}

const l10n = new ReactLocalization([new FluentBundle('en')]);
let commits = 0;
function Harness() {
  const initial =
    fixtures.find(
      (x) => x.name === new URLSearchParams(location.search).get('fixture')
    ) ?? fixtures[0];
  const [state, setState] = useState({
    snapshot: initial.snapshot,
    connection: initial.connection,
  });
  const [selected, setSelected] = useState(initial.name);
  const [instance, setInstance] = useState(0);
  const [, rerender] = useState(0);
  useEffect(() => {
    window.monakaStatusTest = {
      update: (snapshot, connection) => setState({ snapshot, connection }),
      repeat: () => rerender((value) => value + 1),
      commits: () => commits,
    };
  }, []);
  return (
    <LocalizationProvider l10n={l10n}>
      <main className="mx-auto max-w-7xl p-5 space-y-4">
        <h1 className="text-xl font-bold">
          SOFTWARE TEST MODE — injected snapshots, Core not connected
        </h1>
        <label>
          Fixture{' '}
          <select
            aria-label="Fixture"
            value={selected}
            className="bg-background-80 text-background-10 rounded px-2 py-1"
            onChange={(event) => {
              const fixture = fixtures.find(
                (x) => x.name === event.target.value
              )!;
              setSelected(fixture.name);
              setState({
                snapshot: fixture.snapshot,
                connection: fixture.connection,
              });
              setInstance((value) => value + 1);
            }}
          >
            {fixtures.map((fixture) => (
              <option key={fixture.name}>{fixture.name}</option>
            ))}
          </select>
        </label>
        <Profiler
          id="status"
          onRender={() => {
            commits++;
          }}
        >
          <MonakaTrackingStatusPanel
            key={instance}
            snapshot={state.snapshot}
            connection={state.connection}
          />
        </Profiler>
      </main>
    </LocalizationProvider>
  );
}
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <Harness />
  </StrictMode>
);
