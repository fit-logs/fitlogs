package com.example.fitnotes.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode
import java.math.MathContext
import java.util.*
import java.text.SimpleDateFormat

/** Local profile in the existing database. No profile fields are added to AI exports. */
@Entity(tableName = "body_profile")
data class BodyProfile(
    @PrimaryKey val id: Int = 1,
    val heightCm: Double? = null,
    val birthDate: String? = null,
    val weightUnit: String = "kg",
    val heightUnit: String = "cm",
    val chartRange: String = "3M",
)

@Dao
interface BodyProfileDao {
    @Query("SELECT * FROM body_profile WHERE id = 1") fun observe(): Flow<BodyProfile?>
    @Query("SELECT * FROM body_profile WHERE id = 1") suspend fun get(): BodyProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(profile: BodyProfile)
    @Query("DELETE FROM body_profile") suspend fun clear()
}

val BODY_PROFILE_MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE body_measurements ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE IF NOT EXISTS body_profile (id INTEGER NOT NULL PRIMARY KEY, heightCm REAL, birthDate TEXT, weightUnit TEXT NOT NULL, heightUnit TEXT NOT NULL, chartRange TEXT NOT NULL)")
    }
}

fun canonicalWeightUnit(raw: String): String? = when (raw.trim().lowercase(Locale.ROOT)) {
    "kg", "kgs", "kilogram", "kilograms" -> "kg"
    "lb", "lbs", "pound", "pounds" -> "lb"
    else -> null
}
fun weightKg(value: Double, unit: String): Double? {
    if (!value.isFinite() || value <= 0) return null
    return when (canonicalWeightUnit(unit)) { "kg" -> value; "lb" -> value * .45359237; else -> null }
        ?.takeIf { it.isFinite() && it > 0 }
}
fun weightInUnit(kg: Double, unit: String): Double = if (unit == "lb") kg / .45359237 else kg
fun bodyWeightText(kg: Double, unit: String): String {
    val value = weightInUnit(kg, unit)
    return if (value.isFinite()) "${bodyDecimal(value)} $unit" else "${bodyDecimal(kg)} kg"
}
fun heightFromFeet(feet: Double, inches: Double): Double? =
    if (feet.isFinite() && inches.isFinite() && feet >= 0 && feet % 1.0 == 0.0 && inches >= 0 && inches < 12)
        ((feet * 12 + inches) * 2.54).takeIf { it.isFinite() && it > 0 } else null
fun unusualHeight(cm: Double) = cm < 100 || cm > 250
fun unusualWeight(kg: Double) = kg < 20 || kg > 350
fun bmi(weightKg: Double?, heightCm: Double?): Double? {
    if (weightKg == null || heightCm == null || !weightKg.isFinite() || !heightCm.isFinite() || weightKg <= 0 || heightCm <= 0) return null
    val metres = heightCm / 100
    return (weightKg / metres / metres).takeIf { it.isFinite() && it > 0 }
}
fun adultBmiCategory(value: Double): String? = when {
    !value.isFinite() || value <= 0 -> null
    value < 18.5 -> "Underweight range"
    value < 25 -> "Healthy weight range"
    value < 30 -> "Overweight range"
    else -> "Obesity range"
}
fun ageOn(birthDate: String?, today: String): Int? {
    if (birthDate == null || !validHistoryDate(birthDate) || !validHistoryDate(today) || birthDate > today) return null
    return today.take(4).toInt() - birthDate.take(4).toInt() - if (today.substring(5) < birthDate.substring(5)) 1 else 0
}
fun adultBmiEligible(birthDate: String?, today: String) = ageOn(birthDate, today)?.let { it >= 20 } == true
fun bodyDecimal(value: Double): String = if (!value.isFinite()) "Unavailable" else BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString()
fun bodyLocalDate(now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(Date(now))

/** UTC is only a calendar arithmetic device here; date-only records never become timestamps. */
private fun calendarDay(date: String): GregorianCalendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
    gregorianChange = Date(Long.MIN_VALUE); clear(); set(date.take(4).toInt(), date.substring(5, 7).toInt() - 1, date.takeLast(2).toInt())
}
fun shiftBodyDay(date: String, days: Int): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
    .format(calendarDay(date).apply { add(Calendar.DAY_OF_MONTH, days) }.time)
