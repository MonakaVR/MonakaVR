import { useLocalization } from '@fluent/react';
import { memo, useId, useMemo, useState } from 'react';
import type { InputSnapshot } from '@/monaka-input/controller';
import {
  buildTrackingStatusModel,
  type Capability,
  type SourceStatus,
  type TrackingConnection,
  type TrackingStatusReason,
  type Usability,
} from '@/monaka-status/view-model';

export interface MonakaTrackingStatusPanelProps {
  snapshot: InputSnapshot | null;
  /** Required: a cached snapshot alone does not establish a live connection. */
  connection: TrackingConnection;
  minimumGeneration?: string | null;
}

const capabilityText: Record<Capability, string> = {
  'position-orientation': 'Position + orientation',
  'orientation-only': 'Orientation only',
  conflicting: 'Conflicting',
  unknown: 'Unknown',
};
const reasonText: Record<TrackingStatusReason, string> = {
  connected: 'Snapshot received. Source usability describes input only.',
  offline: 'Core disconnected. Current input usability is unknown.',
  disabled:
    'Monaka input runtime disabled. Current input usability is unknown.',
  'loopback-denied':
    'Core loopback access unavailable. Current input usability is unknown.',
  error: 'Core request failed. Current input usability is unknown.',
  unknown: 'Core connection state unknown.',
  'missing-snapshot':
    'No current input snapshot. Current input usability is unknown.',
  'invalid-snapshot':
    'Invalid input snapshot. Current input usability is unknown.',
  'inconsistent-sources':
    'Inconsistent source list. Current input usability is unknown.',
  'stale-generation':
    'Old assignment generation rejected. Current input usability is unknown.',
  'inconsistent-generation':
    'Selected IDs changed without a new generation. Current input usability is unknown.',
};

function Message({ id, fallback }: { id: string; fallback: string }) {
  const { l10n } = useLocalization();
  // Missing keys are expected until D's translations land; avoid repeated logs.
  return (
    <span>
      {l10n.getBundle(id) ? l10n.getString(id, undefined, fallback) : fallback}
    </span>
  );
}
function Unknown() {
  return <Message id="monaka-tracking-unknown" fallback="Unknown" />;
}
function Usable({ value }: { value: Usability }) {
  return value === 'unknown' ? (
    <Unknown />
  ) : (
    <span>{value === 'yes' ? 'Yes' : 'No'}</span>
  );
}
function Choice({ value }: { value: boolean | null }) {
  return value === null ? <Unknown /> : <span>{value ? 'Yes' : 'No'}</span>;
}
function Identity({ source }: { source: SourceStatus }) {
  return (
    <div className="space-y-1 break-all">
      <code className="block text-xs">{source.id}</code>
      <dl className="text-sm">
        <dt>Publisher</dt>
        <dd>{source.publisher ?? <Unknown />}</dd>
        <dt>Source</dt>
        <dd>{source.source ?? <Unknown />}</dd>
        <dt>Tracker</dt>
        <dd>{source.tracker ?? <Unknown />}</dd>
        <dt>Origin</dt>
        <dd>{source.origin ?? <Unknown />}</dd>
      </dl>
    </div>
  );
}
function SavedOffline() {
  return (
    <span>
      Saved (
      <Message id="monaka-tracking-offline" fallback="offline" />)
    </span>
  );
}
function SelectedSource({ source }: { source: SourceStatus | null }) {
  if (!source) return <span>Unassigned</span>;
  return (
    <div className="space-y-1">
      {source.availability === 'saved-offline' ? (
        <SavedOffline />
      ) : (
        <Message id="monaka-tracking-assigned" fallback="Assigned" />
      )}
      <Identity source={source} />
    </div>
  );
}
function Mode({ mode }: { mode: string }) {
  if (mode === 'ik')
    return <Message id="monaka-tracking-mode-ik" fallback="IK" />;
  if (mode === 'direct')
    return <Message id="monaka-tracking-mode-direct" fallback="Direct" />;
  if (mode === 'hybrid')
    return <Message id="monaka-tracking-mode-hybrid" fallback="Hybrid" />;
  return <Unknown />;
}

