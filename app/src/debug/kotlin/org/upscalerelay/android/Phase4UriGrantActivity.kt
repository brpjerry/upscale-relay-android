package org.upscalerelay.android

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/** Debug-only bridge that regrants an app-owned SAF URI to the device test APK. */
class Phase4UriGrantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val document = requireNotNull(intent.data) { "a document URI is required" }
        startActivity(Intent().apply {
            component = ComponentName(
                "org.upscalerelay.android.test",
                "org.upscalerelay.android.UriGrantActivity",
            )
            data = document
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        finish()
    }
}
