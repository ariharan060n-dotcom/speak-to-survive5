package com.speaktosurvive.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ====================================================================== root

@Composable
fun AppRoot(
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onSosNow: () -> Unit,
    onCancelSos: () -> Unit,
    onRequestPerm: (String) -> Unit
) {
    Scaffold(
        containerColor = Pal.Bg,
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                val items = listOf("Home", "Contacts", "Settings")
                for (i in items.indices) {
                    NavigationBarItem(
                        selected = AppState.tab == i,
                        onClick = { AppState.tab = i },
                        icon = {
                            Icon(
                                when (i) {
                                    0 -> Icons.Filled.Home
                                    1 -> Icons.Filled.Person
                                    else -> Icons.Filled.Settings
                                },
                                contentDescription = items[i]
                            )
                        },
                        label = { Text(items[i]) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Pal.Indigo,
                            indicatorColor = Pal.Indigo,
                            unselectedIconColor = Pal.Muted,
                            unselectedTextColor = Pal.Muted
                        )
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (AppState.tab) {
                0 -> HomeScreen(onToggle, onTest, onSosNow, onCancelSos)
                1 -> ContactsScreen()
                else -> SettingsScreen(onRequestPerm)
            }
        }
    }
}

// ====================================================================== shared pieces

@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Pal.Card),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Pal.IndigoDark)
            content()
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, state: Int) {
    // state: 0 = off, 1 = ok, 2 = busy/warn
    val dot = when (state) {
        1 -> Pal.Ok
        2 -> Pal.Warn
        else -> Pal.Off
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(10.dp))
        Text(label, fontWeight = FontWeight.SemiBold, color = Pal.Ink, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = Pal.Muted, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = Pal.Ink, fontSize = 15.sp)
            Text(subtitle, color = Pal.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Pal.Indigo)
        )
    }
}

private fun isIgnoringBattery(ctx: Context): Boolean {
    return try {
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        false
    }
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}

private fun openSettingsIntent(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent)
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${ctx.packageName}")
                )
            )
        } catch (e2: Exception) {
            toast(ctx, "Please open Android Settings manually")
        }
    }
}

// ====================================================================== home