private fun daysBetween(a: String, b: String) = (calendarDay(b).timeInMillis - calendarDay(a).timeInMillis) / 86_400_000
fun isWeightName(name: String) = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") in setOf("bodyweight", "body weight", "weight")
data class WeightReading(val record: BodyMeasurement, val kg: Double)
fun weightReadings(records: List<BodyMeasurement>): List<WeightReading> = records.mapNotNull { record ->
    if (!isWeightName(record.name) || !validHistoryDate(record.date)) null else weightKg(record.value, record.unit)?.let { WeightReading(record, it) }
}.sortedWith(compareBy({ it.record.date }, { it.record.time }, { it.record.id }))
/** Prefer recorded clock times when all readings on the latest day have them.
 * Otherwise use known local creation order; never invent an unknown measurement time. */
fun latestWeight(readings: List<WeightReading>): WeightReading? {
    val date = readings.maxOfOrNull { it.record.date } ?: return null
    val day = readings.filter { it.record.date == date }
    return if (day.all { it.record.time.isNotEmpty() }) day.maxWithOrNull(compareBy({ it.record.time }, { it.record.createdAt }, { it.record.id }))
    else day.maxWithOrNull(compareBy({ it.record.createdAt }, { it.record.time }, { it.record.id }))
}
fun dailyWeightMeans(readings: List<WeightReading>): Map<String, Double> = readings.groupBy { it.record.date }.toSortedMap().mapValues { (_, day) ->
    decimalMean(day.map { it.kg })
}
// Decimal arithmetic avoids binary subtraction errors at displayed half-tenth boundaries.
private fun decimalMean(values: Collection<Double>): Double = values.fold(BigDecimal.ZERO) { sum, value -> sum + BigDecimal.valueOf(value) }
    .divide(BigDecimal.valueOf(values.size.toLong()), MathContext.DECIMAL128).toDouble()
data class WeightWindow(val from: String, val to: String, val meanKg: Double, val measuredDays: Int)
data class WeightComparison(val recent: WeightWindow, val previous: WeightWindow) {
    fun text(unit: String): String {
        val kgChange = BigDecimal.valueOf(recent.meanKg).subtract(BigDecimal.valueOf(previous.meanKg))
        val change = if (unit == "lb") kgChange.divide(BigDecimal("0.45359237"), MathContext.DECIMAL128) else kgChange
        if (change.abs() > BigDecimal.valueOf(Double.MAX_VALUE)) return "Difference is too large to display in $unit"
        val rounded = change.setScale(1, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) return "About the same as the previous four weeks"
        return "${rounded.abs().toPlainString()} $unit ${if (change.signum() < 0) "lower" else "higher"} than the previous four weeks"
    }
}
fun fourWeekComparison(readings: List<WeightReading>, today: String): WeightComparison? {
    if (!validHistoryDate(today)) return null
    val means = dailyWeightMeans(readings)
    fun window(from: String, to: String): WeightWindow? {
        val days = means.filterKeys { it in from..to }
        if (days.size < 3 || daysBetween(days.keys.min(), days.keys.max()) < 7) return null
        val mean = decimalMean(days.values)
        return WeightWindow(from, to, mean, days.size).takeIf { mean.isFinite() && mean > 0 }
    }
    val recent = window(shiftBodyDay(today, -27), today) ?: return null
    val previous = window(shiftBodyDay(today, -55), shiftBodyDay(today, -28)) ?: return null
    return WeightComparison(recent, previous)
}
fun validateBodyProfile(profile: BodyProfile, today: String = bodyLocalDate()) {
    require(profile.id == 1 && profile.weightUnit in setOf("kg", "lb") && profile.heightUnit in setOf("cm", "ft-in") && profile.chartRange in setOf("1M", "3M", "1Y", "All")) { "Invalid Body preferences." }
    require(profile.heightCm == null || (profile.heightCm.isFinite() && profile.heightCm > 0)) { "Height must be positive and finite." }
    require(profile.birthDate == null || (validHistoryDate(profile.birthDate) && profile.birthDate <= today)) { "Birth date must be a valid date, not in the future." }
}
