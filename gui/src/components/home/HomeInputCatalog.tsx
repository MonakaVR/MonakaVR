import { useEffect, useId, useMemo, useSyncExternalStore } from 'react';
import { useLocalization } from '@fluent/react';
import { NavLink } from 'react-router-dom';
import { BodyPart } from 'solarxr-protocol';
import { MonakaInputSocket } from '@/monaka-input/socket';
import { sourceDetails, sourceLabel } from '@/monaka-input/source-label';
import { bodyPartForInput } from '@/monaka-input/roles';
import {
  buildHomeInputCatalog,
  HomeInputCatalogReader,
  inputAssignmentsHref,
} from '@/monaka-input/home-catalog';

/** Injecting the snapshot reader also permits isolated browser verification. */
export function HomeInputCatalogPanel({
  reader,
  search = '',
}: {
  reader: HomeInputCatalogReader;
  search?: string;
}) {
  const state = useSyncExternalStore(
    reader.subscribe,
    () => reader.state,
    () => reader.state
  );
  const { l10n } = useLocalization();
  const titleId = useId();
  const text = (key: string) => l10n.getString(`home-input-${key}`);
  const rows = buildHomeInputCatalog(state.snapshot, state.fresh);
  const candidates = rows.map((row) => row.candidate);
  const bodyLabel = (body: string) => {
    const part = bodyPartForInput(body);
    return part === undefined
      ? l10n.getString('monaka-input-unknown')
      : l10n.getString(`body_part-${BodyPart[part]}`);
  };
  const usability = (value: boolean | null) =>
    l10n.getString(
      `monaka-input-${value === null ? 'unknown' : value ? 'valid' : 'invalid'}`
    );
  return (
    <section
      aria-labelledby={titleId}
      className="mx-4 p-4 rounded-lg bg-background-70 text-background-10 min-w-0 space-y-3"
    >
      <h2 id={titleId} className="text-xl font-bold">
        {text('title')}
      </h2>
      <NavLink className="underline" to={inputAssignmentsHref(search)}>
        {text('assignments')}
      </NavLink>
      {!state.fresh && (
        <p role="status">
          {text(state.snapshot || state.failed ? 'stale' : 'waiting')}
        </p>
      )}
      {state.fresh && rows.length === 0 && <p role="status">{text('empty')}</p>}
      {state.snapshot?.restartRequired && <p>{text('restart')}</p>}
      <ul className="grid sm:grid-cols-1 md:grid-cols-2 gap-3">
        {rows.map((row) => (
          <li
            key={row.id}
            data-input-id={row.id}
            className="p-3 rounded bg-background-80 min-w-0 break-words space-y-2"
          >
            <h3 className="font-bold">
              {sourceLabel(
                row.candidate,
                candidates,
                l10n.getString('monaka-input-unknown')
              )}
            </h3>
            <p role="status">
              {text(
                row.conflict
                  ? 'conflict'
                  : row.qUsableNow === true || row.sixDofUsableNow === true
                    ? 'available'
                    : row.qUsableNow === false && row.sixDofUsableNow === false
                      ? 'unavailable'
                      : 'unknown'
              )}
            </p>
            <p>
              {row.savedRoles.length
                ? [...new Set(row.savedRoles.map(({ body }) => body))]
                    .map(bodyLabel)
                    .join(', ')
                : l10n.getString('monaka-input-unassigned')}
            </p>
            <details className="min-w-0 text-xs font-normal space-y-2">
              <summary className="cursor-pointer">{text('details')}</summary>
              <p>
                {text('route')}:{' '}
                {row.route === 'mvip' ? 'MVIP' : text('route-unknown')}
              </p>
              <p>
                {l10n.getString('monaka-tracking-capability')}:{' '}
                {row.capabilities?.some(
                  (capability) =>
                    capability === 'position' || capability === 'orientation'
                )
                  ? ['position', 'orientation']
                      .filter((capability) =>
                        row.capabilities?.includes(capability)
                      )
                      .map((capability) => text(capability))
                      .join(' + ')
                  : l10n.getString('monaka-input-support-unknown')}
              </p>
              <p>
                {l10n.getString('monaka-input-usability')}: Q{' '}
                {usability(row.qUsableNow)}, 6DoF{' '}
                {usability(row.sixDofUsableNow)}
              </p>
              <p>
                {text('saved-roles')}:{' '}
                {row.savedRoles.length
                  ? row.savedRoles
                      .map(
                        ({ body, kind }) =>
                          `${bodyLabel(body)} (${kind === 'q' ? 'Q' : '6DoF'})`
                      )
                      .join(', ')
                  : l10n.getString('monaka-input-unassigned')}
              </p>
              <pre className="whitespace-pre-wrap break-all max-w-full">
                {row.variants
                  .map(
                    (candidate) =>
                      `${sourceDetails(candidate)}\nOrigin: ${candidate.origin}\nCapabilities: ${candidate.capabilities?.join(', ') ?? l10n.getString('monaka-input-support-unknown')}`
                  )
                  .join('\n\n')}
              </pre>
              <p>{text('saved-help')}</p>
            </details>
          </li>
        ))}
      </ul>
    </section>
  );
}

export function HomeInputCatalog() {
  const { api, reader } = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const host = params.get('ip') ?? 'localhost';
    const port = params.get('port') ?? '21110';
    const api = new MonakaInputSocket(`ws://${host}:${port}`);
    return { api, reader: new HomeInputCatalogReader(api) };
  }, []);
  useEffect(() => {
    void reader.load();
    const timer = setInterval(() => void reader.load(), 2000);
    return () => {
      clearInterval(timer);
      reader.stop();
      api.close();
    };
  }, [api, reader]);
  return (
    <HomeInputCatalogPanel reader={reader} search={window.location.search} />
  );
}
