import { useEffect, useId, useMemo, useSyncExternalStore } from 'react';
import { useLocalization } from '@fluent/react';
import { NavLink } from 'react-router-dom';
import { BodyPart } from 'solarxr-protocol';
import { Typography } from '@/components/commons/Typography';
import {
  TrackerCardSurface,
  TrackerCompactContent,
  TrackerStatusPresentation,
} from '@/components/tracker/TrackerCardPresentation';
import { MonakaInputSocket } from '@/monaka-input/socket';
import {
  sourceDetails,
  sourceLabel,
  sourcePhysicalId,
  trackerSourceLabel,
} from '@/monaka-input/source-label';
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
  const publicCandidates = rows
    .filter((row) => row.route === 'mvip' && !row.conflict)
    .map((row) => row.candidate);
  const unknown = l10n.getString('monaka-input-unknown');
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
    <section aria-labelledby={titleId} className="min-w-0">
      <div className="flex w-full gap-2 items-center px-4 h-5">
        <div id={titleId}>
          <Typography color="secondary">{text('title')}</Typography>
        </div>
        <div className="bg-background-50 h-[2px] rounded-lg flex-grow" />
        <NavLink
          className="text-sm text-background-30 hover:text-background-20"
          to={inputAssignmentsHref(search)}
          state={{ alonePage: true }}
        >
          {text('assignments')}
        </NavLink>
      </div>
      {!state.fresh && (
        <p role="status" className="px-5 pt-5 text-background-10">
          {text(state.snapshot || state.failed ? 'stale' : 'waiting')}
        </p>
      )}
      {state.fresh && rows.length === 0 && (
        <p role="status" className="px-5 pt-5 text-background-10">
          {text('empty')}
        </p>
      )}
      {state.snapshot?.restartRequired && (
        <p className="px-5 pt-5 text-background-10">{text('restart')}</p>
      )}
      <ul className="grid sm:grid-cols-1 md:grid-cols-2 gap-4 px-5 my-5">
        {rows.map((row) => {
          const roles = [...new Set(row.savedRoles.map(({ body }) => body))];
          const bodyPart =
            roles.length === 1
              ? (bodyPartForInput(roles[0]) ?? BodyPart.NONE)
              : BodyPart.NONE;
          const savedRoles = roles.length
            ? roles.map(bodyLabel).join(', ')
            : l10n.getString('monaka-input-unassigned');
          const status = row.conflict
            ? 'conflict'
            : row.qUsableNow === true || row.sixDofUsableNow === true
              ? 'available'
              : row.qUsableNow === false && row.sixDofUsableNow === false
                ? 'unavailable'
                : 'unknown';
          const statusClass =
            status === 'available'
              ? 'bg-status-success'
              : status === 'conflict'
                ? 'bg-status-warning'
                : 'bg-background-30';
          const hasPublicIdentity = row.route === 'mvip' && !row.conflict;
          const name = hasPublicIdentity
            ? trackerSourceLabel(row.candidate, publicCandidates, unknown)
            : sourceLabel(row.candidate, candidates, unknown);
          const physicalId = hasPublicIdentity
            ? sourcePhysicalId(row.candidate)
            : undefined;
          const compactId = physicalId ? `ID : ${physicalId}` : undefined;
          return (
            <li
              key={row.id}
              data-input-id={row.id}
              className="relative min-w-0"
            >
              <TrackerCardSurface asDetails interactable>
                <summary
                  className="list-none [&::-webkit-details-marker]:hidden focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent-background-40 focus-visible:-outline-offset-2"
                  title={`${text('saved-roles')}: ${savedRoles}`}
                  aria-label={`${name}${compactId ? `; ${compactId}` : ''}; ${text(status)}; ${text('saved-roles')}: ${savedRoles}; ${text('details')}`}
                >
                  <TrackerCompactContent
                    bodyPart={bodyPart}
                    name={name}
                    status={
                      <TrackerStatusPresentation
                        label={text(status)}
                        statusClass={statusClass}
                      />
                    }
                    telemetry={
                      compactId && (
                        <span
                          className="flex max-w-[42%] shrink-0 items-center gap-1 text-xs text-background-30"
                          title={compactId}
                        >
                          <span
                            aria-hidden="true"
                            className="shrink-0 [details[open]_&]:rotate-90"
                          >
                            ▶
                          </span>
                          <span className="truncate">{compactId}</span>
                        </span>
                      )
                    }
                  />
                </summary>
                <div className="px-4 pb-3 text-xs text-background-10 break-words space-y-2">
                  <p>
                    {text('route')}:{' '}
                    {row.route === 'mvip' ? 'MVIP' : text('route-unknown')}
                  </p>
                  <p>
                    {l10n.getString('monaka-tracking-capability')}:{' '}
                    {row.capabilities?.some(
                      (capability) =>
                        capability === 'position' ||
                        capability === 'orientation'
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
                </div>
              </TrackerCardSurface>
            </li>
          );
        })}
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
