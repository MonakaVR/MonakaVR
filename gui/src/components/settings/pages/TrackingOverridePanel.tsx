import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import { useLocalization } from '@fluent/react';
import { Button } from '@/components/commons/Button';
import type {
  InputCandidate,
  InputSelectionController,
} from '@/monaka-input/controller';
import { sourceDetails, sourceLabel } from '@/monaka-input/source-label';
import {
  overrideHands,
  overridePaths,
  type GripOffset,
  type OverrideHand,
  type OverrideOutputSnapshot,
  type OverrideRequest,
  type OverrideSelection,
  type SteamOverrideSnapshot,
} from '../../../../electron/tracking-override-types';

const EMPTY: OverrideSelection = {
  enabled: false,
  source: null,
  gripOffset: null,
  sameUniverseConfirmed: false,
};
const IDENTITY = ['0', '0', '0', '1', '0', '0', '0'];
const INPUT_CLASS =
  'bg-background-80 text-background-10 rounded px-2 py-1 min-w-0';
const offsetFields = (gripOffset: GripOffset | null) =>
  gripOffset
    ? [...gripOffset.positionM, ...gripOffset.rotationWxyz].map(String)
    : null;

function HandSettings({
  hand,
  row,
  candidates,
  snapshotFresh,
  driverReady,
  canSave,
  steam,
  busy,
  coreToken,
  request,
}: {
  hand: OverrideHand;
  row?: OverrideOutputSnapshot['hands'][OverrideHand];
  candidates: InputCandidate[];
  snapshotFresh: boolean;
  driverReady: boolean;
  canSave: boolean;
  steam: SteamOverrideSnapshot | null;
  busy: boolean;
  coreToken?: string;
  request: (operation: OverrideRequest) => Promise<boolean>;
}) {
  const { l10n } = useLocalization();
  const text = (name: string) => l10n.getString('tracking-override-' + name);
  const saved = row?.saved ?? EMPTY;
  const [draft, setDraft] = useState(saved);
  const [baseToken, setBaseToken] = useState<string>();
  const [offset, setOffset] = useState(() => offsetFields(saved.gripOffset));
  const update = (fields: Partial<OverrideSelection>) => {
    setBaseToken((current) => current ?? coreToken);
    setDraft((current) => ({ ...current, ...fields }));
  };
  const candidates6Dof = candidates.filter(
    (source) => source.origin === 'mvip_pose'
  );
  const current = candidates6Dof.find((source) => source.id === draft.source);
  const savedSource = candidates6Dof.find(
    (source) => source.id === saved.source
  );
  const { devicePath, targetPath } = overridePaths(hand);
  const registered = snapshotFresh && row?.registeredDevicePath === devicePath;
  const mapping = steam?.hands[hand];
  const removeFirst = mapping?.owned && saved.enabled && !draft.enabled;
  const handLabel = text(hand);
  const numbers = offset?.map((value) =>
    value.trim() === '' ? NaN : Number(value)
  );
  const validOffset = numbers?.length === 7 && numbers.every(Number.isFinite);
  const gripOffset: GripOffset | null = validOffset
    ? {
        positionM: numbers.slice(0, 3) as GripOffset['positionM'],
        rotationWxyz: numbers.slice(3) as GripOffset['rotationWxyz'],
      }
    : null;
  const changed = baseToken !== undefined;
  const formValid =
    (!offset || validOffset) &&
    (!draft.enabled ||
      (!!draft.source && !!gripOffset && draft.sameUniverseConfirmed));
  const sourceStatus = !draft.source
    ? 'unassigned'
    : !snapshotFresh
      ? 'status-unknown'
      : !current
        ? 'source-unavailable'
        : !current.capabilities
          ? 'capability-unknown'
          : !current.sixDofSelectable
            ? 'capability-unsupported'
            : row?.saved.enabled &&
                row.saved.source === draft.source &&
                row.sourceStatus
              ? row.sourceStatus.toLowerCase().replaceAll('_', '-')
              : current.sixDofUsableNow
                ? 'full'
                : 'incomplete';

  return (
    <fieldset className="rounded border border-background-50 p-4 space-y-3 min-w-0">
      <legend className="font-bold px-2">{handLabel}</legend>
      <p>
        {text('target')} <code>{targetPath}</code>
      </p>
      <p>
        {text('device-path')} <code>{devicePath}</code>
      </p>
      <label className="block">
        <input
          type="checkbox"
          checked={draft.enabled}
          disabled={!canSave || busy}
          onChange={(event) => update({ enabled: event.target.checked })}
          aria-label={handLabel + ' ' + text('enabled')}
        />{' '}
        {text('enabled')}
      </label>
      <label className="block">
        {text('source')}{' '}
        <select
          className={INPUT_CLASS + ' w-full'}
          aria-label={handLabel + ' ' + text('source')}
          value={draft.source ?? ''}
          disabled={!canSave || busy}
          onChange={(event) => {
            update({
              source: event.target.value || null,
              sameUniverseConfirmed: false,
            });
            setOffset(null);
          }}
        >
          <option value="">{text('unassigned')}</option>
          {draft.source && !current && (
            <option value={draft.source}>{text('source-unavailable')}</option>
          )}
          {candidates6Dof.map((candidate) => (
            <option
              key={candidate.id}
              value={candidate.id}
              title={sourceDetails(candidate)}
              disabled={!candidate.sixDofSelectable}
            >
              {sourceLabel(candidate, candidates6Dof, text('status-unknown'))}
            </option>
          ))}
        </select>
      </label>
      <p role="status">
        {text('source-status')} {text(sourceStatus)}
      </p>
      <label className="block">
        <input
          type="checkbox"
          checked={draft.sameUniverseConfirmed}
          disabled={!canSave || busy}
          onChange={(event) =>
            update({ sameUniverseConfirmed: event.target.checked })
          }
        />{' '}
        {text('universe')}
      </label>
      <div className="flex flex-wrap items-center gap-2">
        <Button
          variant="secondary"
          disabled={!canSave || busy}
          onClick={() => {
            update({});
            setOffset(IDENTITY);
          }}
        >
          {text('use-identity')}
        </Button>
        <span>{text(offset ? 'offset-help' : 'no-grip-offset')}</span>
      </div>
      {offset && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
          {['X (m)', 'Y (m)', 'Z (m)', 'Qw', 'Qx', 'Qy', 'Qz'].map(
            (name, index) => (
              <label key={name} className="min-w-0">
                {name}{' '}
                <input
                  className={INPUT_CLASS + ' w-full'}
                  type="number"
                  step="any"
                  value={offset[index]}
                  disabled={!canSave || busy}
                  onChange={(event) => {
                    update({});
                    setOffset(
                      offset.map((value, i) =>
                        i === index ? event.target.value : value
                      )
                    );
                  }}
                />
              </label>
            )
          )}
        </div>
      )}
      <p role="status">
        {text('saved')}{' '}
        {text(row ? (saved.enabled ? 'on' : 'off') : 'status-unknown')}
        {saved.source && (
          <>
            {' '}
            ·{' '}
            {savedSource
              ? sourceLabel(
                  savedSource,
                  candidates6Dof,
                  text('source-unavailable')
                )
              : text('source-unavailable')}
          </>
        )}
      </p>
      <p role="status">
        {text(
          !registered
            ? 'registration-unverified'
            : driverReady
              ? 'driver-connected'
              : 'previously-registered'
        )}
      </p>
      <p role="status">
        {text('steam-settings')}{' '}
        {text(
          !mapping
            ? 'status-unknown'
            : mapping.conflict
              ? 'conflict'
              : mapping.owned
                ? 'mapped'
                : 'unmapped'
        )}
      </p>
      <p role="status">{text('active-unverified')}</p>
      {removeFirst && <p>{text('remove-first')}</p>}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="primary"
          disabled={
            !canSave ||
            busy ||
            !baseToken ||
            !changed ||
            !formValid ||
            !!removeFirst
          }
          onClick={() => {
            if (!baseToken) return;
            void request({
              action: 'save',
              hand,
              token: baseToken,
              selection: { ...draft, gripOffset },
            }).then((ok) => {
              if (ok) setBaseToken(undefined);
            });
          }}
        >
          {text('save')}
        </Button>
        <Button
          variant="secondary"
          disabled={busy || !changed}
          onClick={() => {
            setDraft(saved);
            setBaseToken(undefined);
            setOffset(offsetFields(saved.gripOffset));
          }}
        >
          {text('discard')}
        </Button>
        <Button
          variant="secondary"
          disabled={
            busy ||
            !steam ||
            !!mapping?.owned ||
            !!mapping?.conflict ||
            !registered ||
            !saved.enabled ||
            changed
          }
          onClick={() => {
            if (steam)
              void request({ action: 'apply', hand, token: steam.token });
          }}
        >
          {text('apply')}
        </Button>
        <Button
          variant="secondary"
          disabled={busy || !steam || !mapping?.owned}
          onClick={() => {
            if (steam)
              void request({ action: 'remove', hand, token: steam.token });
          }}
        >
          {text('remove')}
        </Button>
      </div>
    </fieldset>
  );
}