/** Read-only reusable component. Mount under the GUI's LocalizationProvider. */
export const MonakaTrackingStatusPanel = memo(
  function MonakaTrackingStatusPanel({
    snapshot,
    connection,
    minimumGeneration,
  }: MonakaTrackingStatusPanelProps) {
    const titleId = useId();
    const [history, setHistory] = useState<{
      input: InputSnapshot | null;
      connection: TrackingConnection;
      minimumGeneration: string | null | undefined;
      accepted: InputSnapshot | null;
    }>({
      input: null,
      connection: 'unknown',
      minimumGeneration: undefined,
      accepted: null,
    });
    const model = useMemo(
      () =>
        buildTrackingStatusModel(snapshot, {
          connection,
          previousSnapshot: history.accepted,
          minimumGeneration,
        }),
      [snapshot, connection, minimumGeneration, history.accepted]
    );
    // React's guarded state adjustment keeps retention in sync with the props
    // before children render. There are no effects, timers or external writes.
    if (
      history.input !== snapshot ||
      history.connection !== connection ||
      history.minimumGeneration !== minimumGeneration
    ) {
      setHistory({
        input: snapshot,
        connection,
        minimumGeneration,
        accepted:
          model.acceptedSnapshot ?? model.retainedSnapshot ?? history.accepted,
      });
    }
    const cell = 'border-b border-background-50 px-3 py-2 text-left align-top';
    return (
      <section
        aria-labelledby={titleId}
        className="bg-background-70 text-background-10 rounded-lg p-5 space-y-4"
        data-status={model.status}
      >
        <h2 id={titleId} className="text-xl font-bold">
          Monaka input tracking status
        </h2>
        <p role={model.status === 'error' ? 'alert' : 'status'}>
          {reasonText[model.reason]}
        </p>
        {model.generation !== null && (
          <p>
            Assignment generation: <code>{model.generation}</code>
          </p>
        )}
        {model.retainedGeneration !== null && (
          <p>
            Retained assignment generation (current state unknown):{' '}
            <code>{model.retainedGeneration}</code>
          </p>
        )}
        <p>
          Q and 6DoF usability describe the source at snapshot time. They do not
          confirm Direct output, common world alignment, solver writeback or
          SteamVR display. Orientation support does not establish an independent
          physical IMU.
        </p>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="text-left text-lg font-bold">
              Body assignments
            </caption>
            <thead>
              <tr>
                <th scope="col" className={cell}>
                  Body
                </th>
                <th scope="col" className={cell}>
                  Selected Q
                </th>
                <th scope="col" className={cell}>
                  Selected 6DoF
                </th>
                <th scope="col" className={cell}>
                  6DoF selection
                </th>
                <th scope="col" className={cell}>
                  Reported mode (activation unknown)
                </th>
              </tr>
            </thead>
            <tbody>
              {model.bodies.map((body) => (
                <tr key={body.body}>
                  <th scope="row" className={cell}>
                    {body.body}
                  </th>
                  <td className={cell}>
                    <SelectedSource source={body.q} />
                  </td>
                  <td className={cell}>
                    <SelectedSource source={body.sixDof} />
                  </td>
                  <td className={cell}>
                    {body.manualSix ? 'Manual selection' : 'Not marked manual'}
                  </td>
                  <td className={cell}>
                    <Mode mode={body.mode} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {model.bodies.length === 0 && <p>No body assignments available.</p>}
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <caption className="text-left text-lg font-bold">Sources</caption>
            <thead>
              <tr>
                <th scope="col" className={cell}>
                  Full identity
                </th>
                <th scope="col" className={cell}>
                  <Message
                    id="monaka-tracking-capability"
                    fallback="Capability"
                  />{' '}
                  (static)
                </th>
                <th scope="col" className={cell}>
                  Selectable (Q / 6DoF)
                </th>
                <th scope="col" className={cell}>
                  <Message id="monaka-tracking-validity" fallback="Validity" />{' '}
                  (Q usable now / 6DoF usable now)
                </th>
                <th scope="col" className={cell}>
                  Presence in snapshot
                </th>
              </tr>
            </thead>
            <tbody>
              {model.sources.map((source) => (
                <tr key={source.id} data-source-id={source.id}>
                  <th scope="row" className={cell}>
                    <Identity source={source} />
                  </th>
                  <td className={cell}>
                    {source.capability === 'unknown' ? (
                      <Unknown />
                    ) : (
                      capabilityText[source.capability]
                    )}
                  </td>
                  <td className={cell}>
                    Q: <Choice value={source.qSelectable} />
                    <br />
                    6DoF: <Choice value={source.sixDofSelectable} />
                  </td>
                  <td className={cell}>
                    Q usable now: <Usable value={source.qUsableNow} />
                    <br />
                    6DoF usable now: <Usable value={source.sixDofUsableNow} />
                  </td>
                  <td className={cell}>
                    {source.availability === 'saved-offline' ? (
                      <SavedOffline />
                    ) : (
                      'Listed'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {model.sources.length === 0 && (
          <p>
            {model.status === 'snapshot'
              ? 'No input candidates in snapshot.'
              : 'Source list unavailable.'}
          </p>
        )}
      </section>
    );
  }
);
