package com.cronos.camera.enhance

import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalCamera2Interop

/**
 * Prefer OIS/EIS and high-quality color path while recording.
 * Full per-frame DaylightEnhancer is photo-only (too heavy for realtime video).
 */
@OptIn(ExperimentalCamera2Interop::class)
object VideoCaptureTuning {

    fun applyStabilizationAndColor(camera: Camera?) {
        if (camera == null) return
        runCatching {
            val c2 = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()
            // Electronic video stabilization
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            // Optical if available
            builder.setCaptureRequestOption(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            )
            // Prefer high-quality color correction pipeline
            builder.setCaptureRequestOption(
                CaptureRequest.COLOR_CORRECTION_MODE,
                CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
            )
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            builder.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            builder.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            c2.captureRequestOptions = builder.build()
        }
    }
}
