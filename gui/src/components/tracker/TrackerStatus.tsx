import { useMemo } from 'react';
import { TrackerStatus as TrackerStatusEnum } from 'solarxr-protocol';
import { TrackerStatusPresentation } from './TrackerCardPresentation';
import { useLocalization } from '@fluent/react';

const statusLabelMap: { [key: number]: string } = {
  [TrackerStatusEnum.NONE]: 'tracker-status-none',
  [TrackerStatusEnum.BUSY]: 'tracker-status-busy',
  [TrackerStatusEnum.ERROR]: 'tracker-status-error',
  [TrackerStatusEnum.DISCONNECTED]: 'tracker-status-disconnected',
  [TrackerStatusEnum.OCCLUDED]: 'tracker-status-occluded',
  [TrackerStatusEnum.OK]: 'tracker-status-ok',
  [TrackerStatusEnum.TIMED_OUT]: 'tracker-status-timed_out',
};

const statusClassMap: { [key: number]: string } = {
  [TrackerStatusEnum.NONE]: 'bg-background-30',
  [TrackerStatusEnum.BUSY]: 'bg-status-warning',
  [TrackerStatusEnum.ERROR]: 'bg-status-critical',
  [TrackerStatusEnum.DISCONNECTED]: 'bg-background-30',
  [TrackerStatusEnum.OCCLUDED]: 'bg-status-warning',
  [TrackerStatusEnum.OK]: 'bg-status-success',
  [TrackerStatusEnum.TIMED_OUT]: 'bg-status-warning',
};

export function TrackerStatus({ status }: { status: number }) {
  const { l10n } = useLocalization();

  const statusClass = useMemo(() => statusClassMap[status], [status]);
  const statusLabel = useMemo(() => statusLabelMap[status], [status]);

  return (
    <TrackerStatusPresentation
      label={l10n.getString(statusLabel)}
      statusClass={statusClass}
    />
  );
}
