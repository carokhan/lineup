package com.lineup.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.lineup.app.BuildConfig
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize

/**
 * The whole app: receive a shared image, show the draft, hand it to the calendar.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            AppTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ConfirmScreen(
                        viewModel = viewModel,
                        onClose = { finish() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val debugFile = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_OCR_FILE) else null
        if (debugFile != null) {
            viewModel.debugOcrFile(debugFile)
            return
        }
        viewModel.onImageReceived(intent?.let { extractImageUri(it) })
    }

    /**
     * Share intents are the wild west: missing extras, wrong types, empty clip data.
     * Anything unexpected simply means "no image", never a crash.
     */
    private fun extractImageUri(intent: Intent): Uri? = try {
        when (intent.action) {
            Intent.ACTION_SEND -> intent.streamExtra() ?: intent.firstClipUri()
            Intent.ACTION_SEND_MULTIPLE -> intent.streamExtras()?.firstOrNull() ?: intent.firstClipUri()
            Intent.ACTION_VIEW -> intent.data ?: intent.firstClipUri()
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtra(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    @Suppress("DEPRECATION")
    private fun Intent.streamExtras(): List<Uri>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        }

    private fun Intent.firstClipUri(): Uri? =
        clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

    private companion object {
        const val EXTRA_OCR_FILE = "ocr_file"
    }
}
