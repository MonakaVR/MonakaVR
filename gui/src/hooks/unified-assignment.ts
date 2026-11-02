import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useAtomValue } from 'jotai';
import {
  AssignTrackerRequestT,
  BodyPart,
  DataFeedMessage,
  DataFeedUpdateT,
  RpcMessage,
} from 'solarxr-protocol';
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
import {
  assignmentTransactions as transactions,
  assignmentReviewFeed,
} from '@/monaka-input/transactions';
import { useWebsocketAPI } from './websocket-api';

export function useUnifiedAssignment() {
  const trackers = useAtomValue(flatTrackersAtom);
  const ws = useWebsocketAPI();
  const live = useRef({ trackers, ws });
  live.current = { trackers, ws };
  const { api, model, endpoint } = useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const endpoint = new URL(
      `ws://${params.get('ip') ?? 'localhost'}:${params.get('port') ?? '21110'}`
    ).href;
    const api = new MonakaInputSocket(endpoint);
    return { api, model: new InputSelectionController(api), endpoint };
  }, []);
  const restored = useRef(transactions.read(endpoint));
  const feedRevision = useRef(0);
  ws.useDataFeedPacket(DataFeedMessage.DataFeedUpdate, (packet: DataFeedUpdateT) => {
    const pending = transactions.read(endpoint);
    if (packet.index === 0 && (!pending || assignmentReviewFeed(pending, packet)))
      feedRevision.current++;
  });
  const state = useSyncExternalStore(model.subscribe, () => model.state);
  const [working, setWorking] = useState(false);
  const [status, setStatus] = useState(restored.current ? 'partial' : '');
  const [detail, setDetail] = useState('');
  const [needsReview, setNeedsReview] = useState(!!restored.current);
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
    let pending: ReturnType<typeof transactions.begin> | undefined;
    try {
      pending = transactions.begin(
        endpoint,
        model.state.draft,
        plan.changes.map((change) => change.id)
      );
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
      transactions.settled(pending.token);
      if (alive.current) {
        const success =
          (result === 'saved' || result === 'legacy-observed') &&
          live.current.ws.isConnected;
        if (success) transactions.clear(endpoint, pending.token);
        setStatus(success ? result : result === 'save-unknown' ? result : 'partial');
        setNeedsReview(!success);
      }
    } catch (error) {
      // begin() rejection precedes both writers; do not claim a partial save.
      setStatus(pending ? 'partial' : '');
      setDetail(String(error));
      setNeedsReview(!!pending);
    } finally {
      if (pending) transactions.settled(pending.token);
      if (alive.current) setWorking(false);
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
    const pending = transactions.read(endpoint);
    if (transactions.isActive(endpoint)) {
      setDetail(
        'The previous save is still awaiting its outcome. Reload again after it settles.'
      );
      return;
    }
    setConfirmation(null);
    const beforeFeed = feedRevision.current;
    await model.load(true);
    if (pending && model.state.snapshotFresh && !model.state.busy) {
      setWorking(true);
      const deadline = Date.now() + 3000;
      while (
        alive.current &&
        live.current.ws.isConnected &&
        feedRevision.current === beforeFeed &&
        Date.now() < deadline
      )
        await new Promise((resolve) => setTimeout(resolve, 50));
      if (alive.current) setWorking(false);
    }
    if (
      alive.current &&
      live.current.ws.isConnected &&
      model.state.snapshotFresh &&
      !model.state.busy &&
      !model.state.needsReload &&
      (!pending ||
        (feedRevision.current > beforeFeed &&
          transactions.clear(endpoint, pending.token)))
    ) {
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
    reviewBody: transactions.read(endpoint)?.body,
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
