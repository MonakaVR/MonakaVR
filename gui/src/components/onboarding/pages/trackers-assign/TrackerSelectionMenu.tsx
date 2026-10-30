import classNames from 'classnames';
import ReactModal from 'react-modal';
import { BodyPart } from 'solarxr-protocol';
import { Button } from '@/components/commons/Button';
import { TipBox } from '@/components/commons/TipBox';
import { Typography } from '@/components/commons/Typography';
import { TrackerCard } from '@/components/tracker/TrackerCard';
import { useLocalization } from '@fluent/react';
import { useAtomValue } from 'jotai';
import type { UnifiedAssignment } from '@/hooks/unified-assignment';
import { sourceOtherBody } from '@/monaka-input/assignment';
import {
  assignedTrackersAtom,
  FlatDeviceTracker,
  unassignedTrackersAtom,
} from '@/store/app-store';

export function TrackerSelectionMenu({
  isOpen = true,
  onClose,
  onTrackerSelected,
  bodyPart,
  unified,
}: {
  isOpen: boolean;
  bodyPart: BodyPart;
  onClose: () => void;
  onTrackerSelected: (tracker: FlatDeviceTracker | null) => void;
  unified?: UnifiedAssignment;
}) {
  const { l10n } = useLocalization();

  const unassignedTrackers = useAtomValue(unassignedTrackersAtom);
  const assignedTrackers = useAtomValue(assignedTrackersAtom);

  return (
    <>
      <ReactModal
        isOpen={isOpen}
        shouldCloseOnOverlayClick={!unified?.working}
        shouldCloseOnEsc={!unified?.working}
        onRequestClose={() => !unified?.working && onClose()}
        overlayClassName={classNames(
          'fixed top-0 right-0 left-0 bottom-0 flex flex-col items-center w-full h-full bg-background-90 bg-opacity-90 z-20'
        )}
        className={classNames(
          'focus:ring-transparent focus:ring-offset-transparent focus:outline-transparent outline-none z-10 h-full pt-10'
        )}
      >
        <div className="flex w-full h-full flex-col px-4">
          <div className="flex w-full flex-col flex-grow h-0 items-center gap-3 py-4">
            <Typography variant="main-title" bold>
              {l10n.getString('tracker_selection_menu-' + BodyPart[bodyPart])}
            </Typography>
            {unified?.state.snapshot ? (
              <UnifiedChoices
                unified={unified}
                bodyPart={bodyPart}
                onClose={onClose}
              />
            ) : (
              <>
                <p role="status">{l10n.getString('assignment-legacy-only')}</p>
                <div className="w-full max-w-sm">
                  <TipBox>{l10n.getString('tips-tap_setup')}</TipBox>
                </div>
                <div className="w-full p-2 flex flex-col flex-grow h-0 gap-4 overflow-y-auto">
                  {unassignedTrackers.length && (
                    <>
                      <Typography>
                        {l10n.getString('tracker_selection_menu-unassigned')}
                      </Typography>
                      <div className="grid xs:grid-cols-2 mobile:grid-cols-1 gap-3">
                        {unassignedTrackers.map((fd, index) => (
                          <TrackerCard
                            key={index}
                            tracker={fd.tracker}
                            device={fd.device}
                            onClick={() => onTrackerSelected(fd)}
                            smol
                            interactable
                            outlined={
                              bodyPart ===
                              (fd.tracker.info?.bodyPart || BodyPart.NONE)
                            }
                          />
                        ))}
                      </div>
                    </>
                  )}
                  <Typography>
                    {l10n.getString('tracker_selection_menu-assigned')}
                  </Typography>
                  <div className=" grid xs:grid-cols-2 mobile:grid-cols-1 gap-3">
                    {assignedTrackers.map((fd, index) => (
                      <TrackerCard
                        key={index}
                        tracker={fd.tracker}
                        device={fd.device}
                        onClick={() => onTrackerSelected(fd)}
                        smol
                        interactable
                        outlined={
                          bodyPart ===
                          (fd.tracker.info?.bodyPart || BodyPart.NONE)
                        }
                      />
                    ))}
                  </div>
                </div>
                <div className="flex w-full justify-end" onClick={onClose}>
                  <div className="flex flex-col justify-end pointer-events-auto">
                    <Button
                      variant="primary"
                      onClick={() => onTrackerSelected(null)}
                    >
                      {l10n.getString('tracker_selection_menu-dont_assign')}
                    </Button>
                  </div>
                </div>
              </>
            )}
          </div>
        </div>
      </ReactModal>
    </>
  );
}

