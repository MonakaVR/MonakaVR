import { useId, useSyncExternalStore } from 'react';
import { useLocalization } from '@fluent/react';
import { Button } from '@/components/commons/Button';
import {
  isOutputMode,
  OUTPUT_MODES,
  TrackingModeController,
} from '@/monaka-mode/mode-controller';

/** Reusable component, intentionally not mounted in the normal Tracker GUI. */
export function TrackingModeControl({
  model,
}: {
  model: TrackingModeController;
}) {
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const { l10n } = useLocalization();
  const titleId = useId();
  const modeLabel = (mode: string) =>
    isOutputMode(mode)
      ? l10n.getString(
          `monaka-tracking-mode-${mode}`,
          null,
          { ik: 'IK', direct: 'Direct', hybrid: 'Hybrid' }[mode]
        )
      : l10n.getString('monaka-tracking-unknown', null, 'Unknown');
  const assignment = model.assignment;
  return (
    <section
      aria-labelledby={titleId}
      className="bg-background-70 text-background-10 rounded-lg p-5 space-y-4"
    >
      <h2 id={titleId} className="text-xl font-bold">
        Tracking output mode
      </h2>
      <p>
        Choose how this body target produces its virtual tracker pose. Q Source
        and 6DoF Source remain independent choices.
      </p>
      <label className="block">
        Body target{' '}
        <select
          aria-label="Mode body target"
          value={state.body}
          disabled={!state.snapshot || state.busy}
          className="bg-background-80 rounded px-2 py-1"
          onChange={(event) => model.selectBody(event.target.value)}
        >
          {(state.snapshot?.bodies ?? ['HIP']).map((body) => (
            <option key={body}>{body}</option>
          ))}
        </select>
      </label>
      <dl>
        <dt>{l10n.getString('monaka-tracking-active', null, 'Active')}</dt>
        <dd>{modeLabel(assignment?.activeMode ?? 'unknown')}</dd>
        <dt>Saved configuration</dt>
        <dd>{modeLabel(assignment?.savedMode ?? 'unknown')}</dd>
      </dl>
      {state.needsReload && (
        <p>The displayed values require a fresh Core readback.</p>
      )}
      {!assignment && <p>Assign a Main input before choosing a mode.</p>}
      <fieldset disabled={!model.canEdit} className="space-y-2">
        <legend>Output mode</legend>
        {OUTPUT_MODES.map((mode) => {
          const option = assignment?.modeOptions.find(
            (item) => item.mode === mode
          );
          return (
            <label key={mode} className="block">
              <input
                type="radio"
                name={`${titleId}-mode`}
                value={mode}
                checked={model.draft === mode}
                disabled={!option?.available}
                onChange={() => model.selectMode(mode)}
              />{' '}
              {modeLabel(mode)}
              {option?.reason && <span> — {option.reason}</span>}
            </label>
          );
        })}
      </fieldset>
      <ul className="list-disc pl-5">
        <li>
          {modeLabel('ik')}: Existing SlimeVR IK output; usable Main 6DoF
          participates through existing constraints.
        </li>
        <li>
          {modeLabel('direct')}: Direct Main position and rotation while usable.
          Lost position is unavailable.
        </li>
        <li>
          {modeLabel('hybrid')}: Main FULL output; fallback requires
          current-tick background IK explicitly approved in the same world
          space. Reacquisition uses existing sample dwell and interpolation.
        </li>
      </ul>
      {!state.snapshot?.backgroundIkAlignmentConfirmed && (
        <p role="status">
          Shared-world alignment is unconfirmed. Hybrid background IK positional
          fallback is unavailable. Saving Hybrid does not approve alignment.
        </p>
      )}
      <p>
        Saving creates an exact configuration backup and requires a manual
        server restart. It does not verify driver or hardware compatibility.
      </p>
      {state.phase === 'review' && (
        <p role="status">
          Review {state.body}: {modeLabel(assignment?.activeMode ?? 'unknown')}{' '}
          → {modeLabel(model.draft)} in saved configuration. Active output
          changes after restart.
        </p>
      )}
      {state.snapshot?.restartRequired && (
        <p role="status">
          {l10n.getString(
            'monaka-tracking-restart-required',
            null,
            'Restart required'
          )}
          . Input edits are blocked until server restart.
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        <Button
          variant="primary"
          disabled={!model.canReview || state.phase === 'review'}
          onClick={() => model.review()}
        >
          Review mode change
        </Button>
        <Button
          variant="primary"
          disabled={state.phase !== 'review' || !model.canReview}
          loading={state.phase === 'saving'}
          onClick={() => void model.save()}
        >
          Confirm and save configuration
        </Button>
        <Button
          variant="secondary"
          disabled={!model.canEdit || model.draft === assignment?.savedMode}
          onClick={() => model.cancel()}
        >
          Cancel draft
        </Button>
        <Button
          variant="secondary"
          disabled={state.busy}
          onClick={() => void model.load()}
        >
          Reload saved and active modes
        </Button>
      </div>
      {state.error && <p role="alert">{state.error}</p>}
    </section>
  );
}
