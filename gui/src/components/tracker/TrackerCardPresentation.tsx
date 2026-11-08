import classNames from 'classnames';
import type { CSSProperties, MouseEventHandler, ReactNode } from 'react';
import type { BodyPart } from 'solarxr-protocol';
import { BodyPartIcon } from '@/components/commons/BodyPartIcon';
import { Typography } from '@/components/commons/Typography';
import { WarningIcon } from '@/components/commons/icon/WarningIcon';

/** Shared card appearance; tracker behavior remains in the owning adapter. */
export function TrackerCardSurface({
  children,
  asDetails = false,
  interactable = false,
  outlined = false,
  bg = 'bg-background-60',
  onClick,
  style,
}: {
  children: ReactNode;
  asDetails?: boolean;
  interactable?: boolean;
  outlined?: boolean;
  bg?: string;
  onClick?: MouseEventHandler<HTMLDivElement>;
  style?: CSSProperties;
}) {
  const className = classNames(
    'rounded-lg overflow-hidden transition-[box-shadow] duration-200 ease-linear',
    interactable && 'hover:bg-background-50 cursor-pointer',
    outlined && 'outline outline-2 outline-accent-background-40',
    bg
  );
  return asDetails ? (
    <details className={className} style={style}>
      {children}
    </details>
  ) : (
    <div onClick={onClick} className={className} style={style}>
      {children}
    </div>
  );
}

/** The original compact tracker's exact 70px layout, including warning area. */
export function TrackerCompactContent({
  bodyPart,
  name,
  status,
  warning = false,
  telemetry,
}: {
  bodyPart?: BodyPart;
  name: ReactNode;
  status: ReactNode;
  warning?: boolean;
  telemetry?: ReactNode;
}) {
  return (
    <div className="flex rounded-md py-3 px-4 w-full gap-4 h-[70px]">
      <div className="flex flex-col justify-center items-center fill-background-10 relative">
        {warning && (
          <div className="absolute -right-2 -bottom-3 text-status-warning ">
            <WarningIcon width={20} />
          </div>
        )}
        <div
          className={classNames(
            'border-[3px] border-opacity-80 rounded-md overflow-clip',
            {
              'border-status-warning': warning,
              'border-transparent': !warning,
            }
          )}
        >
          <BodyPartIcon bodyPart={bodyPart} width={40} />
        </div>
      </div>
      <div className="flex flex-col flex-grow justify-center gap-1 min-w-0">
        <Typography bold truncate variant="section-title">
          {name}
        </Typography>
        {status}
      </div>
      {telemetry}
    </div>
  );
}

export function TrackerStatusPresentation({
  label,
  statusClass,
}: {
  label: string;
  statusClass?: string;
}) {
  return (
    <div className="flex text-default gap-2">
      <div className="flex flex-col justify-center">
        <div className={classNames('w-2 h-2 rounded-full', statusClass)} />
      </div>
      <Typography whitespace="whitespace-nowrap">{label}</Typography>
    </div>
  );
}
