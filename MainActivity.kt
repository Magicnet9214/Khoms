package com.example.khumscalculator // نام پکیج پروژه خود را در این خط حفظ کنید

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.room.*
import androidx.work.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.DecimalFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

// ==========================================
// ۱. تعریف مراجع تقلید و تفاوت فتواها
// ==========================================
enum class MarjaTaqlid(
    val title: String,
    val isGiftSubjectToKhums: Boolean,
    val rulingDescription: String
) {
    KHAMENEI(
        title = "آیت‌الله خامنه‌ای",
        isGiftSubjectToKhums = false,
        rulingDescription = "هدایا، عیدی و جوایز (اگر عرفاً هدیه محسوب شوند) خمس ندارند؛ حتی اگر تا پایان سال خمسی مصرف نشوند."
    ),
    SISTANI(
        title = "آیت‌الله سیستانی",
        isGiftSubjectToKhums = true,
        rulingDescription = "اگر هدیه یا عیدی تا پایان سال خمسی در مخارج زندگی مصرف نشود و باقی بماند، به مقدار باقیمانده خمس تعلق می‌گیرد."
    ),
    MAKAREM(
        title = "آیت‌الله مکارم شیرازی",
        isGiftSubjectToKhums = true,
        rulingDescription = "اضافه هدایا و جوایز که تا پایان سال خمسی مصرف نشده‌اند، بنابر احتیاط واجب مشمول خمس هستند."
    ),
    VAHID(
        title = "آیت‌الله وحید خراسانی",
        isGiftSubjectToKhums = false,
        rulingDescription = "مال بخشیده‌شده (هبه و هدیه) جزو ارباح مکاسب محسوب نمی‌شود و خمس ندارد."
    )
}

// ==========================================
// ۲. پایگاه داده Room (جدول، DAO و Database)
// ==========================================
@Entity(tableName = "khums_records")
data class KhumsRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val yearTitle: String,
    val marjaName: String,
    val totalAssets: Long,
    val totalDeductions: Long,
    val netTaxable: Long,
    val totalKhums: Long,
    val remainingTaxedCapital: Long,
    val saveDate: String
)

@Dao
interface KhumsDao {
    @Query("SELECT * FROM khums_records ORDER BY id DESC")
    fun getAllRecords(): Flow<List<KhumsRecord>>

    @Insert
    suspend fun insertRecord(record: KhumsRecord)

    @Delete
    suspend fun deleteRecord(record: KhumsRecord)
}

@Database(entities = [KhumsRecord::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun khumsDao(): KhumsDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "khums_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// ۳. جداکننده سه‌رقمی خودکار هنگام تایپ
// ==========================================
class ThousandSeparatorTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text
        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val formattedText = buildString {
            for (i in originalText.indices) {
                append(originalText[i])
                val remaining = originalText.length - 1 - i
                if (remaining > 0 && remaining % 3 == 0) {
                    append(',')
                }
            }
        }

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                val clampedOffset = offset.coerceAtMost(originalText.length)
                var commas = 0
                for (i in 0 until clampedOffset) {
                    val remaining = originalText.length - 1 - i
                    if (remaining > 0 && remaining % 3 == 0) {
                        commas++
                    }
                }
                return clampedOffset + commas
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                val clampedOffset = offset.coerceAtMost(formattedText.length)
                val commasBefore = formattedText.substring(0, clampedOffset).count { it == ',' }
                return (clampedOffset - commasBefore).coerceIn(0, originalText.length)
            }
        }

        return TransformedText(AnnotatedString(formattedText), offsetMapping)
    }
}

// ==========================================
// ۴. مبدل دقیق تاریخ میلادی به شمسی (جلالی)
// ==========================================
object JalaliDateUtils {
    private val persianMonths = arrayOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    )

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) +
                ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]

        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461

        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }

        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }

    fun getCurrentJalaliYear(): Int {
        val cal = Calendar.getInstance()
        val (jy, _, _) = gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        return jy
    }

    fun getCurrentShamsiDateTime(): String {
        val cal = Calendar.getInstance()
        val (jy, jm, jd) = gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)

        return String.format(
            Locale.US,
            "%04d/%02d/%02d - %02d:%02d",
            jy, jm, jd, hour, minute
        )
    }

    fun getCurrentShamsiFullDate(): String {
        val cal = Calendar.getInstance()
        val (jy, jm, jd) = gregorianToJalali(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
        val monthName = persianMonths[jm - 1]
        return "$jd $monthName $jy"
    }
}

