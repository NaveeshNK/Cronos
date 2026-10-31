package com.cronos.camera.enhance

import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera

/**
 * Video path: EIS/OIS + ISP HQ + mild vivid color gains.
 * Call after bind and again when recording starts (MIUI often resets).
 */
object VideoCaptureTuning {

    private const val TAG = "CronosVideo"

    @OptIn(ExperimentalCamera2Interop::class)
    fun applyStabilizationAndColor(camera: Camera?) {
        if (camera == null) return
        runCatching {
            val c2 = Camera2CameraControl.from(camera.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // Digital video stabilization (EIS)
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            )
            // Optical if available
            runCatching {
                builder.setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                )
            }

            // Mild vivid: boost red/blue channels slightly (photo-like color on video)
            runCatching {
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX
                )
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_GAINS,
                    RggbChannelVector(1.12f, 1.0f, 1.0f, 1.08f)
                )
            }
            // Fallback HQ color if transform rejected
            runCatching {
                builder.setCaptureRequestOption(
                    CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY
                )
            }

            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO
            )
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            builder.setCaptureRequestOption(
                CaptureRequest.NOISE_REDUCTION_MODE,
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            )
            builder.setCaptureRequestOption(
                CaptureRequest.EDGE_MODE,
                CaptureRequest.EDGE_MODE_HIGH_QUALITY
            )
            builder.setCaptureRequestOption(
                CaptureRequest.HOT_PIXEL_MODE,
                CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
            )
            builder.setCaptureRequestOption(
                CaptureRequest.TONEMAP_MODE,
                CaptureRequest.TONEMAP_MODE_HIGH_QUALITY
            )
            // Prefer continuous video AF
            builder.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )

            c2.setCaptureRequestOptions(builder.build())
            Log.d(TAG, "stabilization + color request options applied")
        }.onFailure {
            Log.w(TAG, "VideoCaptureTuning failed: ${it.message}")
        }
    }
}
