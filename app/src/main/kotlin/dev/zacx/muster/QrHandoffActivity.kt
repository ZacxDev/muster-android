package dev.zacx.muster

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.zacx.muster.tenants.QrHandoff

/**
 * Receives muster's QR sign-in hand-off from the browser (the page's
 * `intent://…/auth/qr` link, QrHandoff) and passes it on to [MainActivity],
 * then finishes without drawing anything.
 *
 * Why a trampoline: Chrome starts the intent with FLAG_ACTIVITY_NEW_TASK and
 * no SINGLE_TOP, which on the standard-launch-mode MainActivity would stack
 * a SECOND shell (a second WebView) on the running one. This activity runs in
 * a task of its own (empty taskAffinity, excluded from Recents) and starts
 * MainActivity the way a notification tap does (NEW_TASK | SINGLE_TOP), so a
 * running shell gets the hand-off in onNewIntent and a cold start gets it in
 * onCreate. Only the data and the code are passed on, never other extras.
 * MainActivity re-checks everything (QrHandoff.match): this activity decides
 * nothing.
 */
class QrHandoffActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(intent?.data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { intent?.getStringExtra(QrHandoff.EXTRA_CODE) }.getOrNull()?.let { forward.putExtra(QrHandoff.EXTRA_CODE, it) }
        startActivity(forward)
        finish()
    }
}