@Composable
fun ShieldButton(running: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (running) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val fill = if (running) Pal.Ok else Color.White
    val ring = if (running) Pal.Ok else Pal.IndigoSoft
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(210.dp)) {
        Box(
            Modifier
                .size(190.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(ring.copy(alpha = 0.30f))
        )
        Box(
            Modifier
                .size(158.dp)
                .clip(CircleShape)
                .background(fill)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    if (running) Icons.Filled.Check else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (running) Color.White else Pal.Indigo,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (running) "PROTECTED" else "TAP TO START",
                    color = if (running) Color.White else Pal.Indigo,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onSosNow: () -> Unit,
    onCancelSos: () -> Unit
) {
    val ctx = LocalContext.current
    val tick = AppState.resumeTick
    val running = AppState.guardRunning
    val contacts = Prefs.contacts(ctx)
    val setupOk = tick >= 0 && Perms.allGranted(ctx) && contacts.isNotEmpty()
    val code = Prefs.codeWord(ctx)
    val voiceOn = Prefs.voiceEnabled(ctx)
    val powerOn = Prefs.powerEnabled(ctx)
    val presses = Prefs.powerPresses(ctx)
    val autoCall = Prefs.autoCall(ctx)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
                .background(Brush.verticalGradient(listOf(Pal.IndigoDark, Pal.Indigo)))
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Speak to Survive",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text("Hands-free emergency alerts", color = Pal.IndigoSoft, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                ShieldButton(running, onToggle)
                Spacer(Modifier.height(8.dp))
                Text(
                    if (AppState.sosActive) "SOS IS ACTIVE" else if (running) "You are protected" else "Protection is off",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                val sub = AppState.lastEvent.ifBlank {
                    if (running) "Say your code word or press the power button quickly" else "Tap the button to switch on"
                }
                Text(sub, color = Pal.IndigoSoft, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
            }
        }

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (AppState.sosActive) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Pal.DangerSoft),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = Pal.Danger)
                            Spacer(Modifier.width(8.dp))
                            Text("SOS active", fontWeight = FontWeight.Bold, color = Pal.Danger, fontSize = 17.sp)
                        }
                        Text(
                            "Your contacts are being alerted with your location. Tap below only when you are safe.",
                            color = Pal.Ink, fontSize = 13.sp
                        )
                        Button(
                            onClick = onCancelSos,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Pal.Danger)
                        ) { Text("I'M SAFE - STOP ALERTS", fontWeight = FontWeight.Bold) }
                    }
                }
            }

            if (!setupOk) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Pal.WarnSoft),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Finish setup", fontWeight = FontWeight.Bold, color = Pal.Warn, fontSize = 16.sp)
                        Text(
                            if (contacts.isEmpty()) "Add at least one emergency contact, then allow the permissions."
                            else "Some permissions are not allowed yet. Tap the big button to allow them, or open Settings.",
                            color = Pal.Ink, fontSize = 13.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { AppState.tab = 1 }) { Text("Contacts") }
                            OutlinedButton(onClick = { AppState.tab = 2 }) { Text("Settings") }
                        }
                    }
                }
            }

            SectionCard("Your triggers") {
                StatusRow(
                    "Voice code",
                    if (!voiceOn) "Off" else "\"$code\"  -  ${AppState.voiceStatus}",
                    if (!voiceOn || !running) 0 else if (AppState.voiceStatus == "Listening") 1 else 2
                )
                StatusRow(
                    "Power button",
                    if (powerOn) "Press ${presses}x quickly" else "Off",
                    if (powerOn && running) 1 else 0
                )
                StatusRow(
                    "Alert route",
                    "Internet app, then SMS, then mesh",
                    if (running) 1 else 0
                )
                StatusRow(
                    "Nearby SOS phones",
                    if (!Prefs.meshEnabled(ctx)) "Off" else AppState.meshStatus,
                    if (!Prefs.meshEnabled(ctx) || !running) 0 else if (AppState.meshPeers > 0) 1 else 2
                )
                if (autoCall) {
                    StatusRow(
                        "Auto-call",
                        "Calls ${contacts.firstOrNull()?.name ?: "first contact"}",
                        if (contacts.isNotEmpty()) 1 else 0
                    )
                }
                StatusRow(
                    "Emergency contacts",
                    "${contacts.size} saved",
                    if (contacts.isNotEmpty()) 1 else 2
                )
            }

            SectionCard("Try it safely") {
                Text(
                    "The test sends one alert marked TEST ALERT to your contacts by the best route available. It does not make a call.",
                    color = Pal.Muted, fontSize = 13.sp
                )
                OutlinedButton(
                    onClick = onTest,
                    enabled = running && !AppState.sosActive,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Send test alert") }
                if (!running) {
                    Text("Switch protection on first.", color = Pal.Muted, fontSize = 12.sp)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Pal.Danger)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { onSosNow() },
                            onTap = { toast(ctx, "Press and hold to send a real SOS") }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "HOLD TO SEND SOS NOW",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ====================================================================== contacts

@Composable
fun ContactsScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Prefs.contacts(ctx)) }
    var name by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Emergency contacts", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Pal.IndigoDark)
        Text(
            "These people get an SMS with your live location. The first contact is also called automatically.",
            color = Pal.Muted, fontSize = 13.sp
        )

        SectionCard("Add a contact") {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; error = null },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it; error = null },
                label = { Text("Phone number (e.g. +919876543210)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                isError = error != null,
                supportingText = { if (error != null) Text(error ?: "") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val n = name.trim()
                    val p = number.replace(" ", "").replace("-", "")
                    when {
                        list.size >= Prefs.MAX_CONTACTS -> error = "You can save up to ${Prefs.MAX_CONTACTS} contacts"
                        n.isEmpty() -> error = "Enter a name"
                        !Prefs.numberOk(p) -> error = "Enter a valid phone number"
                        list.any { it.number == p } -> error = "This number is already saved"
                        else -> {
                            val updated = list + Contact(n, p)
                            Prefs.setContacts(ctx, updated)
                            list = updated
                            name = ""
                            number = ""
                            error = null
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Add contact", fontWeight = FontWeight.Bold)
            }
        }

        if (list.isEmpty()) {
            Text("No contacts yet.", color = Pal.Muted, fontSize = 14.sp)
        } else {
            SectionCard("Saved contacts (${list.size}/${Prefs.MAX_CONTACTS})") {
                for (i in list.indices) {
                    val c = list[i]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(
                            Modifier.size(42.dp).clip(CircleShape).background(Pal.IndigoSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                c.name.take(1).uppercase(),
                                color = Pal.Indigo,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (i == 0) "${c.name}  (auto-call)" else c.name,
                                fontWeight = FontWeight.SemiBold,
                                color = Pal.Ink
                            )
                            Text(c.number, color = Pal.Muted, fontSize = 13.sp)
                        }
                        IconButton(onClick = {
                            val updated = list.filterIndexed { idx, _ -> idx != i }
                            Prefs.setContacts(ctx, updated)
                            list = updated
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Pal.Danger)
                        }
                    }
                }
            }
        }
        Text(
            "Tip: use friends or family numbers while testing. Never test with 112 or the police.",
            color = Pal.Muted, fontSize = 12.sp
        )
    }
}

