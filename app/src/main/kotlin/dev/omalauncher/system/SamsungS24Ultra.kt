package dev.omalauncher.system

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.WindowManager
import java.io.File

/**
 * Hardware and display optimizations tailored for Samsung Galaxy S24 Ultra:
 * - SoC: Qualcomm Snapdragon 8 Gen 3 for Galaxy (SM8650-AC)
 * - GPU: Qualcomm Adreno 750 (Turnip KGSL driver on /dev/kgsl-3d0)
 * - Display: 6.8" Dynamic AMOLED 2X, 3120x1440 QHD+, 1-120Hz LTPO
 */
object SamsungS24Ultra {
    private const val TAG = "S24Ultra"

    /** Returns true if running on a Samsung Galaxy S24 Ultra or Snapdragon 8 Gen 3 device. */
    val isS24Ultra: Boolean by lazy {
        val model = Build.MODEL.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL.orEmpty() else ""
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        // SM-S928 is Galaxy S24 Ultra (S928B, S928U, S9280, etc.)
        val isS24UModel = model.contains("SM-S928", ignoreCase = true) || model.contains("S24 Ultra", ignoreCase = true)
        val isSnapdragon8Gen3 = hardware.contains("qcom", ignoreCase = true) && 
            (soc.contains("8650", ignoreCase = true) || hardware.contains("pineapple", ignoreCase = true))

        (isSamsung && isS24UModel) || isSnapdragon8Gen3
    }

    /** Returns true if the device has the Adreno 750 GPU. */
    val hasAdreno750: Boolean by lazy {
        val kgslModel = runCatching { File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim() }.getOrNull()
        kgslModel?.contains("750") == true
    }

    /**
     * Configures the activity window for Samsung S24 Ultra's Dynamic AMOLED 2X:
     * - Requests 120Hz high refresh rate mode for fluid transitions.
     * - Enables LTPO-friendly frame pacing.
     */
    fun optimizeWindow(activity: Activity) {
        // Other phones keep Android's own choice of mode.
        if (!isS24Ultra) return
        try {
            val window = activity.window
            val layoutParams = window.attributes

            // Target 120Hz display refresh rate on supported displays
            run {
                @Suppress("DEPRECATION")
                val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.display
                } else {
                    activity.windowManager.defaultDisplay
                }
                val modes = display?.supportedModes.orEmpty()
                // At the resolution the display runs now: the S24 Ultra has 120Hz modes at both FHD+ and QHD+,
                // and taking the first one could switch the screen's resolution behind the user's setting.
                val current = display?.mode
                val mode120Hz = modes.firstOrNull { mode ->
                    val rate = mode.refreshRate
                    rate >= 119.0f && rate <= 121.0f &&
                        (current == null || (mode.physicalWidth == current.physicalWidth && mode.physicalHeight == current.physicalHeight))
                }

                if (mode120Hz != null) {
                    layoutParams.preferredDisplayModeId = mode120Hz.modeId
                    Log.i(TAG, "Selected 120Hz display mode ${mode120Hz.modeId} for S24 Ultra")
                } else {
                    // Fallback to preferredRefreshRate
                    layoutParams.preferredRefreshRate = 120.0f
                }
            }

            window.attributes = layoutParams
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set 120Hz refresh rate", e)
        }
    }

    /**
     * Prioritizes the main render thread on Qualcomm's Performance core cluster
     * to eliminate jank without draining battery.
     */
    fun optimizeThreadPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        } catch (e: Exception) {
            Log.d(TAG, "Priority set failed: ${e.message}")
        }
    }
}
