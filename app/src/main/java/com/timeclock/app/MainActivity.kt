package com.timeclock.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*

data class TimeSlot(
    val hour: Int,
    val minute: Int,
    val label: String,
    val emailSubject: String
)

class MainActivity : ComponentActivity() {

    companion object {
        const val CHANNEL_ID = "timeclock_channel"

        val TIME_SLOTS = listOf(
            TimeSlot(9, 0, "9h00", "Pointage"),
            TimeSlot(12, 0, "12h00", "Depointage"),
            TimeSlot(13, 30, "13h30", "Pointage"),
            TimeSlot(17, 30, "17h30", "Depointage"),
        )

        const val RECIPIENT = "a.grandvilliers@critt-tjfu.com"

        fun scheduleAlarms(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)

            TIME_SLOTS.forEachIndexed { index, slot ->
                val intent = Intent(context, AlarmReceiver::class.java).apply {
                    putExtra("slot_index", index)
                    putExtra("slot_label", slot.label)
                    putExtra("slot_subject", slot.emailSubject)
                }

                val pendingIntent = PendingIntent.getBroadcast(
                    context, index, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, slot.hour)
                    set(Calendar.MINUTE, slot.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    if (before(Calendar.getInstance())) {
                        add(Calendar.DAY_OF_YEAR, 1)
                    }
                }

                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pendingIntent
                )
            }
        }
    }

    private val clockedStates = mutableStateListOf(false, false, false, false)
    private var pendingSlotIndex = -1

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val prefs by lazy {
        getSharedPreferences("timeclock", Context.MODE_PRIVATE)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { it }) {
            if (pendingSlotIndex >= 0) getLocationAndSendEmail(pendingSlotIndex)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createNotificationChannel()
        scheduleAlarms(this)
        loadTodayState()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF1565C0),
                )
            ) {
                TimeClockScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        loadTodayState()
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(Date())

    private fun loadTodayState() {
        val today = todayKey()
        for (i in 0..3) {
            clockedStates[i] = prefs.getBoolean("${today}_$i", false)
        }
    }

    private fun saveTodayState(index: Int, value: Boolean) {
        prefs.edit().putBoolean("${todayKey()}_$index", value).apply()
    }

    private fun isWeekday(): Boolean {
        val today = LocalDate.now()
        return today.dayOfWeek != DayOfWeek.SATURDAY && today.dayOfWeek != DayOfWeek.SUNDAY
    }

    @Composable
    fun TimeClockScreen() {
        val today = LocalDate.now()
        val formatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)
        val dateStr = today.format(formatter).replaceFirstChar { it.uppercase() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFFF5F5F5)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Image(
                    painter = painterResource(id = R.drawable.logo_critt),
                    contentDescription = "CRITT TJFU",
                    modifier = Modifier.height(100.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "TimeClock",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = dateStr,
                    fontSize = 16.sp,
                    color = Color.Gray
                )

                Spacer(modifier = Modifier.height(32.dp))

                if (!isWeekday()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFF3E0)
                        )
                    ) {
                        Text(
                            text = "Pas de pointage le week-end",
                            modifier = Modifier.padding(24.dp),
                            fontSize = 16.sp,
                            color = Color(0xFFE65100)
                        )
                    }
                } else {
                    TIME_SLOTS.forEachIndexed { index, slot ->
                        TimeSlotCard(index, slot)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    @Composable
    fun TimeSlotCard(index: Int, slot: TimeSlot) {
        val isClocked = clockedStates[index]
        val isPointage = slot.emailSubject == "Pointage"
        val accentColor = if (isPointage) Color(0xFF2E7D32) else Color(0xFFC62828)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isClocked) Color(0xFFE8F5E9) else Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = slot.label,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = slot.emailSubject,
                        fontSize = 14.sp,
                        color = accentColor,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isClocked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Fait",
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        OutlinedButton(
                            onClick = {
                                clockedStates[index] = false
                                saveTodayState(index, false)
                            }
                        ) {
                            Text("Annuler")
                        }
                    }
                } else {
                    Button(
                        onClick = { onPointerClick(index) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor
                        ),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("Pointer", fontSize = 16.sp)
                    }
                }
            }
        }
    }

    private fun onPointerClick(index: Int) {
        pendingSlotIndex = index

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            getLocationAndSendEmail(index)
        } else {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun getLocationAndSendEmail(index: Int) {
        val slot = TIME_SLOTS[index]

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            sendEmail(slot, "Position: non disponible")
            clockedStates[index] = true
            saveTodayState(index, true)
            return
        }

        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            CancellationTokenSource().token
        ).addOnSuccessListener { location ->
            val body = if (location != null) {
                "Position: ${location.latitude}, ${location.longitude}"
            } else {
                "Position: non disponible"
            }
            sendEmail(slot, body)
            clockedStates[index] = true
            saveTodayState(index, true)
        }.addOnFailureListener {
            sendEmail(slot, "Position: non disponible")
            clockedStates[index] = true
            saveTodayState(index, true)
        }
    }

    private fun sendEmail(slot: TimeSlot, body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("me.bluemail.mail")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(RECIPIENT))
            putExtra(Intent.EXTRA_SUBJECT, slot.emailSubject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            // BlueMail non installe, fallback mailto:
            val encodedSubject = Uri.encode(slot.emailSubject)
            val encodedBody = Uri.encode(body)
            val uri = Uri.parse("mailto:$RECIPIENT?subject=$encodedSubject&body=$encodedBody")
            startActivity(Intent(Intent.ACTION_SENDTO, uri))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Rappels de pointage",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications de rappel pour pointer"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
