# Wire 2.1 to 5Z projection audit (read-only)

Audited MonakaVR commit 62d096ad98137aee7aaeb115c2b142a4e6448768:
server/core/src/main/java/dev/monaka/tracking/CommonWorldAuthority.kt,
ObservationSampleProvenance.kt and PositionPredictionContract.kt.
Audited ALVR 5X commit e94ff1967c70da480531c3ec9f6956d3be8b5ce5:
alvr/common/src/hmd_authority.rs. Bridge remains 84f5b7529694d19aee98461580f3cfe628bf3cbc.

| Wire fact | 5Z destination / future rule |
|---|---|
| source_space.source_id | SourceSpaceAuthorityEpoch.sourceId |
| source_authority_session_epoch | SourceSpaceAuthorityEpoch.sourceSessionEpoch; opaque token |
| source_space_id | SourceSpaceAuthorityEpoch.sourceSpaceId |
| source_space_generation | SourceSpaceAuthorityEpoch.generation; combined STAGE/VIEW |
| source.observation_id | TrustedHmdSourceObservationIdentity.observationId |
| world.world_epoch | CommonWorldEpoch.value / CommonWorldAuthority.epoch |
| world.owner_id | CommonWorldAuthority.ownerId; authorized expected owner required |
| world.coordinate_space | CommonWorldAuthority.space; revision is worldRevision |
| anchor_source | CommonWorldAuthority.anchor |
| mapping.calibration_epoch | CommonMappingCalibrationEpoch.value |
| mapping.mapping_revision | CommonMappingRevision.value |
| mapping.source_space/world | SourceToCommonMappingSnapshot.sourceSpace/commonWorld |
| transform.rotation_xyzw/translation_xyz | SourceToCommonRigidTransform; Quaternion(w,x,y,z) constructor |
| source_position/source_orientation | TrustedHmdSourcePoseSnapshot exact observation |
| common_position/common_orientation | CommonWorldMappedHmdPose mapped P/Q |
| source_locate_time_ns/source_time_domain_id | No 5Z time-domain field; retain dormant ingress evidence separately, no unrelated clock subtraction |
| six validity facts | No equivalent 5Z snapshot field; retain exact ingress evidence and independently prove usability |
| publisher session + stream sequence | No source/world/mapping epoch projection; future ingress ordering/replay receipt only |
| world revocation | CommonWorldAuthorityState.revokeWorld; its local diagnostic sequence differs from wire sequence |
| mapping revocation | CommonWorldAuthorityState.revokeMapping; no pose needed |

ObservationSampleProvenance.sequence must represent source observation ID when projecting sample provenance, never substitute publication stream sequence. sourceEpoch/calibrationEpoch/mappingRevision/space/commonWorldEpoch remain separate. sampleAtNanos requires explicit safe receiver-clock admission; source locate time cannot be copied into it. A mapped copy of an existing observation cannot refresh sample time.

5Z openXrAnchor requires the source to equal the world anchor and an identity transform. Generic transforms may be represented on wire but cannot bypass that M2 gate. The 5Z numerical validator uses float norm-squared tolerance 1e-5; wire uses double norm tolerance 1e-5. Future projection must satisfy both without normalization/repair. Source facts and mapping output must be validated against one frozen current snapshot before any admission.

No code in MonakaVR, Bridge or ALVR was changed. This table does not instantiate live authority handles, RawHmdPoseInput, Reviewed registration or Strong Trusted. Transport association, authorized publisher, tombstones, high-waters, freshness and exact state matching remain future consumer requirements.
