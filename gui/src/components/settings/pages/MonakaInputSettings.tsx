import { useEffect, useMemo, useSyncExternalStore } from 'react';
import { InputSelectionController } from '@/monaka-input/controller';
import { MonakaInputSocket } from '@/monaka-input/socket';
import { Button } from '@/components/commons/Button';

export function MonakaInputPanel({
  model,
}: {
  model: InputSelectionController;
}) {
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const { snapshot, draft, busy, dirty } = state;
  const options = (kind: 'q' | 'six') => {
    const selected = kind === 'q' ? draft.qSource : draft.sixDofSource;
    return (
      <>
        <option value="">Unassigned</option>
        {selected && !snapshot?.candidates.some((x) => x.id === selected) && (
          <option value={selected}>Saved source (offline): {selected}</option>
        )}
        {snapshot?.candidates.map((source) => (
          <option
            key={source.id}
            value={source.id}
            disabled={
              kind === 'q' ? !source.qSelectable : !source.sixDofSelectable
            }
          >
            {[source.publisher, source.source, source.tracker]
              .filter(Boolean)
              .join(' / ')}{' '}
            — {source.capabilities?.join(', ') || 'support unknown'}
          </option>
        ))}
      </>
    );
  };
  return (
    <section
      aria-labelledby="monaka-input-title"
      className="bg-background-70 text-background-10 rounded-lg p-5 space-y-4"
    >
      <h2 id="monaka-input-title" className="text-xl font-bold">
        Q and 6DoF sources
      </h2>
      <p>
        6DoF Main supplies its paired pose while usable. The Q source provides
        rotation when Main is unavailable. Orientation support does not
        establish an independent IMU.
      </p>
      <label className="block">
        Body target{' '}
        <select
          className="bg-background-80 text-background-10 rounded px-2 py-1"
          aria-label="Input body target"
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
        Q Source{' '}
        <select
          className="bg-background-80 text-background-10 rounded px-2 py-1"
          aria-label="Q Source"
          value={draft.qSource ?? ''}
          disabled={!snapshot || busy}
          onChange={(e) => model.selectQ(e.target.value || null)}
        >
          {options('q')}
        </select>
      </label>
      <label className="block">
        6DoF Source{' '}
        <select
          className="bg-background-80 text-background-10 rounded px-2 py-1"
          aria-label="6DoF Source"
          value={draft.sixDofSource ?? ''}
          disabled={!snapshot || busy}
          onChange={(e) => model.selectSix(e.target.value || null)}
        >
          {options('six')}
        </select>
      </label>
      <p>
        On the first Q selection, a confirmed Pos+Q source also fills 6DoF.
        Manual choices and cleared fields remain saved through loss and
        reconnect.
      </p>
      <div className="flex gap-3">
        <Button
          variant="primary"
          disabled={!dirty || busy || state.needsReload}
          onClick={() => void model.save()}
        >
          Save input choices
        </Button>
        <Button
          variant="secondary"
          disabled={!dirty || busy || state.needsReload}
          onClick={() => model.cancel()}
        >
          Cancel input changes
        </Button>
        <Button
          variant="secondary"
          disabled={busy}
          onClick={() => void model.load(true)}
        >
          Reload input choices
        </Button>
      </div>
      {state.error && <p role="alert">{state.error}</p>}
      {!snapshot && (
        <p>Waiting for the explicitly enabled Monaka input runtime.</p>
      )}
      <ul aria-label="Current input usability">
        {snapshot?.candidates.map((source) => (
          <li key={source.id}>
            {[source.publisher, source.source, source.tracker]
              .filter(Boolean)
              .join(' / ')}
            : Q {source.qUsableNow ? 'valid now' : 'invalid now'}, 6DoF{' '}
            {source.sixDofUsableNow ? 'valid now' : 'invalid now'}
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
  return <MonakaInputPanel model={model} />;
}
