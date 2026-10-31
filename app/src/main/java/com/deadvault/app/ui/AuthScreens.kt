package com.deadvault.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.deadvault.app.BuildConfig
import java.text.DateFormat
import java.util.Date

/** The check-in intervals offered to the user. The 2-minute one exists only in debug builds. */
val IntervalOptions: List<Pair<String, Long>> = buildList {
    if (BuildConfig.DEBUG) add("2 min (debug)" to 2L * 60 * 1000)
    add("1 day" to 1L * DAY)
    add("3 days" to 3L * DAY)
    add("7 days" to 7L * DAY)
    add("14 days" to 14L * DAY)
    add("30 days" to 30L * DAY)
}

private const val DAY = 24L * 60 * 60 * 1000

fun formatRemaining(ms: Long): String {
    if (ms <= 0) return "now"
    val s = ms / 1000
    val d = s / 86_400
    val h = (s % 86_400) / 3_600
    val m = (s % 3_600) / 60
    val sec = s % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
    }
}

@Composable
fun SetupScreen(vm: VaultViewModel) {
    var passcode by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf(IntervalOptions.first { it.first == "7 days" }.second) }
    var understood by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Icon(Icons.Default.Lock, null, Modifier.size(40.dp), tint = Mint)
        Text("Create your vault", style = MaterialTheme.typography.headlineMedium)

        vm.wipeNotice?.let { notice ->
            Text(
                "Your previous vault was erased. $notice",
                color = Amber,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Text(
            "Photos and videos you add are encrypted and removed from the normal gallery. " +
                "You must enter your passcode at least once per check-in period. If you don't, " +
                "everything in the vault is erased permanently — there is no recovery.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PasscodeField("Passcode (6+ characters)", passcode, { passcode = it })
        PasscodeField("Confirm passcode", confirm, { confirm = it }, ImeAction.Done)

        Text("Check in every", style = MaterialTheme.typography.titleSmall)
        IntervalChips(selected = interval, onSelect = { interval = it })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = understood, onCheckedChange = { understood = it })
            Text(
                "I understand that missing a check-in, or forgetting my passcode, permanently erases my vault.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Button(
            onClick = { vm.setup(passcode, confirm, interval) },
            enabled = understood && !vm.busy && passcode.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Create vault")
        }
    }
}

@Composable
fun IntervalChips(selected: Long, onSelect: (Long) -> Unit) {
    // Two rows keep all options visible on narrow phones.
    val rows = IntervalOptions.chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, ms) ->
                    FilterChip(selected = selected == ms, onClick = { onSelect(ms) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
fun PasscodeField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun LockScreen(vm: VaultViewModel) {
    var passcode by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(56.dp), tint = Mint)
        Spacer(Modifier.height(12.dp))
        Text("DeadVault", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))

        val soon = vm.remainingMs < 24L * 60 * 60 * 1000
        Text(
            "Vault erases in ${formatRemaining(vm.remainingMs)} unless you unlock it",
            color = if (soon) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        PasscodeField("Passcode", passcode, { passcode = it }, ImeAction.Done) {
            if (passcode.isNotEmpty()) vm.unlock(passcode)
        }
        vm.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.unlock(passcode); passcode = "" },
            enabled = passcode.isNotEmpty() && !vm.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Unlock")
        }
        Spacer(Modifier.width(1.dp))
    }
}

fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
