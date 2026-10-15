import { useEffect, useState, useSyncExternalStore } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '@/components/commons/Button';
import { BridgeController } from '@/bridge/controller';
import {
  BRIDGE_POLICIES,
  type BridgeDesktopApi,
  type BridgePolicy,
} from '../../../../electron/bridge-types';

export function BridgeControlPanel({ model }: { model: BridgeController }) {
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const config = state.configuration,
    health = state.diagnostics;
  const selection = health.devices.find(
    (row) => row.key === state.selectedDevice
  );
  return (
    <main className="min-h-full bg-background-80 text-background-10 p-6 space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div data-electron-drag-region="true">
          <p className="text-sm text-background-30">MonakaVR Desktop</p>
          <h1 className="text-2xl font-bold">Monaka Bridge</h1>
        </div>
        {window.electronAPI && (
          <Button variant="secondary" onClick={() => window.electronAPI.hide()}>
            Hide Desktop window
          </Button>
        )}
        <Link className="underline" to="/settings/trackers">
          Back to MonakaVR settings
        </Link>
      </header>
      <section
        className="bg-background-70 rounded-lg p-5 space-y-3"
        aria-labelledby="bridge-files-title"
      >
        <h2 className="text-xl font-bold" id="bridge-files-title">
          Bridge configuration
        </h2>
        <p className="break-all">
          Configuration: {config?.locations.configPath ?? 'not selected'}
        </p>
        <p className="break-all">
          Validator: {config?.locations.validatorPath ?? 'not selected'}
        </p>
        <div className="flex flex-wrap gap-3">
          <Button
            variant="secondary"
            disabled={state.busy}
            onClick={() => void model.choose('config')}
          >
            Choose configuration
          </Button>
          <Button
            variant="secondary"
            disabled={state.busy}
            onClick={() => void model.choose('validator')}
          >
            Choose validator
          </Button>
          <Button
            variant="secondary"
            disabled={state.busy}
            onClick={() => void model.load()}
          >
            Reload saved settings
          </Button>
        </div>
        <p className="text-sm">
          Uses the existing Bridge version 2 configuration and
          monaka_bridge_config.exe. Selecting files does not start Bridge or
          tracking.
        </p>
      </section>
      <section
        className="bg-background-70 rounded-lg p-5 space-y-3"
        aria-labelledby="bridge-policy-title"
      >
        <h2 className="text-xl font-bold" id="bridge-policy-title">
          Output policy
        </h2>
        <p>
          Saved (last load): <strong>{config?.policy ?? 'unknown'}</strong> /
          Revision: {config?.revision ?? 'unknown'}
        </p>
        <label className="block" htmlFor="bridge-policy">
          Selected output policy
        </label>
        <select
          id="bridge-policy"
          className="bg-background-60 rounded p-2 w-full max-w-sm"
          value={state.selectedPolicy ?? ''}
          disabled={state.busy || !config?.token}
          onChange={(event) =>
            model.selectPolicy(event.target.value as BridgePolicy)
          }
        >
          <option value="" disabled>
            unknown
          </option>
          {BRIDGE_POLICIES.map((policy) => (
            <option key={policy} value={policy}>
              {policy}
            </option>
          ))}
        </select>
        <p className="text-sm">
          both can create duplicate downstream trackers. A saved policy is
          separate from runtime observation and output delivery.
        </p>
        <label className="flex items-center gap-3">
          <input
            type="checkbox"
            checked={state.confirmed}
            disabled={state.busy || !config?.token}
            onChange={(event) => model.confirm(event.target.checked)}
          />
          I have reviewed the selected output policy
        </label>
        <div className="flex flex-wrap gap-3">
          <Button
            variant="primary"
            disabled={!model.canApply}
            onClick={() => void model.apply()}
          >
            Apply output policy
          </Button>
          <Button variant="secondary" onClick={() => void model.cancel()}>
            Cancel / restore selection
          </Button>
        </div>
        <p role="status" aria-live="polite">
          {state.message}
        </p>
      </section>
      <section
        className="bg-background-70 rounded-lg p-5 space-y-3"
        aria-labelledby="bridge-health-title"
      >
        <h2 className="text-xl font-bold" id="bridge-health-title">
          Bridge diagnostics
        </h2>
        <dl className="grid grid-cols-2 gap-2">
          <dt>Backend started</dt>
          <dd>unknown - not reported</dd>
          <dt>Publisher running</dt>
          <dd>unknown - not reported</dd>
          <dt>Diagnostic snapshot</dt>
          <dd>{health.current ? 'current' : 'unknown / historical'}</dd>
          <dt>Observed output policy</dt>
          <dd>{health.observedPolicy ?? 'unknown'}</dd>
          <dt>Observed mapping revision</dt>
          <dd>
            {health.observedRevision ?? 'unknown'} / {health.revision}
          </dd>
          <dt>Source flow</dt>
          <dd>{health.flow}</dd>
        </dl>
        <p>{health.message}</p>
        <p className="text-sm">
          Native observation stage; these values are not final SteamVR output.
          Device source/identity and body assignments are separate.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr>
                <th>Source / device</th>
                <th>Logical tracker</th>
                <th>Tracking</th>
                <th>Pose</th>
                <th>Position</th>
                <th>Orientation</th>
                <th>Collision</th>
              </tr>
            </thead>
            <tbody>
              {health.devices.map((row) => (
                <tr
                  key={row.key}
                  className={
                    row.key === state.selectedDevice ? 'bg-background-50' : ''
                  }
                >
                  <td>
                    <button
                      type="button"
                      className="underline py-2"
                      aria-pressed={row.key === state.selectedDevice}
                      onClick={() => model.selectDevice(row.key)}
                    >
                      {row.source} / {row.device}
                    </button>
                  </td>
                  <td>{row.tracker}</td>
                  <td>{row.tracking}</td>
                  <td>{row.freshness}</td>
                  <td>{row.positionValid}</td>
                  <td>{row.orientationValid}</td>
                  <td>{row.collision}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!health.devices.length && <p>No device observations available.</p>}
        </div>
        {selection && (
          <p className="text-sm break-all">
            Selected identity: publisher={selection.publisher} / source=
            {selection.source} / device={selection.device}
          </p>
        )}
      </section>
      <section
        className="bg-background-70 rounded-lg p-5 space-y-3"
        aria-labelledby="bridge-assignment-title"
      >
        <h2 className="text-xl font-bold" id="bridge-assignment-title">
          Mapping and calibration prerequisites
        </h2>
        <p>
          Body roles remain in MonakaVR tracker controls. Bridge exposes no
          body-role setter. Calibration measurements require the existing OpenVR
          CLI; measurement progress, cancellation and result application are not
          exposed by this contract.
        </p>
        <p className="text-sm">
          Input-space approval and coordinate-profile approval are independent.
          No approval or measured calibration is created here.
        </p>
        <ul>
          {config?.mappings.map((row, index) => (
            <li key={index} className="py-2 break-all">
              {row.source} / {row.device} to {row.tracker} / profile=
              {row.profile} (
              {row.profileApproved === null
                ? 'unknown'
                : row.profileApproved
                  ? 'approved'
                  : 'not approved'}
              ) / input={row.inputSpace}:{row.inputRevision ?? 'unknown'} (
              {row.approved === null
                ? 'unknown'
                : row.approved
                  ? 'approved'
                  : 'not approved'}
              )
            </li>
          ))}
        </ul>
      </section>
    </main>
  );
}

export function MonakaBridgeSettings({
  api = window.electronAPI?.bridge,
}: {
  api?: BridgeDesktopApi;
}) {
  const [model, setModel] = useState<BridgeController | null>(null);
  useEffect(() => {
    if (!api) return;
    const next = new BridgeController(api);
    setModel(next);
    void next.load().then(() => next.poll());
    const timer = setInterval(() => void next.poll(), 1000);
    return () => {
      clearInterval(timer);
      next.dispose();
    };
  }, [api]);
  if (!api)
    return (
      <main className="p-6 text-background-10">
        <h1>Monaka Bridge</h1>
        <p>
          Bridge file controls are available in MonakaVR Desktop. Connection
          state: unknown.
        </p>
        <Link to="/">Return to MonakaVR</Link>
      </main>
    );
  if (!model) return <p>Loading Bridge controls...</p>;
  return <BridgeControlPanel model={model} />;
}
