import { useLocalization } from '@fluent/react';
import classNames from 'classnames';
import { useMemo, useState, useEffect } from 'react';
import { useForm } from 'react-hook-form';
import {
  AssignTrackerRequestT,
  BodyPart,
  QuatT,
  RpcMessage,
  TrackerIdT,
  SettingsRequestT,
  SettingsResponseT,
  TapDetectionSettingsT,
  ChangeSettingsRequestT,
  TapDetectionSetupNotificationT,
} from 'solarxr-protocol';
import { useChokerWarning } from '@/hooks/choker-warning';
import { useOnboarding } from '@/hooks/onboarding';
import { useWebsocketAPI } from '@/hooks/websocket-api';
import { Button } from '@/components/commons/Button';
import { CheckBox } from '@/components/commons/Checkbox';
import { TipBox } from '@/components/commons/TipBox';
import { Typography } from '@/components/commons/Typography';
import {
  ASSIGNMENT_RULES,
  BodyAssignment,
  LOWER_BODY,
} from '@/components/onboarding/BodyAssignment';
import { NeckWarningModal } from '@/components/onboarding/NeckWarningModal';
import { TrackerSelectionMenu } from './TrackerSelectionMenu';
import { defaultConfig, useConfig } from '@/hooks/config';
import { playTapSetupSound } from '@/sounds/sounds';
import { useBreakpoint } from '@/hooks/breakpoint';
import { TrackerAssignOptions } from './TrackerAssignOptions';
import { useAtomValue } from 'jotai';
import { useLocation, useNavigate } from 'react-router-dom';
import { useUnifiedAssignment } from '@/hooks/unified-assignment';
import { bodyPartForInput, inputRole } from '@/monaka-input/roles';
import { InputAssignmentSummaries } from '@/monaka-input/assignment-summary';
import {
  assignedTrackersAtom,
  FlatDeviceTracker,
  flatTrackersAtom,
} from '@/store/app-store';

export type BodyPartError = {
  label: string | undefined;
  affectedRoles: BodyPart[];
};

interface FlatDeviceTrackerDummy {
  tracker: {
    trackerId: TrackerIdT;
    info: undefined;
  };
}

