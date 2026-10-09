"""TEST ONLY: fixture oracle, never imported by a protocol/runtime library.

Authorization, association and freshness clocks are intentionally not implemented.
"""
import copy

class Reference:
    def __init__(self):
        self.publisher=None; self.session=None; self.retired=set(); self.last=-1
        self.content=None; self.closed=False; self.authority=None; self.domain=None
        self.world=None; self.mapping=None; self.retired_worlds=set()
        self.retired_calibrations=set(); self.mapping_high=None; self.pose=False
        self.freshness_updates=0; self.observations=set()
        self.retired_sources=set(); self.retired_source_sessions=set(); self.source_generations={}
        # Snapshot history survives publisher reconnects and repeated world publications.
        self.mapping_history={}

    def accept(self, event):
        j=copy.deepcopy(event); sent=j.pop('sent_at_ns'); j.pop('future_note',None)
        pub=j['publisher_id']; sid=j['session_id']; seq=int(j['sequence']); t=j['type']
        if self.publisher is not None and pub!=self.publisher: return 'unauthorized'
        if sid in self.retired: return 'retired'
        if sid!=self.session:
            if self.session: self.retired.add(self.session)
            self.session=sid; self.publisher=pub; self.last=-1; self.closed=False
            self.authority=None; self.domain=None; self.world=None; self.mapping=None; self.pose=False
            self.mapping_high=None
        if self.closed: return 'closed'
        if seq<self.last: return 'old'
        if seq==self.last:
            if j==self.content: return 'duplicate'
            self.closed=True; self.pose=False; self.authority=None; self.world=None; self.mapping=None
            return 'conflict'
        if seq!=self.last+1:
            self.closed=True; self.pose=False; self.authority=None; self.world=None; self.mapping=None
            return 'gap'
        if self.last==-1 and t not in ('source_authority','world_authority'):
            self.closed=True; return 'bootstrap-failed'
        self.last=seq; self.content=j
        if t=='source_authority':
            s=j['source_space']; key=(s['source_id'],s['source_authority_session_epoch'],s['source_space_id'])
            generation=int(s['source_space_generation'])
            if key[:2] in self.retired_source_sessions or (*key,generation) in self.retired_sources or generation<self.source_generations.get(key,-1): return 'untrusted'
            self.source_generations[key]=int(s['source_space_generation'])
            self.authority=j['source_space']; self.domain=j['source_time_domain_id']; self.pose=False
            return 'authority'
        if t=='world_authority':
            if j['world']['world_epoch'] in self.retired_worlds: return 'untrusted'
            if self.world is not None and self.world!=j['world']: return 'untrusted'
            same_world=self.world==j['world']
            self.world=j['world']; self.mapping=None; self.pose=False
            self.anchor=j['anchor_source']
            if not same_world: self.mapping_high=None
            return 'world'
        if t=='mapping_publication':
            m=j['mapping']
            if m['world']!=self.world or m['source_space']!=self.anchor or m['calibration_epoch'] in self.retired_calibrations: return 'untrusted'
            world_key=(m['world']['owner_id'],m['world']['world_epoch'])
            snapshot=(m,j['transform'])
            previous=self.mapping_history.get(world_key)
            if previous is not None:
                revision, frozen=previous
                if m['mapping_revision']<revision or (m['mapping_revision']==revision and snapshot!=frozen): return 'untrusted'
                # An exact current snapshot can bootstrap a fresh session. Within
                # one active session, revisions must still advance.
                if self.mapping_high is not None and m['mapping_revision']<=self.mapping_high: return 'untrusted'
            self.mapping_history[world_key]=(m['mapping_revision'],copy.deepcopy(snapshot))
            self.mapping_high=m['mapping_revision']; self.mapping=m; self.pose=False
            return 'mapping'
        if t in ('source_pose','common_pose'):
            source=j['source']; s=source['source_space']
            match=(s==self.authority and source['source_time_domain_id']==self.domain) if t=='source_pose' else j['mapping']==self.mapping and j['mapping']['world']==self.world
            if not match or not all(j['validity'][k] for k in ['position_valid','orientation_valid','view_position_valid','view_orientation_valid']): return 'untrusted'
            key=(s['source_id'],s['source_authority_session_epoch'],source['observation_id'])
            if key not in self.observations: self.freshness_updates+=1; self.observations.add(key)
            self.pose=True; return 'pose'
        if t=='source_unavailable':
            if j['source_space']!=self.authority: return 'untrusted'
            self.pose=False; return 'unavailable'
        if t=='common_unavailable':
            if j['mapping']!=self.mapping: return 'untrusted'
            self.pose=False; return 'unavailable'
        if t=='source_revocation':
            if j['source_space']!=self.authority: return 'untrusted'
            s=self.authority; self.retired_sources.add((s['source_id'],s['source_authority_session_epoch'],s['source_space_id'],int(s['source_space_generation'])))
            if j['reason'] in ('source_session_loss','event_stream_lost'):
                self.retired_source_sessions.add((s['source_id'],s['source_authority_session_epoch']))
            self.authority=None; self.pose=False; return 'revoked'
        if t=='mapping_revocation':
            if j['mapping']!=self.mapping: return 'untrusted'
            self.retired_calibrations.add(self.mapping['calibration_epoch'])
            self.mapping=None; self.pose=False; return 'revoked'
        if t=='world_revocation':
            if j['world']!=self.world: return 'untrusted'
            self.retired_worlds.add(self.world['world_epoch'])
            if self.mapping: self.retired_calibrations.add(self.mapping['calibration_epoch'])
            self.world=None; self.mapping=None; self.pose=False; return 'revoked'
        raise RuntimeError('unknown fixture event')
