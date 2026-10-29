import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useAtomValue } from 'jotai';
import { AssignTrackerRequestT, BodyPart, RpcMessage } from 'solarxr-protocol';
import { flatTrackersAtom } from '@/store/app-store';
import { InputSelectionController } from '@/monaka-input/controller';
import { MonakaInputSocket } from '@/monaka-input/socket';
import {
  executeAssignment,
  legacyObserved,
  locatorKey,
  planAssignment,
  presentCandidates,
  type AssignmentPlan,
  type LegacyTracker,
  type Locator,
} from '@/monaka-input/assignment';
import { inputRole } from '@/monaka-input/roles';
import { useWebsocketAPI } from './websocket-api';

export function useUnifiedAssignment() {
  const trackers = useAtomValue(flatTrackersAtom);
  const ws = useWebsocketAPI();
  const live = useRef({ trackers, ws });
  live.current = { trackers, ws };
  const { api, model } = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const api = new MonakaInputSocket(
      `ws://${params.get('ip') ?? 'localhost'}:${params.get('port') ?? '21110'}`
    );
    return { api, model: new InputSelectionController(api) };
  }, []);
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const [working, setWorking] = useState(false);
  const [status, setStatus] = useState('');
  const [detail, setDetail] = useState('');
  const [needsReview, setNeedsReview] = useState(false);
  const [confirmation, setConfirmation] = useState<AssignmentPlan | null>(null);
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    void model.load();
    const timer = setInterval(() => void model.load(), 2000);
    return () => {
      alive.current = false;
      clearInterval(timer);
      api.close();
    };
  }, [api, model]);

  const current = (): LegacyTracker[] =>
    live.current.trackers.flatMap(({ tracker }) =>
      tracker.trackerId
        ? [
            {
              id: tracker.trackerId,
              body: tracker.info?.bodyPart ?? BodyPart.NONE,
              editable: tracker.info?.editable === true,
            },
          ]
        : []
    );

  const open = (body: BodyPart, preferred?: Locator, clear = false) => {
    if (working || model.state.busy || model.state.dirty || needsReview) return false;
    const role = inputRole(body, model.state.snapshot?.bodies ?? []);
    if (!role) return false;
    model.selectBody(role);
    setStatus('');
    setDetail('');
    setConfirmation(null);
    if (preferred) {
      const key = locatorKey(preferred);
      const matches =
        model.state.snapshot?.candidates.filter(
          (c) => key !== null && locatorKey(c.slimeTrackerId) === key
        ) ?? [];
      if (matches.length !== 1) {
        setDetail(
          'Core cannot safely associate this physical tracker. Reload or update Core.'
        );
      } else if (clear) {
        if (
          model.state.draft.qSource === matches[0].id ||
          model.state.draft.qSource === null
        )
          model.selectQ(null);
        else {
          // No saved source relation: do not silently clear an unrelated Monaka Q.
          setDetail(
            'Review this body and explicitly select or clear its Q source before saving.'
          );
        }
      } else model.selectQ(matches[0].id);
    }
    return true;
  };

  const perform = async (plan: AssignmentPlan) => {
    if (working || needsReview) return;
    setWorking(true);
    setConfirmation(null);
    setDetail('');
    try {
      const result = await executeAssignment(plan, {
        save: () => model.save(),
        current,
        connected: () => alive.current && live.current.ws.isConnected,
        write: (change) => {
          const actual = live.current.trackers.find(
            (t) => locatorKey(t.tracker.trackerId) === locatorKey(change.id)
          );
          if (!actual) throw new Error('Legacy tracker disappeared after Core save.');
          const request = new AssignTrackerRequestT();
          request.trackerId = actual.tracker.trackerId;
          request.bodyPosition = change.to;
          request.mountingOrientation =
            actual.tracker.info?.mountingOrientation ?? null;
          request.allowDriftCompensation =
            actual.tracker.info?.allowDriftCompensation ?? false;
          live.current.ws.sendRPCPacket(RpcMessage.AssignTrackerRequest, request);
        },
        observe: async () => {
          const deadline = Date.now() + 5000;
          while (
            alive.current &&
            live.current.ws.isConnected &&
            Date.now() < deadline
          ) {
            if (legacyObserved(plan, current())) return true;
            await new Promise((resolve) => setTimeout(resolve, 50));
          }
          return false;
        },
      });
      setStatus(result);
      if (result === 'partial' || result === 'save-unknown') setNeedsReview(true);
    } catch (error) {
      setStatus('partial');
      setDetail(String(error));
      setNeedsReview(true);
    } finally {
      setWorking(false);
    }
  };

  const save = (body: BodyPart) => {
    if (!model.canEdit || !state.dirty || working || needsReview) return;
    try {
      if (
        !state.snapshot ||
        inputRole(body, state.snapshot.bodies) !== state.draft.body
      )
        throw new Error('Unsupported or changed body target. Reload before saving.');
      if (!live.current.ws.isConnected)
        throw new Error('Legacy data feed is disconnected.');
      const plan = planAssignment(
        state.snapshot,
        state.draft,
        body,
        current(),
        state.qEdited
      );
      if (plan.changes.length || plan.reset) setConfirmation(plan);
      else void perform(plan);
    } catch (error) {
      setDetail(error instanceof Error ? error.message : String(error));
    }
  };

  const reload = async () => {
    if (working) return;
    setConfirmation(null);
    await model.load(true);
    if (model.state.snapshotFresh && !model.state.needsReload) {
      setNeedsReview(false);
      setStatus('');
      setDetail('');
    }
  };
  return {
    model,
    state,
    working,
    status,
    detail,
    needsReview,
    confirmation,
    open,
    save,
    reload,
    confirm: () => confirmation && void perform(confirmation),
    cancelConfirmation: () => setConfirmation(null),
    candidates: presentCandidates(
      state.snapshot?.candidates ?? [],
      trackers,
      (t) => t.tracker.trackerId
    ),
    canEdit: model.canEdit && !working && !needsReview && !confirmation,
  };
}

export type UnifiedAssignment = ReturnType<typeof useUnifiedAssignment>;
