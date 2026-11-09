import { useEffect, useMemo, useSyncExternalStore } from 'react';
import { InputSelectionController } from '@/monaka-input/controller';
import { MonakaInputSocket } from '@/monaka-input/socket';
import { Button } from '@/components/commons/Button';
import { useLocalization } from '@fluent/react';
import {
  sourceLabel,
  sourceDetails,
  savedSourceLabel,
  selectedSourceDetails,
} from '@/monaka-input/source-label';
import { InputSourceDetails } from '@/components/commons/InputSourceDetails';
import { TrackingOverridePanel } from './TrackingOverridePanel';

export function MonakaInputPanel({
  model,
}: {
  model: InputSelectionController;
}) {
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const { l10n } = useLocalization();
  const text = (key: string) => l10n.getString(`monaka-input-${key}`);
  const { snapshot, draft, busy, dirty } = state;
  const options = (kind: 'q' | 'six') => {
    const selected = kind === 'q' ? draft.qSource : draft.sixDofSource;
    return (
      <>
        <option value="">{text('unassigned')}</option>
        {selected && !snapshot?.candidates.some((x) => x.id === selected) && (
          <option value={selected} title={selectedSourceDetails(selected, [])}>
            {savedSourceLabel(text('saved-offline'))}
          </option>
        )}
        {snapshot?.candidates.map((source) => (
          <option
            key={source.id}
            value={source.id}
            title={sourceDetails(source)}
            disabled={
              kind === 'q' ? !source.qSelectable : !source.sixDofSelectable
            }
          >
            {sourceLabel(
              source,
              snapshot?.candidates ?? [],
              text('saved-offline')
            )}{' '}
            — {source.capabilities?.join(', ') || text('support-unknown')}
            {source.conflict ? ` — ${text('conflict')}` : ''}
          </option>
        ))}
      </>
    );
  };
  return (
    <section
      aria-labelledby="monaka-input-title"
      className="bg-background-70 text-background-10 rounded-lg p-5 space-y-4 min-w-0 break-words"
    >
      <h2 id="monaka-input-title" className="text-xl font-bold">
        {text('title')}
      </h2>
      <p>{text('semantics')}</p>
      <label className="block">
        {text('body')}{' '}
        <select
          className="bg-background-80 text-background-10 rounded px-2 py-1 max-w-full min-w-0"
          aria-label={text('body-label')}
          value={draft.body}
          disabled={!snapshot || busy || dirty}
          onChange={(e) => model.selectBody(e.target.value)}
        >
          {(snapshot?.bodies ?? ['HIP']).map((body) => (
            <option key={body}>{body}</option>
          ))}
        </select>
      </label>
      <label className="block">
        {text('q')}{' '}
        <select
          className="bg-background-80 text-background-10 rounded px-2 py-1 w-full max-w-full min-w-0"
          title={selectedSourceDetails(
            draft.qSource,
            snapshot?.candidates ?? []
          )}
          aria-label={text('q')}
          value={draft.qSource ?? ''}
          disabled={!model.canEdit}
          onChange={(e) => model.selectQ(e.target.value || null)}
        >
          {options('q')}
        </select>
      </label>
      <label className="block">
        {text('six')}{' '}
        <select
          className="bg-background-80 text-background-10 rounded px-2 py-1 w-full max-w-full min-w-0"
          title={selectedSourceDetails(
            draft.sixDofSource,
            snapshot?.candidates ?? []
          )}
          aria-label={text('six')}
          value={draft.sixDofSource ?? ''}
          disabled={!model.canEdit}
          onChange={(e) => model.selectSix(e.target.value || null)}
        >
          {options('six')}
        </select>
      </label>
      <p>{text('selection-help')}</p>
      <div className="flex flex-wrap gap-3">
        <Button
          variant="primary"
          disabled={!dirty || !model.canEdit}
          onClick={() => void model.save()}
        >
          {text('save')}
        </Button>
        <Button
          variant="secondary"
          disabled={!dirty || busy || state.needsReload}
          onClick={() => model.cancel()}
        >
          {text('cancel')}
        </Button>
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => void model.load(true)}
        >
          {text('reload')}
        </Button>
      </div>
      {state.error && <p role="alert">{state.error}</p>}
      <p>{text('readback')}</p>
      {snapshot?.restartRequired && (
        <p role="status">
          {l10n.getString('monaka-tracking-restart-required')}
        </p>
      )}
      {snapshot && !state.snapshotFresh && <p role="status">{text('stale')}</p>}
      {!snapshot && <p>{text('waiting')}</p>}
      <ul aria-label={text('usability')}>
        {snapshot?.candidates.map((source) => (
          <li
            key={source.id}
            className="min-w-0 break-words"
            title={sourceDetails(source)}
          >
            {sourceLabel(
              source,
              snapshot?.candidates ?? [],
              text('saved-offline')
            )}
            : Q{' '}
            {text(
              !state.snapshotFresh
                ? 'unknown'
                : source.qUsableNow
                  ? 'valid'
                  : 'invalid'
            )}
            , 6DoF{' '}
            {text(
              !state.snapshotFresh
                ? 'unknown'
                : source.sixDofUsableNow
                  ? 'valid'
                  : 'invalid'
            )}
            {source.conflict ? ` — ${text('conflict')}` : ''}
            {source.capabilities === null
              ? ` — ${text('support-unknown')}`
              : ''}
            <InputSourceDetails text={sourceDetails(source)} />
          </li>
        ))}
      </ul>
    </section>
  );
}
export function MonakaInputSettings() {
  const { api, model } = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const host = params.get('ip') ?? 'localhost';
    const port = params.get('port') ?? '21110';
    const api = new MonakaInputSocket(`ws://${host}:${port}`);
    return { api, model: new InputSelectionController(api) };
  }, []);
  useEffect(() => {
    void model.load();
    const timer = setInterval(() => void model.load(), 2000);
    return () => {
      clearInterval(timer);
      api.close();
    };
  }, [api, model]);
  return (
    <div className="space-y-4">
      <MonakaInputPanel model={model} />
      <TrackingOverridePanel model={model} />
    </div>
  );
}
