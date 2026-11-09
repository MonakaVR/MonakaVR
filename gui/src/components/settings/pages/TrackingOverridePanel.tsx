import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import { useLocalization } from '@fluent/react';
import ReactModal from 'react-modal';
import { Euler, MathUtils, Quaternion } from 'three';
import { Button } from '@/components/commons/Button';
import type { InputSelectionController } from '@/monaka-input/controller';
import { selectedSourceLabel } from '@/monaka-input/source-label';
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

const IDENTITY = ['0', '0', '0', '0', '0', '0'];
const OFFSET_FIELDS = ['X (mm)', 'Y (mm)', 'Z (mm)', 'X (°)', 'Y (°)', 'Z (°)'];
type OverrideRow = OverrideOutputSnapshot['hands'][OverrideHand];

function rotationDegrees([w, x, y, z]: GripOffset['rotationWxyz']) {
  const angles = new Euler().setFromQuaternion(
    new Quaternion(x, y, z, w).normalize(),
    'XYZ'
  );
  return [angles.x, angles.y, angles.z].map((radians) =>
    Number(MathUtils.radToDeg(radians).toFixed(10))
  );
}

function rotationWxyz(
  x: number,
  y: number,
  z: number
): GripOffset['rotationWxyz'] {
  const rotation = new Quaternion()
    .setFromEuler(
      new Euler(
        MathUtils.degToRad(x % 360),
        MathUtils.degToRad(y % 360),
        MathUtils.degToRad(z % 360),
        'XYZ'
      )
    )
    .normalize();
  return [rotation.w, rotation.x, rotation.y, rotation.z];
}

function HandDetails({
  row,
  source,
  hand,
  editorReady,
  intended,
  busy,
  registered,
  steam,
  save,
  request,
  close,
}: {
  row?: OverrideRow;
  source: string;
  hand: OverrideHand;
  editorReady: boolean;
  intended: boolean;
  busy: boolean;
  registered: boolean;
  steam: SteamOverrideSnapshot | null;
  save: (selection: OverrideSelection) => Promise<boolean>;
  request: (operation: OverrideRequest) => Promise<boolean>;
  close: () => void;
}) {
  const { l10n } = useLocalization();
  const text = (name: string) => l10n.getString('tracking-override-' + name);
  const saved = row?.saved;
  const grip = saved?.gripOffset;
  const savedAngles = grip ? rotationDegrees(grip.rotationWxyz) : null;
  const [fields, setFields] = useState<string[] | null>(
    grip && savedAngles
      ? [
          ...grip.positionM.map((metres) => String(metres * 1000)),
          ...savedAngles.map(String),
        ]
      : null
  );
  const [confirmed, setConfirmed] = useState(
    saved?.sameUniverseConfirmed ?? false
  );
  const [identitySelected, setIdentitySelected] = useState(false);
  const numbers = fields?.map((value) =>
    value.trim() === '' ? NaN : Number(value)
  );
  const offset: GripOffset | null = numbers?.every(Number.isFinite)
    ? {
        positionM: numbers.slice(0, 3).map((millimetres, index) => {
          const savedMetres = grip?.positionM[index];
          // Keep unchanged axes exact through a display-unit round trip.
          return savedMetres !== undefined && millimetres === savedMetres * 1000
            ? savedMetres
            : millimetres / 1000;
        }) as GripOffset['positionM'],
        rotationWxyz:
          !identitySelected &&
          grip &&
          savedAngles?.every((angle, index) => angle === numbers[index + 3])
            ? grip.rotationWxyz
            : rotationWxyz(numbers[3], numbers[4], numbers[5]),
      }
    : null;
  const editing = editorReady && !!row?.source && row.canEnable;
  const { targetPath, devicePath } = overridePaths(hand);
  const mapping = steam?.hands[hand];
  return (
    <>
      <h2 className="text-xl font-bold">
        {text(hand)} · {text('offset-settings')}
      </h2>
      <p>
        {text('target')} <code>{targetPath}</code>
      </p>
      <p>
        {text('device-path')} <code>{devicePath}</code>
      </p>
      {row && (
        <>
          <p>
            {text('source')} {source}
          </p>
          <p role="status">
            {text('source-status')}{' '}
            {text(
              row.sourceStatus?.toLowerCase().replaceAll('_', '-') ??
                'status-unknown'
            )}
          </p>
          <p>
            {text(
              registered ? 'previously-registered' : 'registration-unverified'
            )}
          </p>
        </>
      )}
      {row?.source && (
        <>
          <p>{text('offset-help')}</p>
          <label className="block">
            <input
              type="checkbox"
              checked={confirmed}
              disabled={!editing || busy}
              onChange={(event) => setConfirmed(event.target.checked)}
            />{' '}
            {text('universe')}
          </label>
          <Button
            variant="secondary"
            disabled={!editing || busy}
            onClick={() => {
              setFields(IDENTITY);
              setIdentitySelected(true);
            }}
          >
            {text('use-identity')}
          </Button>
          {fields && (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
              {OFFSET_FIELDS.map((name, index) => (
                <label key={name} className="min-w-0">
                  {name}{' '}
                  <input
                    className="bg-background-80 text-background-10 rounded px-2 py-1 min-w-0 w-full"
                    type="number"
                    step={1}
                    value={fields[index]}
                    disabled={!editing || busy}
                    onChange={(event) =>
                      setFields(
                        fields.map((value, i) =>
                          i === index ? event.target.value : value
                        )
                      )
                    }
                  />
                </label>
              ))}
            </div>
          )}
          <Button
            variant="primary"
            disabled={!editing || busy || !offset || !confirmed}
            onClick={() =>
              void save({
                enabled: intended || !!saved?.enabled,
                gripOffset: offset,
                sameUniverseConfirmed: confirmed,
              })
            }
          >
            {text('save-offset')}
          </Button>
        </>
      )}
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
      <p>{text('active-unverified')}</p>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="secondary"
          disabled={
            busy ||
            !steam ||
            !!mapping?.owned ||
            !!mapping?.conflict ||
            !registered ||
            !editorReady ||
            !row?.saved.enabled ||
            !row.canEnable ||
            !row.calibrationCurrent
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
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => void request({ action: 'snapshot' })}
        >
          {text('reload')}
        </Button>
        <Button variant="secondary" disabled={busy} onClick={close}>
          {l10n.getString('assignment-close')}
        </Button>
      </div>
    </>
  );
}