export function TrackingOverridePanel({
  model,
}: {
  model: InputSelectionController;
}) {
  const { l10n } = useLocalization();
  const text = (name: string) => l10n.getString('tracking-override-' + name);
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const editor = window.electronAPI?.trackingOverride;
  const [steam, setSteam] = useState<SteamOverrideSnapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [restartRequired, setRestartRequired] = useState(false);
  const pending = useRef(false);
  const request = useCallback(
    async (operation: OverrideRequest) => {
      if (!editor || pending.current) return false;
      pending.current = true;
      setBusy(true);
      setError('');
      try {
        const reply = await editor(operation);
        if (reply.type === 'steam') {
          setSteam(reply.settings);
          if (operation.action !== 'snapshot') setRestartRequired(true);
        }
        if (operation.action === 'save') await model.load();
        return true;
      } catch (cause) {
        setError(cause instanceof Error ? cause.message : String(cause));
        if (operation.action !== 'save') setSteam(null);
        else await model.load();
        return false;
      } finally {
        pending.current = false;
        setBusy(false);
      }
    },
    [editor, model]
  );
  useEffect(() => {
    void request({ action: 'snapshot' });
  }, [request]);
  const snapshot = state.snapshot;
  return (
    <section
      aria-labelledby="tracking-override-title"
      className="bg-background-70 text-background-10 rounded-lg p-5 space-y-4 min-w-0 break-words"
    >
      <h2 id="tracking-override-title" className="text-xl font-bold">
        {text('title')}
      </h2>
      <p>{text('help')}</p>
      {!editor && <p role="status">{text('desktop-required')}</p>}
      {error && <p role="alert">{error}</p>}
      {restartRequired && <p role="status">{text('restart-required')}</p>}
      {overrideHands.map((hand) => {
        const row = snapshot?.trackingOverride?.hands?.[hand];
        return (
          <HandSettings
            key={hand + JSON.stringify(row?.saved ?? EMPTY)}
            hand={hand}
            row={row}
            candidates={snapshot?.candidates ?? []}
            snapshotFresh={state.snapshotFresh}
            driverReady={snapshot?.trackingOverride?.driverReady === true}
            canSave={
              !!editor &&
              !!row &&
              state.snapshotFresh &&
              !state.busy &&
              !!snapshot?.readbackToken
            }
            steam={steam}
            busy={busy}
            coreToken={snapshot?.readbackToken}
            request={request}
          />
        );
      })}
      <Button
        variant="secondary"
        disabled={!editor || busy}
        onClick={() => void request({ action: 'snapshot' })}
      >
        {text('reload')}
      </Button>
    </section>
  );
}
