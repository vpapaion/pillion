package app.pillion.android

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline that shows Android's "Start screen sharing?" dialog after the system ended
 * the previous share on lock, then hands the new grant to the running [CaptureService]. Lives in
 * its own task, so finishing returns the rider straight to the app they were using.
 */
class ProjectionResumeActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureService.resultCode = result.resultCode
            CaptureService.resultData = data
            runCatching {
                startService(
                    Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_RESUME_PROJECTION),
                )
            }.onFailure { DiagnosticLog.e("Pillion", "re-grant: could not reach CaptureService", it) }
        } else {
            DiagnosticLog.d("Pillion", "re-grant: rider declined screen sharing")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticLog.attach(applicationContext)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            if (mpm == null) {
                finish()
                return
            }
            consent.launch(mpm.createScreenCaptureIntent())
        }
    }
}
