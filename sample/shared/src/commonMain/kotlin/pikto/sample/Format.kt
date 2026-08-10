package pikto.sample

/** "1:07", "12:04", "1:02:33". Never "01:07", because nothing shows a leading zero on minutes. */
fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    val paddedSeconds = if (seconds < 10) "0$seconds" else "$seconds"
    if (hours == 0L) return "$minutes:$paddedSeconds"
    val paddedMinutes = if (minutes < 10) "0$minutes" else "$minutes"
    return "$hours:$paddedMinutes:$paddedSeconds"
}

/** Decimal units, the way both platforms report storage to users. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1_000) return "$bytes B"
    val units = listOf("kB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1_000
    var unit = 0
    while (value >= 1_000 && unit < units.lastIndex) {
        value /= 1_000
        unit++
    }
    return "${formatOneDecimal(value)} ${units[unit]}"
}

/** `toString()` on a Double is unbounded, and there is no common `String.format`. */
private fun formatOneDecimal(value: Double): String {
    val scaled = ((value * 10) + 0.5).toLong()
    val whole = scaled / 10
    val tenths = scaled % 10
    return if (tenths == 0L) "$whole" else "$whole.$tenths"
}
