import React from 'react';
import { createRoot } from 'react-dom/client';
import { FluentBundle } from '@fluent/bundle';
import { LocalizationProvider, ReactLocalization } from '@fluent/react';
import { TrackingModeControl } from '../../src/components/monaka-mode/TrackingModeControl';
import {
  TrackingModeController,
  type ModeSnapshot,
} from '../../src/monaka-mode/mode-controller';
import '../../src/index.scss';

// Empty bundle deliberately exercises fallback before owner D's translations are merged.
const localization = new ReactLocalization(
  [new FluentBundle('en')],
  null,
  () => {}
);
const value: ModeSnapshot = {
  generation: '3',
  readbackToken: 'browser-fixture-token',
  restartRequired: false,
  backgroundIkAlignmentConfirmed: false,
  bodies: ['HIP', 'LEFT_FOOT'],
  assignments: ['HIP', 'LEFT_FOOT'].map((body) => ({
    body,
    mode: 'ik',
    activeMode: 'ik',
    savedMode: 'ik',
    restartRequired: false,
    modeOptions: ['ik', 'direct', 'hybrid'].map((mode) => ({
      mode: mode as 'ik' | 'direct' | 'hybrid',
      available: true,
      reason: null,
    })),
  })),
};
let requests = 0;
const params = new URLSearchParams(location.search);
if (params.has('unknown')) {
  value.assignments[0].mode = 'future';
  value.assignments[0].activeMode = 'future';
  value.assignments[0].savedMode = 'future';
}
const model = new TrackingModeController({
  async request(fields) {
    if (fields.action === 'save_mode') {
      requests++;
      await new Promise((resolve) => setTimeout(resolve, 150));
      const row = value.assignments.find((row) => row.body === fields.body)!;
      row.savedMode = fields.mode as string;
      row.restartRequired = true;
      value.restartRequired = true;
      value.readbackToken = 'browser-committed-token';
    }
    return structuredClone(value);
  },
});
createRoot(document.getElementById('root')!).render(
  <LocalizationProvider l10n={localization}>
    <main className="p-8 max-w-4xl">
      <p>SOFTWARE COMPONENT FIXTURE / NOT WIRED TO NORMAL TRACKER GUI</p>
      <TrackingModeControl model={model} />
      <button onClick={() => model.disconnect()}>Simulate disconnect</button>
      <button
        onClick={() => {
          document.body.dataset.saves = String(requests);
        }}
      >
        Read fixture save count
      </button>
    </main>
  </LocalizationProvider>
);
void model.load();