export function TrackersAssignPage() {
  const { isMd } = useBreakpoint('md');
  const { l10n } = useLocalization();
  const { config, setConfig } = useConfig();
  const { applyProgress, state, slimeSet } = useOnboarding();
  const { sendRPCPacket, useRPCPacket } = useWebsocketAPI();
  const defaultValues = {
    mirrorView: config?.mirrorView ?? defaultConfig.mirrorView,
  };
  const { control, watch } = useForm<{
    mirrorView: boolean;
  }>({ defaultValues });
  const { mirrorView } = watch();
  const unified = useUnifiedAssignment();
  const [selectedRole, setSelectRole] = useState<BodyPart>(BodyPart.NONE);
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => {
    const body = bodyPartForInput(unified.reviewBody ?? '');
    if (
      unified.needsReview &&
      body !== undefined &&
      inputRole(body, unified.state.snapshot?.bodies ?? [])
    )
      setSelectRole(body);
  }, [unified.reviewBody, unified.needsReview, unified.state.snapshot]);
  const inputSummaries = useMemo(() => {
    const snapshot = unified.state.snapshot;
    const label = (id: string | null) => {
      const source = snapshot?.candidates.find((c) => c.id === id);
      return source
        ? [source.publisher, source.source, source.tracker]
            .filter(Boolean)
            .join(' / ')
        : (id ?? '—');
    };
    return Object.fromEntries(
      (snapshot?.assignments ?? []).flatMap((row) => {
        const part = bodyPartForInput(row.body);
        return part === undefined
          ? []
          : [
              [
                part,
                `Q: ${label(row.qSource)} · 6DoF: ${label(row.sixDofSource)}`,
              ],
            ];
      })
    );
  }, [unified.state.snapshot]);

  const chooseRole = (role: BodyPart) => {
    if (unified.state.dirty || unified.working || unified.needsReview) return;
    unified.open(role);
    setSelectRole(role);
  };
  useEffect(() => {
    const request = location.state?.assignmentRequest;
    if (request) setSelectRole(request.body);
  }, [location.state]);
  useEffect(() => {
    const role = inputRole(selectedRole, unified.state.snapshot?.bodies ?? []);
    if (role && role !== unified.state.draft.body && !unified.state.dirty)
      unified.model.selectBody(role);
  }, [selectedRole, unified.state.snapshot]);
  useEffect(() => {
    const request = location.state?.assignmentRequest;
    if (!request || !unified.state.snapshot) return;
    if (unified.open(request.body, request.trackerId, request.clear)) {
      setSelectRole(request.body);
      navigate(location.pathname, {
        replace: true,
        state: { alonePage: true },
      });
    }
  }, [location.state, unified.state.snapshot]);

  const assignedTrackers = useAtomValue(assignedTrackersAtom);
  const trackers = useAtomValue(flatTrackersAtom);

  useEffect(() => {
    setConfig({ mirrorView });
  }, [mirrorView]);

  const [tapDetectionSettings, setTapDetectionSettings] = useState<Omit<
    TapDetectionSettingsT,
    'pack'
  > | null>(null);

  useEffect(() => {
    sendRPCPacket(RpcMessage.SettingsRequest, new SettingsRequestT());
  }, []);

  useRPCPacket(RpcMessage.SettingsResponse, (settings: SettingsResponseT) => {
    if (settings.tapDetectionSettings) {
      setTapDetectionSettings(settings.tapDetectionSettings);
    }
  });

  useEffect(() => {
    if (!tapDetectionSettings) return;
    const newTapSettings = new TapDetectionSettingsT(
      tapDetectionSettings.fullResetDelay,
      tapDetectionSettings.fullResetEnabled,
      tapDetectionSettings.fullResetTaps,
      tapDetectionSettings.yawResetDelay,
      tapDetectionSettings.yawResetEnabled,
      tapDetectionSettings.yawResetTaps,
      tapDetectionSettings.mountingResetDelay,
      tapDetectionSettings.mountingResetEnabled,
      tapDetectionSettings.mountingResetTaps,
      true,
      null,
      tapDetectionSettings.yawResetTracker,
      tapDetectionSettings.fullResetTracker,
      tapDetectionSettings.mountingResetTracker
    );

    sendRPCPacket(
      RpcMessage.ChangeSettingsRequest,
      new ChangeSettingsRequestT(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        newTapSettings
      )
    );

    return () => {
      newTapSettings.setupMode = false;
      sendRPCPacket(
        RpcMessage.ChangeSettingsRequest,
        new ChangeSettingsRequestT(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          newTapSettings
        )
      );
    };
  }, [tapDetectionSettings]);

  const trackerPartGrouped = useMemo(
    () =>
      assignedTrackers.reduce<{ [key: number]: FlatDeviceTracker[] }>(
        (curr, td) => {
          const key = td.tracker.info?.bodyPart || BodyPart.NONE;
          return {
            ...curr,
            [key]: [...(curr[key] || []), td],
          };
        },
        {}
      ),
    [assignedTrackers]
  );

  const rolesWithErrors = useMemo(() => {
    const trackerRoles = [
      ...trackers.map(({ tracker }) => tracker.info?.bodyPart || BodyPart.NONE),
      ...Object.keys(inputSummaries).map(Number),
    ];

    const message = (assignedRole: BodyPart) => {
      const unassignedRoles: [BodyPart | BodyPart[], boolean][] = (
        ASSIGNMENT_RULES[assignedRole] || []
      ).map((part) => [
        part,
        Array.isArray(part)
          ? trackerRoles.some((tr) => part.includes(tr))
          : trackerRoles.includes(part),
      ]);

      // Special exception for waist/hip: https://github.com/SlimeVR/SlimeVR-Server/issues/612
      if (
        (assignedRole === BodyPart.HIP || assignedRole === BodyPart.WAIST) &&
        !trackerRoles.some((t) => LOWER_BODY.has(t))
      ) {
        return;
      }

      if (unassignedRoles.every(([, state]) => state)) return;

      return {
        affectedRoles: unassignedRoles
          .filter(([, state]) => !state)
          .flatMap(([part]) => part),
        label: l10n.getString(
          `onboarding-assign_trackers-warning-${BodyPart[assignedRole]}`,
          {
            unassigned: unassignedRoles
              .map(([, state]) => state)
              .reduce((acc, cur, i) => acc + (Number(cur) << i), 0),
          }
        ),
      };
    };

    return Object.keys(BodyPart)
      .map<BodyPart>((key) => +key)
      .filter((key) => typeof key === 'number' && !Number.isNaN(key))
      .reduce<Record<BodyPart, BodyPartError>>((curr, role) => {
        return {
          ...curr,
          [role]: trackerRoles.find((tr) => tr === role)
            ? message(role)
            : undefined,
        };
      }, {} as any);
  }, [trackers, inputSummaries]);

  const onTrackerSelected = (
    tracker: FlatDeviceTracker | FlatDeviceTrackerDummy | null
  ) => {
    if (unified.needsReview || unified.working) return;
    if (inputRole(selectedRole, unified.state.snapshot?.bodies ?? [])) return;
    if (!window.confirm(l10n.getString('assignment-legacy-only'))) return;
    const assign = (
      role: BodyPart,
      rotation: QuatT | null,
      trackerId: TrackerIdT | null
    ) => {
      const assignreq = new AssignTrackerRequestT();

      assignreq.bodyPosition = role;
      assignreq.mountingOrientation = rotation;
      assignreq.trackerId = trackerId;
      assignreq.allowDriftCompensation = false;

      sendRPCPacket(RpcMessage.AssignTrackerRequest, assignreq);
    };

    (trackerPartGrouped[selectedRole] || []).forEach((td) =>
      assign(
        BodyPart.NONE,
        td.tracker.info?.mountingOrientation || null,
        td.tracker.trackerId
      )
    );

    if (!tracker) {
      setSelectRole(BodyPart.NONE);
      return;
    }
    assign(
      selectedRole,
      tracker.tracker.info?.mountingOrientation || null,
      tracker.tracker.trackerId
    );
    setSelectRole(BodyPart.NONE);
  };

  useRPCPacket(
    RpcMessage.TapDetectionSetupNotification,
    (tapSetup: TapDetectionSetupNotificationT) => {
      if (selectedRole === BodyPart.NONE || !tapSetup.trackerId) return;
      if (inputRole(selectedRole, unified.state.snapshot?.bodies ?? [])) {
        unified.open(selectedRole, tapSetup.trackerId);
        return;
      }
      onTrackerSelected({
        tracker: { trackerId: tapSetup.trackerId, info: undefined },
      });
      playTapSetupSound(config?.feedbackSoundVolume);
    }
  );

  const unassignAll = () => {
    if (unified.needsReview || unified.working) return;
    if (!window.confirm(l10n.getString('assignment-legacy-clear-all'))) return;
    assignedTrackers.forEach((td) => {
      const assignreq = new AssignTrackerRequestT();
      assignreq.bodyPosition = BodyPart.NONE;
      assignreq.trackerId = td.tracker.trackerId;
      sendRPCPacket(RpcMessage.AssignTrackerRequest, assignreq);
    });
  };

  applyProgress(0.5);

  const { closeChokerWarning, tryOpenChokerWarning, shouldShowChokerWarn } =
    useChokerWarning({
      next: chooseRole,
    });

  const firstError = Object.values(rolesWithErrors).find((r) => !!r);

  return (
    <>
      <TrackerSelectionMenu
        bodyPart={selectedRole}
        isOpen={selectedRole !== BodyPart.NONE}
        onClose={() => {
          if (unified.working || unified.state.busy || unified.needsReview)
            return;
          if (
            unified.state.dirty &&
            !window.confirm(l10n.getString('assignment-discard'))
          )
            return;
          unified.model.cancel();
          setSelectRole(BodyPart.NONE);
        }}
        onTrackerSelected={onTrackerSelected}
        unified={
          inputRole(selectedRole, unified.state.snapshot?.bodies ?? [])
            ? unified
            : undefined
        }
      />
      <NeckWarningModal
        isOpen={shouldShowChokerWarn}
        overlayClassName={classNames(
          'fixed top-0 right-0 left-0 bottom-0 flex flex-col items-center w-full h-full justify-center bg-background-90 bg-opacity-90 z-20'
        )}
        onClose={() => closeChokerWarning(true)}
        accept={() => closeChokerWarning(false)}
      />
      <div className="w-full h-full xs:p-4 p-2 flex justify-center">
        <div className="flex xs:gap-4 w-full max-w-screen-xl flex-col md:flex-row">
          <div className="flex flex-col xs:gap-3 gap-2 w-full md:max-w-md">
            <Typography variant="main-title" color="accent">
              {l10n.getString('onboarding-assign_trackers-title')}
            </Typography>
            {unified.needsReview && (
              <div role="alert" data-testid="assignment-review-required">
                <p>{l10n.getString('assignment-status-partial')}</p>
                <Button
                  variant="secondary"
                  disabled={unified.working || unified.state.busy}
                  onClick={() => void unified.reload()}
                >
                  {l10n.getString('monaka-input-reload')}
                </Button>
              </div>
            )}
            {Object.keys(inputSummaries).length > 0 && (
              <p>
                {l10n.getString('assignment-source-roles', {
                  count: Object.keys(inputSummaries).length,
                })}
              </p>
            )}
            <Typography>
              {l10n.getString('onboarding-assign_trackers-description')}
            </Typography>
            <Typography>
              {l10n.getString('onboarding-assign_trackers-assigned', {
                assigned: assignedTrackers.length,
                trackers: trackers.length,
              })}
            </Typography>
            <TipBox className="xs:p-4 w-full">
              {l10n.getString('tips-find_tracker')}
            </TipBox>
            {!!firstError && (
              <div className="bg-status-warning text-background-60 px-3 py-2 text-justify rounded-md">
                <div className="flex flex-col gap-1 whitespace-normal">
                  <span>{firstError.label}</span>
                </div>
              </div>
            )}
            <div className="flex flex-col gap-2">
              <TrackerAssignOptions variant={!isMd ? 'dropdown' : 'radio'} />
              <CheckBox
                control={control}
                label={l10n.getString('onboarding-assign_trackers-mirror_view')}
                name="mirrorView"
                variant="toggle"
              />
              {state.alonePage && (
                <Button
                  variant="secondary"
                  onClick={unassignAll}
                  disabled={
                    unified.working ||
                    unified.state.dirty ||
                    unified.needsReview
                  }
                >
                  {l10n.getString('assignment-legacy-clear-all-button')}
                </Button>
              )}
              <div className="flex flex-row">
                {!state.alonePage && (
                  <>
                    {state.alonePage && (
                      <Button variant="secondary" to="/onboarding/wifi-creds">
                        {l10n.getString('onboarding-previous_step')}
                      </Button>
                    )}
                    {!state.alonePage && (
                      <Button
                        variant="secondary"
                        to={
                          slimeSet &&
                          ['butterfly', 'dongle-slime'].includes(slimeSet)
                            ? '/onboarding/dongle'
                            : '/onboarding/wifi-creds'
                        }
                      >
                        {l10n.getString('onboarding-previous_step')}
                      </Button>
                    )}
                    <Button
                      variant="primary"
                      to="/onboarding/mounting/choose"
                      disabled={
                        assignedTrackers.length === 0 &&
                        Object.keys(inputSummaries).length === 0 &&
                        trackers.length > 0
                      }
                      className="ml-auto"
                    >
                      {l10n.getString('onboarding-continue')}
                    </Button>
                  </>
                )}
              </div>
            </div>
          </div>
          <div className="flex fill-background-50 items-center justify-center flex-grow">
            <div className="md:w-full md:h-full h-full max-w-[770px] flex flex-col overflow-y-clip tall:py-10">
              <InputAssignmentSummaries.Provider value={inputSummaries}>
                <BodyAssignment
                  inputSummaries={inputSummaries}
                  dotSize={15}
                  onlyAssigned={false}
                  highlightedRoles={firstError?.affectedRoles || []}
                  rolesWithErrors={rolesWithErrors}
                  assignMode={config?.assignMode ?? null}
                  mirror={mirrorView}
                  onRoleSelected={tryOpenChokerWarning}
                />
              </InputAssignmentSummaries.Provider>
            </div>
          </div>
        </div>
      </div>
    </>
  );
}
