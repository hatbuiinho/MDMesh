package com.mdmesh.agent.apps

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** The only exported entry point. Forward a validated package, never external extras or admin flags. */
class PlayShareActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val pkg = if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain")
            PlayAppLink.fromText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()) else null
        if (pkg == null) Toast.makeText(this, "Hãy chia sẻ link của một ứng dụng trong Google Play.", Toast.LENGTH_LONG).show()
        else startActivity(Intent(this, AppRequestsActivity::class.java).putExtra("sharedPackage", pkg)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
