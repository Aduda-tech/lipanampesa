package com.lipanampesa.shop

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Direct Daraja client used by KIOSK MODE (single-device, no server).
 * Talks straight to Safaricom: https://sandbox.safaricom.co.ke | https://api.safaricom.co.ke
 *
 * Keys live ONLY on this device (onboarding shop owner accepts that risk).
 * Payment confirmation is done with STK Query polling because there is no callback URL
 * (a callback needs a public server — that is the server mode).
 */
object DarajaDirect {

    data class Keys(
        val env: String,            // "sandbox" | "production"
        val consumerKey: String,
        val consumerSecret: String,
        val shortcode: String,
        val passkey: String,
        val transactionType: String, // CustomerPayBillOnline | CustomerBuyGoodsOnline
        val partyB: String,          // = shortcode, or STORE NUMBER for store-based tills
        val accountRef: String
    )

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val message: String) : Result<Nothing>()
    }

    enum class QueryState { PENDING, SUCCESS, FAILED }

    data class QueryOutcome(val state: QueryState, val description: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private fun base(env: String) =
        if (env == "production") "https://api.safaricom.co.ke" else "https://sandbox.safaricom.co.ke"

    fun normalizePhone(input: String): String? {
        var p = input.replace(Regex("[^\\d+]"), "")
        if (p.startsWith("+")) p = p.substring(1)
        if (p.startsWith("0")) p = "254" + p.substring(1)
        if (p.length == 9 && (p.startsWith("7") || p.startsWith("1"))) p = "254$p"
        return if (Regex("^254[71]\\d{8}$").matches(p)) p else null
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date())

    private fun password(keys: Keys, ts: String): String =
        Base64.encodeToString("${keys.shortcode}${keys.passkey}$ts".toByteArray(), Base64.NO_WRAP)

    /** Executes a request synchronously on the calling thread — always call from a background thread. */
    private fun execute(request: Request): JSONObject {
        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string() ?: "{}"
            return try { JSONObject(raw) } catch (e: Exception) { JSONObject().put("httpError", resp.code) }
        }
    }

    fun accessToken(keys: Keys): Result<String> {
        return try {
            val auth = Base64.encodeToString(
                "${keys.consumerKey}:${keys.consumerSecret}".toByteArray(), Base64.NO_WRAP
            )
            val req = Request.Builder()
                .url("${base(keys.env)}/oauth/v1/generate?grant_type=client_credentials")
                .header("Authorization", "Basic $auth")
                .get()
                .build()
            val json = execute(req)
            val token = json.optString("access_token")
            if (token.isNotEmpty()) Result.Ok(token)
            else Result.Err("Daraja OAuth failed: $json")
        } catch (e: Exception) {
            Result.Err("Network/OAuth error: ${e.message}")
        }
    }

    fun stkPush(
        keys: Keys,
        phone: String,
        amount: Int,
        desc: String,
        callback: (Result<String>) -> Unit   // Ok(checkoutRequestId) | Err(message)
    ) {
        Thread {
            val result = try {
                when (val t = accessToken(keys)) {
                    is Result.Err -> t
                    is Result.Ok -> {
                        val ts = timestamp()
                        val body = JSONObject().apply {
                            put("BusinessShortCode", keys.shortcode)
                            put("Password", password(keys, ts))
                            put("Timestamp", ts)
                            put("TransactionType", keys.transactionType)
                            put("Amount", amount.coerceAtLeast(1))
                            put("PartyA", phone)
                            put("PartyB", keys.partyB)
                            put("PhoneNumber", phone)
                            // STK requires an https callback even though kiosk mode ignores it
                            // (confirmation comes from STK Query polling instead).
                            put("CallBackURL", "https://kiosk.invalid/callback")
                            put("AccountReference", keys.accountRef.take(12))
                            put("TransactionDesc", desc.take(13))
                        }
                        val req = Request.Builder()
                            .url("${base(keys.env)}/mpesa/stkpush/v1/processrequest")
                            .header("Authorization", "Bearer ${t.value}")
                            .post(body.toString().toRequestBody(JSON))
                            .build()
                        val json = execute(req)
                        if (json.optString("ResponseCode") == "0") {
                            Result.Ok(json.optString("CheckoutRequestID"))
                        } else {
                            Result.Err("STK rejected: $json")
                        }
                    }
                }
            } catch (e: Exception) {
                Result.Err("STK error: ${e.message}")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(result) }
        }.start()
    }

    fun stkQuery(keys: Keys, checkoutRequestId: String, callback: (QueryOutcome) -> Unit) {
        Thread {
            val outcome = try {
                when (val t = accessToken(keys)) {
                    is Result.Err -> QueryOutcome(QueryState.FAILED, t.message)
                    is Result.Ok -> {
                        val ts = timestamp()
                        val body = JSONObject().apply {
                            put("BusinessShortCode", keys.shortcode)
                            put("Password", password(keys, ts))
                            put("Timestamp", ts)
                            put("CheckoutRequestID", checkoutRequestId)
                        }
                        val req = Request.Builder()
                            .url("${base(keys.env)}/mpesa/stkpushquery/v1/query")
                            .header("Authorization", "Bearer ${t.value}")
                            .post(body.toString().toRequestBody(JSON))
                            .build()
                        val json = execute(req)
                        val resultCode = json.optString("ResultCode")
                        val desc = json.optString("ResultDesc")
                        val err = json.optString("errorMessage")
                        when {
                            resultCode == "0" ->
                                QueryOutcome(QueryState.SUCCESS, desc)
                            err.contains("processed", ignoreCase = true) ->
                                QueryOutcome(QueryState.PENDING, "Customer hasn't responded yet…")
                            resultCode.isNotEmpty() ->
                                QueryOutcome(QueryState.FAILED, if (desc.isNotEmpty()) desc else err)
                            else ->
                                QueryOutcome(QueryState.PENDING, err.ifEmpty { "Waiting…" })
                        }
                    }
                }
            } catch (e: Exception) {
                QueryOutcome(QueryState.PENDING, "Retrying… (${e.message})")
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post { callback(outcome) }
        }.start()
    }
}