function UnifiedChoices({
  unified,
  bodyPart,
  onClose,
}: {
  unified: UnifiedAssignment;
  bodyPart: BodyPart;
  onClose: () => void;
}) {
  const { l10n } = useLocalization();
  const text = (key: string) => l10n.getString(`assignment-${key}`);
  const { model, state } = unified;
  const snapshot = state.snapshot!;
  const saved = snapshot.assignments.find(
    (row) => row.body === state.draft.body
  );
  const label = (id: string | null | undefined) => {
    if (!id) return text('none');
    const source = snapshot.candidates.find((c) => c.id === id);
    return source
      ? [source.publisher, source.source, source.tracker]
          .filter(Boolean)
          .join(' / ')
      : `${text('offline')}: ${id}`;
  };
  const unavailable = [state.draft.qSource, state.draft.sixDofSource].filter(
    (id, index, all): id is string =>
      !!id &&
      all.indexOf(id) === index &&
      !snapshot.candidates.some((c) => c.id === id)
  );
  return (
    <div
      className="w-full max-w-3xl min-h-0 flex flex-col gap-3 overflow-y-auto p-3 rounded-xl bg-background-80 text-background-10"
      data-testid="unified-assignment"
    >
      {unified.needsReview && (
        <p role="status" data-testid="assignment-recovery-warning">
          {text(`status-${unified.status || 'partial'}`)}
        </p>
      )}
      <p>{l10n.getString('monaka-input-semantics')}</p>
      <p>{text('select-help')}</p>
      <div className="overflow-x-auto shrink-0">
        <table className="w-full text-left">
          <caption className="text-left">{text('sources')}</caption>
          <thead>
            <tr>
              <th>{text('source')}</th>
              <th>{l10n.getString('monaka-input-q')}</th>
              <th>{l10n.getString('monaka-input-six')}</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <th>{text('none')}</th>
              <td>
                <input
                  type="radio"
                  name="assignment-q"
                  aria-label={`${l10n.getString('monaka-input-q')}: ${text('none')}`}
                  checked={!state.draft.qSource}
                  disabled={!unified.canEdit}
                  onChange={() => model.selectQ(null)}
                />
              </td>
              <td>
                <input
                  type="radio"
                  name="assignment-six"
                  aria-label={`${l10n.getString('monaka-input-six')}: ${text('none')}`}
                  checked={!state.draft.sixDofSource}
                  disabled={!unified.canEdit}
                  onChange={() => model.selectSix(null)}
                />
              </td>
            </tr>
            {unified.candidates.map((entry) => {
              const { source } = entry;
              const other = sourceOtherBody(
                snapshot,
                source.id,
                state.draft.body
              );
              const unsafeSlime =
                entry.kind === 'slime' &&
                (!entry.tracker ||
                  entry.tracker.tracker.info?.editable !== true);
              const disabled = !unified.canEdit || !!other || unsafeSlime;
              return (
                <tr key={source.id} className="border-t border-background-50">
                  <th className="p-2 break-all font-normal max-w-sm">
                    <span>{label(source.id)}</span>
                    <span className="block text-sm">
                      {entry.kind === 'slime' ? text('slime') : 'MVIP'} ·{' '}
                      {source.capabilities?.join(', ') || text('unknown')}
                    </span>
                    <span className="block text-sm">
                      Q:{' '}
                      {state.snapshotFresh
                        ? source.qUsableNow
                          ? text('usable')
                          : text('unusable')
                        : text('unknown')}{' '}
                      · 6DoF:{' '}
                      {state.snapshotFresh
                        ? source.sixDofUsableNow
                          ? text('usable')
                          : text('unusable')
                        : text('unknown')}
                    </span>
                    {other && (
                      <span className="block">
                        {text('other-body')}: {other}
                      </span>
                    )}
                    {unsafeSlime && (
                      <span className="block">
                        {text('association-unavailable')}
                      </span>
                    )}
                    {source.conflict && (
                      <span className="block">{text('conflict')}</span>
                    )}
                    {entry.kind === 'slime' && entry.tracker && (
                      <span className="block">
                        {text('legacy-active')}:{' '}
                        {l10n.getString(
                          `body_part-${BodyPart[entry.tracker.tracker.info?.bodyPart ?? BodyPart.NONE]}`
                        )}
                      </span>
                    )}
                  </th>
                  <td>
                    <input
                      type="radio"
                      name="assignment-q"
                      aria-label={`${l10n.getString('monaka-input-q')}: ${label(source.id)}`}
                      checked={state.draft.qSource === source.id}
                      disabled={disabled || !source.qSelectable}
                      onChange={() => model.selectQ(source.id)}
                    />
                  </td>
                  <td>
                    <input
                      type="radio"
                      name="assignment-six"
                      aria-label={`${l10n.getString('monaka-input-six')}: ${label(source.id)}`}
                      checked={state.draft.sixDofSource === source.id}
                      disabled={disabled || !source.sixDofSelectable}
                      onChange={() => model.selectSix(source.id)}
                    />
                  </td>
                </tr>
              );
            })}
            {unavailable.map((id) => (
              <tr key={id}>
                <th className="break-all">{label(id)}</th>
                <td>{state.draft.qSource === id ? '✓' : ''}</td>
                <td>{state.draft.sixDofSource === id ? '✓' : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {state.snapshot?.restartRequired && (
        <p data-testid="assignment-active">
          {text('active')}: Q = {label(saved?.qSource)}; 6DoF = {label(saved?.sixDofSource)}
        </p>
      )}
      <p data-testid="assignment-readback">
        {text('saved')}: Q = {label(saved?.savedQSource === undefined ? saved?.qSource : saved.savedQSource)}; 6DoF ={' '}
        {label(saved?.savedSixDofSource === undefined ? saved?.sixDofSource : saved.savedSixDofSource)}
      </p>
      <p>{l10n.getString('monaka-input-readback')}</p>
      {state.snapshot?.restartRequired && (
        <p role="status">
          {l10n.getString('monaka-tracking-restart-required')}
        </p>
      )}
      {(state.error || unified.detail) && (
        <p role="alert">{state.error || unified.detail}</p>
      )}
      {unified.status && !state.dirty && !unified.needsReview && (
        <p role="status">{text(`status-${unified.status}`)}</p>
      )}
      {unified.confirmation && (
        <div
          role="alertdialog"
          aria-label={text('confirm-title')}
          className="p-3 bg-background-60 space-y-2"
        >
          <p>{text('confirm-title')}</p>
          {unified.confirmation.reset && <p>{text('reset-warning')}</p>}
          {unified.confirmation.changes.map((change, i) => (
            <p key={i}>
              {text('legacy-change')}: {change.id.deviceId?.id ?? '—'}/
              {change.id.trackerNum}: {BodyPart[change.from]} →{' '}
              {BodyPart[change.to]}
            </p>
          ))}
          <p>{text('two-writers')}</p>
          <Button variant="primary" onClick={unified.confirm}>
            {text('confirm')}
          </Button>
          <Button variant="secondary" onClick={unified.cancelConfirmation}>
            {text('back')}
          </Button>
        </div>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="primary"
          disabled={!unified.canEdit || !state.dirty}
          onClick={() => unified.save(bodyPart)}
        >
          {l10n.getString('monaka-input-save')}
        </Button>
        <Button
          variant="secondary"
          disabled={
            unified.working ||
            state.busy ||
            unified.needsReview ||
            !!unified.confirmation
          }
          onClick={() => model.cancel()}
        >
          {l10n.getString('monaka-input-cancel')}
        </Button>
        <Button
          variant="secondary"
          disabled={unified.working || state.busy}
          onClick={() => void unified.reload()}
        >
          {l10n.getString('monaka-input-reload')}
        </Button>
        <Button
          variant="secondary"
          disabled={
            unified.working || state.busy || state.dirty || unified.needsReview
          }
          onClick={onClose}
        >
          {text('close')}
        </Button>
      </div>
    </div>
  );
}
