package dev.monaka.tracking

/** Read-only HIP state captured after the existing solver and output decisions. Ages are payload only. */
data class HybridTrackingDiagnosticSnapshot(
	val main: MainSampleState,
	val mainSource: String?,
	val mainSequence: Long?,
	val mainAgeNanos: Long?,
	val imuSource: String?,
	val imuProvenanceAvailable: Boolean,
	val imuSequence: Long?,
	val imuAgeNanos: Long?,
	val imuFresh: Boolean,
	val imuRotationUsable: Boolean,
	val imuEpochCompatible: Boolean?,
	val resolverPositionOwner: String?,
	val resolverRotationOwner: String?,
	val correctionEnabled: Boolean,
	val correctionState: RotationCorrectionState?,
	val correctionReady: Boolean,
	val correctionAgeNanos: Long?,
	val correctionResidualRadians: Double?,
	val learningDecision: LearningDecision?,
	val learningReason: String?,
	val applicationDecision: ApplicationDecision?,
	val applicationReason: String?,
	val continuityState: ContinuityState?,
	val backgroundAvailable: Boolean?,
	val visiblePositionSource: OutputPositionSource?,
	val visibleRotationOwner: String?,
	val transitionReason: String?,
) {
	/** Sequence, age, residual and pose values intentionally do not participate in deduplication. */
	internal fun semanticKey() = listOf(
		main.modality, main.positionValid, main.rotationValid, mainSource,
		imuSource, imuProvenanceAvailable, imuFresh, imuRotationUsable, imuEpochCompatible,
		resolverPositionOwner, resolverRotationOwner, correctionEnabled, correctionState, correctionReady,
		learningDecision, learningReason, applicationDecision, applicationReason,
		continuityState, backgroundAvailable, visiblePositionSource, visibleRotationOwner, transitionReason,
	)
}

data class HybridTrackingDiagnosticEvent(val snapshot: HybridTrackingDiagnosticSnapshot) {
	fun compactLine(): String {
		val s = snapshot
		fun age(nanos: Long?) = nanos?.let { it / 1_000_000 }?.toString() ?: "?"
		return "[MonakaHIL] HIP main=${s.main.modality} mainValid=${s.main.positionValid}/${s.main.rotationValid}" +
			" mainSrc=${s.mainSource ?: "-"} mainSeq=${s.mainSequence ?: "-"} mainAgeMs=${age(s.mainAgeNanos)}" +
			" imuSrc=${s.imuSource ?: "-"} imuSeq=${s.imuSequence ?: "-"} imuAgeMs=${age(s.imuAgeNanos)}" +
			" imuFresh=${s.imuFresh} imuValid=${s.imuRotationUsable} imuEpoch=${s.imuEpochCompatible ?: "-"}" +
			" resolverPos=${s.resolverPositionOwner ?: "-"} resolverRot=${s.resolverRotationOwner ?: "-"}" +
			" corr=${s.correctionState ?: "OFF"} ready=${s.correctionReady} corrAgeMs=${age(s.correctionAgeNanos)}" +
			" residual=${s.correctionResidualRadians ?: "-"}" +
			" learn=${s.learningDecision ?: "OFF"}:${s.learningReason ?: "-"}" +
			" apply=${s.applicationDecision ?: "OFF"}:${s.applicationReason ?: "-"}" +
			" continuity=${s.continuityState ?: "-"} bg=${s.backgroundAvailable ?: "-"}" +
			" visiblePos=${s.visiblePositionSource ?: "-"} visibleRot=${s.visibleRotationOwner ?: "-"}" +
			" reason=${s.transitionReason ?: "-"}"
	}
}

/** Emits only on semantic change; snapshot capture itself has no logging side effect. */
class HybridTrackingDiagnosticRecorder(private val onEvent: (HybridTrackingDiagnosticEvent) -> Unit) {
	private var previousKey: List<Any?>? = null
	var latest: HybridTrackingDiagnosticSnapshot? = null
		private set
	fun record(snapshot: HybridTrackingDiagnosticSnapshot) {
		latest = snapshot
		val key = snapshot.semanticKey()
		if (key != previousKey) {
			previousKey = key
			// Diagnostic sinks are best-effort; a logging failure must never stop tracking.
			try { onEvent(HybridTrackingDiagnosticEvent(snapshot)) } catch (_: Exception) { }
		}
	}
}
