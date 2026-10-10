package se.euther.eutherbeam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import se.euther.eutherbeam.androidtv.AndroidTvKey
import se.euther.eutherbeam.androidtv.AndroidTvDevice

internal enum class LinkedDisplay { SAMSUNG, NEC }

@Composable
internal fun AndroidTvPanel(
    devices: List<AndroidTvDevice>,
    selectedDeviceId: String?,
    onSelectDevice: (AndroidTvDevice) -> Unit,
    savedAddress: String,
    deviceName: String,
    addressInput: String,
    onAddressChange: (String) -> Unit,
    paired: Boolean,
    linkedDisplay: LinkedDisplay,
    onLinkedDisplayChange: (LinkedDisplay) -> Unit,
    working: Boolean,
    status: String?,
    onDiscover: () -> Unit,
    onPair: () -> Unit,
    onForget: () -> Unit,
    onKey: (AndroidTvKey) -> Unit,
    canCastWake: Boolean,
    onWakePlayer: () -> Unit,
    onRoomPower: (Boolean) -> Unit,
) {
    BeamCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ANDROID TV // RUM", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(
                    when {
                        savedAddress.isBlank() -> "Ingen puck sparad"
                        deviceName.isNotBlank() -> deviceName
                        else -> "Android TV"
                    },
                    color = BeamText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text(
                    if (savedAddress.isBlank()) "Remote Service v2 • TLS" else "$savedAddress  •  ${if (paired) "PARAD" else "EJ PARAD"}",
                    color = if (paired) BeamMint else BeamMuted,
                    fontSize = 13.sp,
                )
            }
            Box(
                Modifier.size(42.dp).background(BeamRaised, CircleShape)
                    .border(BorderStroke(1.dp, if (paired) BeamMint else BeamOrange), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("A", color = if (paired) BeamMint else BeamOrange, fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
    }

    if (devices.isNotEmpty()) {
        BeamCard {
            Text("SPARADE PUCKAR", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                devices.forEach { device ->
                    val selected = device.id == selectedDeviceId
                    Button(
                        onClick = { onSelectDevice(device) },
                        enabled = !working,
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(1.dp, if (selected) BeamYellow else BeamMint.copy(alpha = 0.35f)),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) BeamOrange else BeamRaised,
                            contentColor = if (selected) BeamDark else BeamText,
                        ),
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(device.name, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            Text(device.address, fontSize = 10.sp)
                            Text(
                                listOfNotNull(
                                    "SPARAD",
                                    "PARAD".takeIf { device.paired },
                                    "CAST".takeIf { device.supportsCast },
                                    device.linkedDisplay.uppercase(),
                                ).joinToString(" • "),
                                fontSize = 9.sp,
                            )
                        }
                    }
                }
            }
            Text(
                "Valet och TV-kopplingen ligger kvar även när pucken är avstängd eller appen startas om.",
                color = BeamMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }

    BeamCard {
        Text("VARDAGSRUMMETS BILDSKÄRM", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkedDisplay.entries.forEach { display ->
                val selected = linkedDisplay == display
                Button(
                    onClick = { onLinkedDisplayChange(display) },
                    enabled = !working,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selected) BeamOrange else BeamRaised,
                        contentColor = if (selected) BeamDark else BeamText,
                    ),
                ) { Text(if (display == LinkedDisplay.SAMSUNG) "Samsung TV" else "NEC Display", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            }
        }
        Text(
            "EutherBeam skickar navigering till pucken och rummets strömkommando till den valda skärmen.",
            color = BeamMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    BeamCard {
        Text("ANSLUTNING", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = addressInput,
            onValueChange = { onAddressChange(it.filter { char -> char.isDigit() || char == '.' }.take(15)) },
            label = { Text("Android TV-puckens IPv4-adress") },
            placeholder = { Text("192.168.32.x") },
            enabled = !working,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = androidTvFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AndroidTvButton("Hitta puck", working, Modifier.weight(1f), onDiscover)
            AndroidTvButton(if (paired) "Parad" else "Para med PIN", working || paired, Modifier.weight(1f), onPair, primary = true)
        }
        if (paired) {
            Spacer(Modifier.height(8.dp))
            AndroidTvButton("Glöm parning", working, Modifier.fillMaxWidth(), onForget, danger = true)
        }
    }

    if (paired) {
        BeamCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("NAVIGATION", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text("ANDROID TV", color = BeamMuted, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.size(252.dp).align(Alignment.CenterHorizontally)
                    .background(BeamRaised, CircleShape)
                    .border(BorderStroke(2.dp, BeamMint.copy(alpha = 0.72f)), CircleShape),
            ) {
                AndroidDpadButton("↑", AndroidTvKey.DPAD_UP, working, Modifier.align(Alignment.TopCenter).offset(y = 8.dp), onKey)
                AndroidDpadButton("↓", AndroidTvKey.DPAD_DOWN, working, Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp), onKey)
                AndroidDpadButton("←", AndroidTvKey.DPAD_LEFT, working, Modifier.align(Alignment.CenterStart).offset(x = 8.dp), onKey)
                AndroidDpadButton("→", AndroidTvKey.DPAD_RIGHT, working, Modifier.align(Alignment.CenterEnd).offset(x = (-8).dp), onKey)
                Button(
                    onClick = { onKey(AndroidTvKey.DPAD_CENTER) },
                    enabled = !working,
                    modifier = Modifier.align(Alignment.Center).size(86.dp),
                    shape = CircleShape,
                    border = BorderStroke(2.dp, BeamYellow),
                    colors = ButtonDefaults.buttonColors(containerColor = BeamMint, contentColor = BeamDark),
                ) { Text("OK", fontWeight = FontWeight.Black, fontSize = 18.sp) }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidTvKeyButton("Tillbaka", AndroidTvKey.BACK, working, Modifier.weight(1f), onKey)
                AndroidTvKeyButton("Hem", AndroidTvKey.HOME, working, Modifier.weight(1f), onKey)
                AndroidTvKeyButton("Power", AndroidTvKey.POWER, working, Modifier.weight(1f), onKey)
            }
        }

        BeamCard {
            Text("MEDIA & LJUD", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidTvKeyButton("−", AndroidTvKey.VOLUME_DOWN, working, Modifier.weight(1f), onKey)
                AndroidTvKeyButton("Mute", AndroidTvKey.VOLUME_MUTE, working, Modifier.weight(1f), onKey)
                AndroidTvKeyButton("+", AndroidTvKey.VOLUME_UP, working, Modifier.weight(1f), onKey)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidTvKeyButton("⏪", AndroidTvKey.MEDIA_REWIND, working, Modifier.weight(1f), onKey)
                AndroidTvKeyButton("▶Ⅱ", AndroidTvKey.MEDIA_PLAY_PAUSE, working, Modifier.weight(1f), onKey)
                AndroidTvKeyButton("⏩", AndroidTvKey.MEDIA_FAST_FORWARD, working, Modifier.weight(1f), onKey)
            }
        }

        BeamCard {
            Text("RUMSSCENER", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            if (canCastWake) {
                AndroidTvButton("VÄCK PUCK VIA CAST", working, Modifier.fillMaxWidth(), onWakePlayer, primary = true)
                Text(
                    "Fungerar även när Android Remote är nedkopplad i standby.",
                    color = BeamMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 7.dp, bottom = 10.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AndroidTvButton("STARTA RUM", working, Modifier.weight(1f), { onRoomPower(true) }, primary = true)
                AndroidTvButton("STÄNG ALLT", working, Modifier.weight(1f), { onRoomPower(false) }, danger = true)
            }
        }
    }

    status?.let {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (working) CircularProgressIndicator(color = BeamOrange, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(it, color = BeamMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AndroidDpadButton(label: String, key: AndroidTvKey, working: Boolean, modifier: Modifier, onKey: (AndroidTvKey) -> Unit) {
    Button(
        onClick = { onKey(key) }, enabled = !working, modifier = modifier.size(width = 70.dp, height = 66.dp),
        shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, BeamOrange.copy(alpha = 0.7f)),
        colors = ButtonDefaults.buttonColors(containerColor = BeamPanel, contentColor = BeamText),
    ) { Text(label, fontSize = 27.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun AndroidTvKeyButton(label: String, key: AndroidTvKey, working: Boolean, modifier: Modifier, onKey: (AndroidTvKey) -> Unit) =
    AndroidTvButton(label, working, modifier, { onKey(key) })

@Composable
private fun AndroidTvButton(
    label: String,
    working: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    primary: Boolean = false,
    danger: Boolean = false,
) {
    val container = when {
        danger -> androidx.compose.ui.graphics.Color(0xFF9D4934)
        primary -> BeamOrange
        else -> BeamRaised
    }
    Button(
        onClick = onClick,
        enabled = !working,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (danger) BeamOrange else BeamMint.copy(alpha = 0.45f)),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = if (primary) BeamDark else BeamText),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun androidTvFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BeamText,
    unfocusedTextColor = BeamText,
    focusedBorderColor = BeamOrange,
    unfocusedBorderColor = BeamMint.copy(alpha = 0.5f),
    focusedLabelColor = BeamOrange,
    unfocusedLabelColor = BeamMuted,
    cursorColor = BeamOrange,
    focusedContainerColor = BeamDark.copy(alpha = 0.35f),
    unfocusedContainerColor = BeamDark.copy(alpha = 0.35f),
)
