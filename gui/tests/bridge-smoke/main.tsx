// Test-only renderer entry. Not imported by App or packaged renderer inputs.
import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { MonakaBridgeSettings } from '../../src/components/settings/pages/MonakaBridgeSettings';
import type {
  BridgeConfiguration,
  BridgeDesktopApi,
  BridgeHealthRead,
  BridgePolicy,
} from '../../electron/bridge-types';
import '../../src/index.scss';
import '@fontsource/poppins/500.css';

class FixtureApi implements BridgeDesktopApi {
  saved: BridgeConfiguration = {
    locations: {
      configPath: 'SOFTWARE TEST FIXTURE / bridge.json',
      validatorPath: 'SOFTWARE TEST FIXTURE / validator',
    },
    token: 'fixture',
    policy: 'steamvr',
    revision: 1,
    publisher: 'fixture-publisher',
    mappings: [],
  };
  fail = false;
  disconnected = false;
  old = false;
  future = false;
  attempts = 0;
  cancelled = new Set<string>();
  async load() {
    await new Promise((resolve) => setTimeout(resolve, 100));
    return structuredClone(this.saved);
  }
  async choose() {
    return null;
  }
  async health(): Promise<BridgeHealthRead> {
    if (this.disconnected)
      return {
        configPath: this.saved.locations.configPath,
        value: null,
        error: 'Injected disconnected diagnostic source.',
      };
    return {
      configPath: this.saved.locations.configPath,
      error: null,
      value: {
        publisher_id: 'fixture-publisher',
        policy: this.saved.policy,
        mapping_revision: this.saved.revision,
        snapshot_unix_ms:
          Date.now() + (this.old ? -10000 : this.future ? 10000 : -5),
        devices: [
          {
            publisher_id: 'fixture-publisher',
            source: 'fixture-source',
            device: 'fixture-device',
            tracker: 'fixture-tracker',
            tracking_state: 'degraded',
            fresh: true,
            pose_age_ms: 50,
            position_valid: true,
            orientation_valid: false,
            collision: false,
          },
        ],
      },
    };
  }
  async apply(_token: string, policy: BridgePolicy, id: string) {
    ++this.attempts;
    await new Promise((resolve) => setTimeout(resolve, 2500));
    if (this.cancelled.has(id))
      throw new Error('Injected pre-commit cancellation.');
    if (this.fail) {
      this.fail = false;
      throw new Error('Injected validator failure.');
    }
    this.saved = {
      ...this.saved,
      token: `fixture-${this.attempts}`,
      policy,
      revision: this.saved.revision! + 1,
    };
    return structuredClone(this.saved);
  }
  async cancel(id: string) {
    this.cancelled.add(id);
  }
}
const api = new FixtureApi();
function SoftwareFixture() {
  const [, refresh] = useState(0);
  return (
    <MemoryRouter>
      <div className="p-3 bg-red-800 text-white space-y-2">
        <strong>
          SOFTWARE TEST MODE - fixtures only; no backend, files, HMD or real
          output
        </strong>
        <div className="flex flex-wrap gap-4">
          <button
            onClick={() => {
              api.fail = true;
              refresh((value) => value + 1);
            }}
          >
            Next save fails
          </button>
          <button
            onClick={() => {
              api.disconnected = true;
              refresh((value) => value + 1);
            }}
          >
            Disconnect diagnostics
          </button>
          <button
            onClick={() => {
              api.disconnected = false;
              api.old = false;
              api.future = false;
              refresh((value) => value + 1);
            }}
          >
            Reconnect diagnostics
          </button>
          <button
            onClick={() => {
              api.old = true;
              refresh((value) => value + 1);
            }}
          >
            Old diagnostic update
          </button>
          <button
            onClick={() => {
              api.future = true;
              refresh((value) => value + 1);
            }}
          >
            Future diagnostic update
          </button>
        </div>
        <p>
          Fixture save attempts: {api.attempts} / Failure armed:{' '}
          {String(api.fail)}
        </p>
      </div>
      <MonakaBridgeSettings api={api} />
    </MemoryRouter>
  );
}
createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <SoftwareFixture />
  </React.StrictMode>
);
