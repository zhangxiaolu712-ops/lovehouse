package fyi.b612.lovehouse

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import fyi.b612.lovehouse.app.LoveHouseApp
import fyi.b612.lovehouse.feature.events.NotificationIntentHandoff

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        acceptNotificationIntent(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LoveHouseApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!acceptNotificationIntent(intent)) {
            setIntent(intent)
        }
    }

    private fun acceptNotificationIntent(candidate: Intent?): Boolean {
        val accepted = NotificationIntentHandoff.offer(candidate?.action, candidate?.dataString)
        if (accepted) {
            // Prevent Navigation from consuming the same deep link again during Activity recreation.
            setIntent(Intent(this, MainActivity::class.java).apply { action = Intent.ACTION_MAIN })
        }
        return accepted
    }
}