// ====================================================================== settings

@Composable
private fun PermRow(label: String, why: String, granted: Boolean, onAllow: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.SemiBold, color = Pal.Ink, fontSize = 14.sp)
            Text(why, color = Pal.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        if (granted) {
            Icon(Icons.Filled.Check, contentDescription = "Allowed", tint = Pal.Ok)
        } else {
            OutlinedButton(onClick = onAllow, shape = RoundedCornerShape(12.dp)) { Text("Allow") }
        }
    }
}

@Composable
fun SettingsScreen(onRequestPerm: (String) -> Unit) {
    val ctx = LocalContext.current
    val tick = AppState.resumeTick

    var name by remember { mutableStateOf(Prefs.userName(ctx)) }
    var code by remember { mutableStateOf(Prefs.codeWord(ctx)) }
    var voiceOn by remember { mutableStateOf(Prefs.voiceEnabled(ctx)) }
    var powerOn by remember { mutableStateOf(Prefs.powerEnabled(ctx)) }
    var presses by remember { mutableIntStateOf(Prefs.powerPresses(ctx)) }
    var autoCall by remember { mutableStateOf(Prefs.autoCall(ctx)) }
    var myNumber by remember { mutableStateOf(Prefs.myNumber(ctx)) }
    var alsoSms by remember { mutableStateOf(Prefs.alsoSms(ctx)) }
    var meshOn by remember { mutableStateOf(Prefs.meshEnabled(ctx)) }
    val codeErr = Prefs.codeError(code)

    val overlayOk = tick >= 0 && AndroidSettings.canDrawOverlays(ctx)
    val battOk = tick >= 0 && isIgnoringBattery(ctx)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Pal.IndigoDark)

        SectionCard("About you") {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    Prefs.setUserName(ctx, it.trim())
                },
                label = { Text("Your name (shown in the alert)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = myNumber,
                onValueChange = {
                    myNumber = it
                    Prefs.setMyNumber(ctx, it.trim())
                },
                label = { Text("Your own phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                supportingText = {
                    Text("Needed to receive alerts sent to you through the app. Switch protection off and on to apply.")
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        SectionCard("Voice code") {
            ToggleRow("Voice trigger", "Listens offline for your secret phrase", voiceOn) {
                voiceOn = it
                Prefs.setVoiceEnabled(ctx, it)
            }
            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it
                    if (Prefs.codeError(it) == null) Prefs.setCodeWord(ctx, it.trim().lowercase())
                },
                label = { Text("Secret phrase") },
                singleLine = true,
                isError = codeErr != null,
                supportingText = { Text(codeErr ?: "Saved. Switch protection off and on to apply.") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Use ordinary English words (2 to 4). The offline voice engine only knows common words, so rare or invented words will not work.",
                color = Pal.Muted, fontSize = 12.sp
            )
        }

        SectionCard("Power button") {
            ToggleRow("Power button trigger", "Press the power button quickly several times", powerOn) {
                powerOn = it
                Prefs.setPowerEnabled(ctx, it)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Presses needed", color = Pal.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        if (presses > 3) {
                            presses -= 1
                            Prefs.setPowerPresses(ctx, presses)
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("-") }
                Text("  $presses  ", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Pal.IndigoDark)
                OutlinedButton(
                    onClick = {
                        if (presses < 8) {
                            presses += 1
                            Prefs.setPowerPresses(ctx, presses)
                        }
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+") }
            }
            Text(
                "Presses must happen within 6 seconds. If your phone has its own Emergency SOS or camera shortcut on the power button, turn it off in Android Settings so they do not clash.",
                color = Pal.Muted, fontSize = 12.sp
            )
        }

        SectionCard("How alerts are sent") {
            Text(
                "1. Internet: a message with your location goes to your contacts through this app.\n" +
                    "2. No internet: an SMS with your location is sent.\n" +
                    "3. No signal at all: the alert hops between nearby phones that have this app (Bluetooth mesh).",
                color = Pal.Ink, fontSize = 13.sp
            )
            ToggleRow(
                "Also send SMS when online",
                "Turn on if some contacts do not have this app",
                alsoSms
            ) {
                alsoSms = it
                Prefs.setAlsoSms(ctx, it)
            }
            ToggleRow(
                "Bluetooth mesh",
                "Relay SOS between nearby phones with this app. Uses extra battery.",
                meshOn
            ) {
                meshOn = it
                Prefs.setMeshEnabled(ctx, it)
            }
            Text(
                "Switch protection off and on after changing the mesh setting. Your contacts' phone numbers are shared with nearby phones only while an SOS is being relayed, so they can forward it to your contacts.",
                color = Pal.Muted, fontSize = 12.sp
            )
        }

        SectionCard("Emergency call") {
            ToggleRow("Auto-call first contact", "Off by default. Calls in addition to the messages", autoCall) {
                autoCall = it
                Prefs.setAutoCall(ctx, it)
            }
        }

        SectionCard("Permissions") {
            PermRow("Microphone", "Hear your code word", Perms.granted(ctx, Manifest.permission.RECORD_AUDIO)) {
                onRequestPerm(Manifest.permission.RECORD_AUDIO)
            }
            PermRow("Location", "Add your position to the alert", Perms.granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) {
                onRequestPerm(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            PermRow("SMS", "Send the alert text", Perms.granted(ctx, Manifest.permission.SEND_SMS)) {
                onRequestPerm(Manifest.permission.SEND_SMS)
            }
            if (Perms.mesh().isNotEmpty()) {
                PermRow("Nearby devices", "Bluetooth mesh with other phones", tick >= 0 && Perms.meshGranted(ctx)) {
                    onRequestPerm("MESH")
                }
            }
            PermRow("Phone calls", "Only needed for auto-call", Perms.granted(ctx, Manifest.permission.CALL_PHONE)) {
                onRequestPerm(Manifest.permission.CALL_PHONE)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                PermRow(
                    "Notifications", "Show the protection notice",
                    Perms.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
                ) { onRequestPerm(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }

        SectionCard("Keep it working in the background") {
            PermRow("Display over other apps", "Helps the emergency call start when the screen is off", overlayOk) {
                openSettingsIntent(
                    ctx,
                    Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }
            PermRow("Unrestricted battery", "Stops Android from stopping the app", battOk) {
                openSettingsIntent(
                    ctx,
                    Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
            }
            Text(
                "On Xiaomi, Oppo, Vivo and Realme phones also switch on Autostart for this app in the phone's own settings.",
                color = Pal.Muted, fontSize = 12.sp
            )
            OutlinedButton(
                onClick = {
                    openSettingsIntent(
                        ctx,
                        Intent(
                            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${ctx.packageName}")
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Open this app's Android settings") }
        }

        SectionCard("Privacy") {
            Text(
                "Your voice is processed on the phone only. Audio is never recorded, stored or uploaded. Only the alert text and location link are sent.",
                color = Pal.Muted, fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}
