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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun NecRemotePanel(
    savedAddress: String,
    addressInput: String,
    onAddressChange: (String) -> Unit,
    status: String?,
    working: Boolean,
    onSaveAddress: () -> Unit,
    onDiscover: () -> Unit,
    onPower: (Boolean) -> Unit,
    onInput: (String) -> Unit,
) {
    var customCode by remember { mutableStateOf("") }

    BeamCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("NEC DISPLAY", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
                Text(
                    if (savedAddress.isBlank()) "Ingen skärm sparad" else savedAddress,
                    color = BeamText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                )
                Text("External Control • TCP 7142", color = BeamMuted, fontSize = 13.sp)
            }
            Box(
                Modifier
                    .size(42.dp)
                    .background(BeamRaised, CircleShape)
                    .border(BorderStroke(1.dp, BeamMint), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("N", color = BeamMint, fontWeight = FontWeight.Black, fontSize = 18.sp) }
        }
    }

    BeamCard {
        Text("ANSLUTNING", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = addressInput,
            onValueChange = { onAddressChange(it.filter { char -> char.isDigit() || char == '.' }.take(15)) },
            label = { Text("NEC-skärmens IPv4-adress") },
            placeholder = { Text("192.168.32.x") },
            enabled = !working,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = necFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NecButton("Spara IP", working, Modifier.weight(1f), onSaveAddress)
            NecButton("Hitta NEC", working, Modifier.weight(1f), onDiscover, primary = true)
        }
        Text(
            "Sökningen verifierar NEC-protokollet och provar högst 1024 adresser på ditt aktiva lokala nät.",
            color = BeamMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    BeamCard {
        Text("STRÖM", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NecButton("PÅ", working, Modifier.weight(1f), { onPower(true) }, primary = true)
            NecButton("STÄNG AV", working, Modifier.weight(1f), { onPower(false) }, danger = true)
        }
    }

    BeamCard {
        Text("INGÅNG", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        listOf(
            listOf("HDMI 1" to "0011", "HDMI 2" to "0012"),
            listOf("HDMI 3" to "0082", "VGA RGB" to "0001"),
            listOf("VGA COMP" to "000C", "A/V IN" to "0005"),
        ).forEachIndexed { index, row ->
            if (index > 0) Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { (label, code) ->
                    NecButton(label, working, Modifier.weight(1f), { onInput(code) })
                }
            }
        }
    }

    BeamCard {
        Text("AVANCERAT", color = BeamOrange, fontWeight = FontWeight.Black, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = customCode,
                onValueChange = { value ->
                    customCode = value.uppercase().filter { it.isDigit() || it in 'A'..'F' }.take(4)
                },
                label = { Text("4-siffrig hexkod") },
                enabled = !working,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                colors = necFieldColors(),
                modifier = Modifier.weight(1f),
            )
            NecButton(
                label = "Skicka",
                working = working || customCode.length != 4,
                modifier = Modifier.align(Alignment.CenterVertically),
                onClick = { onInput(customCode) },
                primary = true,
            )
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
private fun NecButton(
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
    val content = if (primary) BeamDark else BeamText
    Button(
        onClick = onClick,
        enabled = !working,
        modifier = modifier.height(54.dp),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, if (danger) BeamOrange else BeamMint.copy(alpha = 0.5f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.45f),
        ),
    ) { Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}

@Composable
private fun necFieldColors() = OutlinedTextFieldDefaults.colors(
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
