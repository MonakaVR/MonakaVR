package dev.monaka.tracking

import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.test.assertIs

/** Explicit synthetic physical relation only; provides no production acquisition or HMD trust. */
internal fun syntheticHeadAnchorCalibration(hmdSourceId: String, bodyModelId: String,
	calibrationId: String = "synthetic-head-anchor", sessionEpoch: String = "synthetic-fit:1",
	offset: Vector3 = Vector3(0f, 0f, 0f), orientation: Quaternion = Quaternion.IDENTITY) =
	assertIs<PredictorFixedCalibrationSnapshotResult.Available>(PredictorFixedCalibrationSnapshot.create(
		calibrationId, sessionEpoch, hmdSourceId, bodyModelId, offset, orientation)).snapshot