export function useTrackingOverrideControls(
  model: InputSelectionController,
  connected: boolean,
  assignmentBusy: boolean
) {
  const { l10n } = useLocalization();
  const text = (name: string) => l10n.getString('tracking-override-' + name);
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const editor = window.electronAPI?.trackingOverride;
  const [steam, setSteam] = useState<SteamOverrideSnapshot | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [restartRequired, setRestartRequired] = useState(false);
  const [detail, setDetail] = useState<OverrideHand | null>(null);
  const [intent, setIntent] = useState<Partial<Record<OverrideHand, boolean>>>(
    {}
  );
  const pending = useRef(false);
  const request = useCallback(
    async (operation: OverrideRequest, quiet = false) => {
      if (!editor || pending.current) return false;
      pending.current = true;
      setBusy(true);
      setError('');
      try {
        const reply = await editor(operation);
        if (reply.settings) {
          setSteam(reply.settings);
          if (operation.action !== 'snapshot') setRestartRequired(true);
        }
        if (reply.type === 'core') {
          await model.load();
          if (reply.error) {
            setError(reply.error);
            return false;
          }
        }
        return true;
      } catch (cause) {
        if (!quiet)
          setError(cause instanceof Error ? cause.message : String(cause));
        if (operation.action === 'save') await model.load();
        else if (operation.action === 'snapshot') setSteam(null);
        return false;
      } finally {
        pending.current = false;
        setBusy(false);
      }
    },
    [editor, model]
  );
  useEffect(() => {
    void request({ action: 'snapshot' }, true);
  }, [request]);

  const rows = state.snapshot?.trackingOverride?.hands;
  const coreToken = state.snapshot?.readbackToken;
  const editorReady =
    !!editor &&
    !!coreToken &&
    connected &&
    state.snapshotFresh &&
    !state.busy &&
    !assignmentBusy;
  const save = async (hand: OverrideHand, selection: OverrideSelection) => {
    if (!coreToken || !editorReady) return false;
    const saved = await request({
      action: 'save',
      hand,
      token: coreToken,
      selection,
    });
    if (saved) setIntent((current) => ({ ...current, [hand]: false }));
    return saved;
  };
  const toggle = (hand: OverrideHand, enabled: boolean) => {
    const row = rows?.[hand];
    if (!row) return;
    if (!enabled && intent[hand] && !row.saved.enabled) {
      setIntent((current) => ({ ...current, [hand]: false }));
      return;
    }
    if (enabled && (!row.canEnable || !row.calibrationCurrent)) {
      setIntent((current) => ({ ...current, [hand]: true }));
      return;
    }
    void save(hand, {
      enabled,
      gripOffset: row.saved.gripOffset,
      sameUniverseConfirmed: row.saved.sameUniverseConfirmed,
    });
  };
  const review = (hand: OverrideHand) => {
    const row = rows?.[hand];
    if (intent[hand]) {
      if (!row?.source) return 'pending-source';
      return row.canEnable ? 'pending-offset' : 'pending-capability';
    }
    if (row?.saved.enabled && !row.calibrationCurrent)
      return row.source ? 'review-offset' : 'review-source';
    return null;
  };
  const recovery = (hand: OverrideHand) =>
    steam?.hands[hand].owned ? (
      <button
        type="button"
        className="text-xs underline"
        onClick={(event) => {
          event.stopPropagation();
          setDetail(hand);
        }}
        disabled={busy}
      >
        {text('recover-hand')}
      </button>
    ) : null;
  const controls = (hand: OverrideHand) => {
    const row = rows?.[hand];
    return (
      <>
        <label
          className="inline-flex items-center gap-1 whitespace-nowrap text-xs"
          onClick={(event) => event.stopPropagation()}
        >
          <input
            type="checkbox"
            aria-label={text(hand) + ' ' + text('title')}
            aria-describedby={
              review(hand) ? 'override-review-' + hand : undefined
            }
            checked={!!row?.saved.enabled || !!intent[hand]}
            disabled={!editorReady || !row || busy}
            onChange={(event) => toggle(hand, event.target.checked)}
          />
          {text('toggle')}
        </label>
        {row?.source ? (
          <button
            type="button"
            className="text-xs underline"
            aria-label={text(hand) + ' ' + text('offset-settings')}
            disabled={!editor || busy}
            onClick={(event) => {
              event.stopPropagation();
              setDetail(hand);
            }}
          >
            {text('offset-settings')}
          </button>
        ) : (
          recovery(hand)
        )}
      </>
    );
  };
  const row = detail && connected ? rows?.[detail] : undefined;
  const close = () => {
    if (!busy) setDetail(null);
  };
  const status = (
    <>
      {connected &&
        overrideHands.map((hand) => {
          const message = review(hand);
          return (
            message && (
              <p
                key={hand}
                id={'override-review-' + hand}
                role="status"
                className="text-sm"
              >
                {text(hand)} · {text(message)}
              </p>
            )
          );
        })}
      {restartRequired && (
        <p role="status" className="text-sm">
          {text('restart-required')}
        </p>
      )}
      {!detail && error && <p role="alert">{error}</p>}
    </>
  );
  const dialog = detail && (
    <ReactModal
      isOpen
      onRequestClose={close}
      shouldCloseOnOverlayClick={!busy}
      shouldCloseOnEsc={!busy}
      contentLabel={text(detail) + ' ' + text('title')}
      overlayClassName="fixed inset-0 z-30 grid place-items-center bg-background-90 bg-opacity-90 p-4"
      className="w-full max-w-xl max-h-full overflow-auto rounded-lg bg-background-70 text-background-10 p-4 space-y-3 outline-none"
    >
      <HandDetails
        key={detail + JSON.stringify([row?.source, row?.saved])}
        row={row}
        source={selectedSourceLabel(
          row?.source,
          state.snapshot?.candidates ?? [],
          text('unassigned'),
          text('source-unavailable')
        )}
        hand={detail}
        editorReady={editorReady}
        intended={!!intent[detail]}
        busy={busy}
        registered={
          state.snapshotFresh &&
          row?.registeredDevicePath === overridePaths(detail).devicePath
        }
        steam={steam}
        save={(selection) => save(detail, selection)}
        request={request}
        close={close}
      />
      {error && <p role="alert">{error}</p>}
      {restartRequired && <p role="status">{text('restart-required')}</p>}
    </ReactModal>
  );
  const coreless = (
    <section className="p-4 space-y-3">
      <h2 className="text-xl font-bold">{text('title')}</h2>
      <p>{text('coreless-help')}</p>
      {overrideHands.map(
        (hand) =>
          steam?.hands[hand].owned && (
            <p key={hand}>
              {text(hand)} · {recovery(hand)}
            </p>
          )
      )}
      <Button
        variant="secondary"
        disabled={busy}
        onClick={() => void request({ action: 'snapshot' })}
      >
        {text('reload')}
      </Button>
      {status}
      {dialog}
    </section>
  );
  return { controls, status, dialog, coreless };
}