// ==========================================
// ۵. کلاس Worker و زمان‌بندی یادآور (WorkManager)
// ==========================================
class KhumsReminderWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val notificationManager =
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "khums_reminder_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "یادآور سررسید سال خمسی",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان سالانه برای بررسی و محاسبه خمس"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("فرارسیدن سررسید سال خمسی")
            .setContentText("زمان بررسی موجودی‌ها و محاسبه خمس سالانه شما فرارسیده است.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
        return Result.success()
    }
}

fun scheduleKhumsReminder(
    context: Context,
    delayAmount: Long,
    timeUnit: TimeUnit,
    toastMessage: String
) {
    val workRequest = OneTimeWorkRequestBuilder<KhumsReminderWorker>()
        .setInitialDelay(delayAmount, timeUnit)
        .addTag("khums_annual_reminder")
        .build()

    WorkManager.getInstance(context).enqueueUniqueWork(
        "khums_reminder_work",
        ExistingWorkPolicy.REPLACE,
        workRequest
    )

    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

// ==========================================
// ۶. ساخت و اشتراک‌گذاری رسید PDF
// ==========================================
fun exportAndShareKhumsPdf(
    context: Context,
    yearTitle: String,
    marjaName: String,
    totalAssets: Long,
    totalDeductions: Long,
    netTaxable: Long,
    totalKhums: Long,
    halfShare: Long
) {
    val formatter = DecimalFormat("#,###")
    val pdfDocument = PdfDocument()

    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
    val page = pdfDocument.startPage(pageInfo)
    val canvas = page.canvas

    val titlePaint = Paint().apply {
        color = AndroidColor.rgb(25, 118, 210)
        textSize = 22f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    val textPaint = Paint().apply {
        color = AndroidColor.BLACK
        textSize = 15f
        textAlign = Paint.Align.RIGHT
    }

    val boldPaint = Paint().apply {
        color = AndroidColor.rgb(0, 100, 0)
        textSize = 18f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }

    val linePaint = Paint().apply {
        color = AndroidColor.LTGRAY
        strokeWidth = 2f
    }

    val rightMargin = 545f
    var yPos = 70f

    canvas.drawText("رسید محاسبه خمس سالانه ($yearTitle)", rightMargin, yPos, titlePaint)
    yPos += 35f
    canvas.drawText("مرجع تقلید: $marjaName", rightMargin, yPos, textPaint)
    yPos += 25f
    val currentDate = "${JalaliDateUtils.getCurrentShamsiFullDate()} (${JalaliDateUtils.getCurrentShamsiDateTime()})"
    canvas.drawText("تاریخ صدور: $currentDate", rightMargin, yPos, textPaint)

    yPos += 20f
    canvas.drawLine(50f, yPos, rightMargin, yPos, linePaint)

    yPos += 40f
    canvas.drawText("جمع کل دارایی‌های مشمول: ${formatter.format(totalAssets)} تومان", rightMargin, yPos, textPaint)
    yPos += 35f
    canvas.drawText("جمع کل کسورات و مستثنیات: ${formatter.format(totalDeductions)} تومان", rightMargin, yPos, textPaint)
    yPos += 35f
    canvas.drawText("مازاد خالص مشمول خمس: ${formatter.format(netTaxable)} تومان", rightMargin, yPos, textPaint)

    yPos += 25f
    canvas.drawLine(50f, yPos, rightMargin, yPos, linePaint)

    yPos += 45f
    canvas.drawText("خمس قابل پرداخت (۲۰٪): ${formatter.format(totalKhums)} تومان", rightMargin, yPos, boldPaint)
    yPos += 40f
    canvas.drawText("• سهم مبارک امام (ره): ${formatter.format(halfShare)} تومان", rightMargin, yPos, textPaint)
    yPos += 30f
    canvas.drawText("• سهم سادات جلیل‌القدر: ${formatter.format(halfShare)} تومان", rightMargin, yPos, textPaint)
    yPos += 35f
    canvas.drawText("• سرمایه مخمّس باقیمانده برای سال بعد: ${formatter.format(netTaxable - totalKhums)} تومان", rightMargin, yPos, textPaint)

    pdfDocument.finishPage(page)

    try {
        val receiptsDir = File(context.cacheDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()

        val pdfFile = File(receiptsDir, "Khums_Receipt.pdf")
        FileOutputStream(pdfFile).use { outputStream ->
            pdfDocument.writeTo(outputStream)
        }
        pdfDocument.close()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            pdfFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "رسید محاسبه خمس - $yearTitle")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "اشتراک‌گذاری یا ذخیره رسید PDF"))

    } catch (e: Exception) {
        Toast.makeText(context, "خطا در ساخت PDF: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

// ==========================================
// ۷. اکتیویتی اصلی و رابط کاربری (UI)
// ==========================================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        KhumsCalculatorApp()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhumsCalculatorApp() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val dao = remember { db.khumsDao() }
    val records by dao.getAllRecords().collectAsState(initial = emptyList())
    val coroutineScope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "برای دریافت یادآور، مجوز اعلان لازم است", Toast.LENGTH_SHORT).show()
        }
    }

    var selectedMarja by remember { mutableStateOf(MarjaTaqlid.KHAMENEI) }
    var marjaDropdownExpanded by remember { mutableStateOf(false) }

    var yearTitle by remember {
        mutableStateOf("سال خمسی ${JalaliDateUtils.getCurrentJalaliYear()}")
    }

    // متغیرهای دارایی‌ها
    var cashBalance by remember { mutableStateOf("") }
    var unusedGoods by remember { mutableStateOf("") }
    var investments by remember { mutableStateOf("") }
    var receivables by remember { mutableStateOf("") }
    var remainingGifts by remember { mutableStateOf("") }

    // متغیرهای کسورات
    var exemptions by remember { mutableStateOf("") }
    var livingDebts by remember { mutableStateOf("") }

    val giftAmount = remainingGifts.toLongOrNull() ?: 0L
    val taxableGiftAmount = if (selectedMarja.isGiftSubjectToKhums) giftAmount else 0L

    val totalAssets = (cashBalance.toLongOrNull() ?: 0L) +
            (unusedGoods.toLongOrNull() ?: 0L) +
            (investments.toLongOrNull() ?: 0L) +
            (receivables.toLongOrNull() ?: 0L) +
            taxableGiftAmount

    val totalDeductions = (exemptions.toLongOrNull() ?: 0L) +
            (livingDebts.toLongOrNull() ?: 0L)

    val netTaxable = (totalAssets - totalDeductions).coerceAtLeast(0L)
    val totalKhums = netTaxable / 5
    val halfShare = totalKhums / 2
    val remainingTaxedCapital = netTaxable - totalKhums

    val formatter = remember { DecimalFormat("#,###") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("محاسبه‌گر هوشمند خمس", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // بخش اول: انتخاب مرجع تقلید
            Text(
                text = "۱. انتخاب مرجع تقلید",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            ExposedDropdownMenuBox(
                expanded = marjaDropdownExpanded,
                onExpandedChange = { marjaDropdownExpanded = !marjaDropdownExpanded }
            ) {
                OutlinedTextField(
                    value = selectedMarja.title,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("مرجع تقلید") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = marjaDropdownExpanded) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = marjaDropdownExpanded,
                    onDismissRequest = { marjaDropdownExpanded = false }
                ) {
                    MarjaTaqlid.entries.forEach { marja ->
                        DropdownMenuItem(
                            text = { Text(marja.title) },
                            onClick = {
                                selectedMarja = marja
                                marjaDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "حکم هدایا و عیدی: ${selectedMarja.rulingDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // بخش دوم: دارایی‌ها
            Text(
                text = "۲. دارایی‌های باقیمانده از درآمد سال (تومان)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            NumberInputField("موجودی نقد و بانکی", cashBalance) { cashBalance = it }
            NumberInputField("ارزش اقلام و آذوقه مصرف‌نشده", unusedGoods) { unusedGoods = it }
            NumberInputField("سرمایه‌گذاری‌ها (سکه، طلا، ارز، سهام)", investments) { investments = it }
            NumberInputField("طلب‌های قابل وصول از دیگران", receivables) { receivables = it }
            NumberInputField(
                label = if (selectedMarja.isGiftSubjectToKhums)
                    "هدایا و عیدی باقیمانده (مشمول خمس)"
                else
                    "هدایا و عیدی باقیمانده (معاف طبق نظر مرجع شما)",
                value = remainingGifts
            ) { remainingGifts = it }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // بخش سوم: کسورات
            Text(
                text = "۳. کسورات و مستثنیات (تومان)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Bold
            )

            NumberInputField("سرمایه خمس‌داده‌شده قبلی، ارث یا مهریه", exemptions) { exemptions = it }
            NumberInputField("بدهی‌های پرداخت‌نشده برای مخارج امسال", livingDebts) { livingDebts = it }

            OutlinedButton(
                onClick = {
                    cashBalance = ""
                    unusedGoods = ""
                    investments = ""
                    receivables = ""
                    remainingGifts = ""
                    exemptions = ""
                    livingDebts = ""
                },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("پاک کردن فرم")
            }

            // کارت نتیجه محاسبه و عملیات
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "نتیجه محاسبه (${selectedMarja.title})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text("جمع کل دارایی‌های مشمول: ${formatter.format(totalAssets)} تومان")
                    Text("جمع کل کسورات: ${formatter.format(totalDeductions)} تومان")
                    Text("مازاد خالص مشمول خمس: ${formatter.format(netTaxable)} تومان")

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    Text(
                        text = "خمس قابل پرداخت (۲۰٪): ${formatter.format(totalKhums)} تومان",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("سهم سادات: ${formatter.format(halfShare)} تومان")
                        Text("سهم امام: ${formatter.format(halfShare)} تومان")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = yearTitle,
                        onValueChange = { yearTitle = it },
                        label = { Text("عنوان برای ذخیره یا چاپ رسید") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            val currentDate = JalaliDateUtils.getCurrentShamsiDateTime()
                            val record = KhumsRecord(
                                yearTitle = yearTitle.ifBlank { "سال خمسی بدون عنوان" },
                                marjaName = selectedMarja.title,
                                totalAssets = totalAssets,
                                totalDeductions = totalDeductions,
                                netTaxable = netTaxable,
                                totalKhums = totalKhums,
                                remainingTaxedCapital = remainingTaxedCapital,
                                saveDate = currentDate
                            )
                            coroutineScope.launch {
                                dao.insertRecord(record)
                                Toast.makeText(context, "در سوابق ذخیره شد", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("ذخیره در تاریخچه سال‌های خمسی")
                    }

                    OutlinedButton(
                        onClick = {
                            exportAndShareKhumsPdf(
                                context = context,
                                yearTitle = yearTitle,
                                marjaName = selectedMarja.title,
                                totalAssets = totalAssets,
                                totalDeductions = totalDeductions,
                                netTaxable = netTaxable,
                                totalKhums = totalKhums,
                                halfShare = halfShare
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("دریافت رسید PDF و اشتراک‌گذاری")
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text(
                        text = "تنظیم یادآور سررسید سال خمسی:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scheduleKhumsReminder(
                                        context = context,
                                        delayAmount = 365,
                                        timeUnit = TimeUnit.DAYS,
                                        toastMessage = "یادآور برای ۳۶۵ روز دیگر تنظیم شد!"
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("یادآور سال بعد")
                        }

                        OutlinedButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    scheduleKhumsReminder(
                                        context = context,
                                        delayAmount = 5,
                                        timeUnit = TimeUnit.SECONDS,
                                        toastMessage = "نوتیفیکیشن تستی ۵ ثانیه دیگر نمایش داده می‌شود..."
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("تست آنی (۵ ثانیه)")
                        }
                    }
                }
            }

            // بخش چهارم: نمایش تاریخچه ذخیره‌شده از دیتابیس Room
            if (records.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "۴. سوابق سال‌های خمسی ذخیره‌شده",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                records.forEach { record ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${record.yearTitle} (${record.marjaName})",
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = {
                                        coroutineScope.launch { dao.deleteRecord(record) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "حذف سابقه",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Text("تاریخ ثبت (شمسی): ${record.saveDate}", style = MaterialTheme.typography.bodySmall)
                            Text("خمس پرداختی آن سال: ${formatter.format(record.totalKhums)} تومان")
                            Text("سرمایه مخمّس باقیمانده (۸۰٪): ${formatter.format(record.remainingTaxedCapital)} تومان")

                            TextButton(
                                onClick = {
                                    exemptions = record.remainingTaxedCapital.toString()
                                    Toast.makeText(context, "به بخش کسورات (سرمایه مخمّس) منتقل شد", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text("قرار دادن به عنوان سرمایه مخمّس در فرم بالا")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NumberInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val digitsOnly = input.filter { it.isDigit() }
            if (digitsOnly.length <= 15) {
                onValueChange(digitsOnly)
            }
        },
        label = { Text(label) },
        placeholder = { Text("۰") },
        visualTransformation = ThousandSeparatorTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}
