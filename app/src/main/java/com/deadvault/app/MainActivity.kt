package com.deadvault.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import com.deadvault.app.ui.DeadVaultRoot
import com.deadvault.app.ui.DeadVaultTheme
import com.deadvault.app.ui.VaultViewModel

// FragmentActivity (a ComponentActivity subclass) because BiometricPrompt requires it.
class MainActivity : FragmentActivity() {

    private val vm: VaultViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, and a blank card in the recent-apps switcher.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        setContent {
            DeadVaultTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DeadVaultRoot(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onResumed()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app always locks the vault (except while one of our own pickers is open).
        vm.onStopped()
    }
}
