package com.lipanampesa.shop

import org.json.JSONArray
import java.text.NumberFormat
import java.util.Locale

private val SYMBOLS = mapOf(
    "KES" to "KES ",
    "USD" to "$",
    "EUR" to "€",
    "GBP" to "£",
    "TZS" to "TSh ",
    "UGX" to "USh ",
    "RWF" to "FRw "
)

/** Formats an amount with the three-letter currency's display symbol. */
fun money(amount: Double, currency: String): String {
    val nf = NumberFormat.getInstance(Locale.US)
    nf.minimumFractionDigits = 2
    nf.maximumFractionDigits = 2
    val prefix = SYMBOLS[currency] ?: "$currency "
    return prefix + nf.format(amount)
}

/** Parses a transaction's stored cart snapshot into "Name × qty — line total" strings. */
fun itemLines(itemsJson: String?, currency: String): List<String> {
    if (itemsJson.isNullOrEmpty()) return emptyList()
    return try {
        val arr = JSONArray(itemsJson)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val name = o.optString("name")
            val qty = o.optInt("quantity", 1)
            val lineTotal = o.optDouble("line_total")
            "$name × $qty — ${money(lineTotal, currency)}"
        }
    } catch (e: Exception) {
        emptyList()
    }
}
