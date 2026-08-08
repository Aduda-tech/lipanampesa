package com.lipanampesa.shop

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class KioskTxn(
    val id: Long,
    val itemName: String,
    val quantity: Int,
    val amount: Double,
    val phone: String,
    val status: String,          // SUCCESS | FAILED
    val description: String,
    val checkoutId: String,
    val createdAt: String
)

/** On-device storage for KIOSK MODE: catalog, Daraja keys and transaction log. */
object LocalStore {
    private const val PREFS = "kiosk"
    private val gson = Gson()

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /* ---------------- catalog ---------------- */

    private val seed = listOf(
        Item(1, "A4 Ruled Notebook (200 pages)", "Stationery", null, 150.0, "KES", null),
        Item(2, "USB-C Fast Charger 25W", "Phone Accessories", null, 1500.0, "KES", null),
        Item(3, "Wired Earphones 3.5mm", "Phone Accessories", null, 350.0, "KES", null),
        Item(4, "AA Batteries (4-pack)", "Batteries", null, 320.0, "KES", null),
        Item(5, "Windows Installation & Setup", "Services", null, 1000.0, "KES", null),
        Item(6, "Printer Service & Repair", "Services", null, 1500.0, "KES", null)
    )

    fun items(ctx: Context): MutableList<Item> {
        val raw = p(ctx).getString("items", null) ?: run {
            saveItems(ctx, seed.toMutableList()); return seed.toMutableList()
        }
        val type = object : TypeToken<MutableList<Item>>() {}.type
        return gson.fromJson(raw, type)
    }

    fun saveItems(ctx: Context, list: MutableList<Item>) {
        p(ctx).edit().putString("items", gson.toJson(list)).apply()
    }

    fun nextItemId(ctx: Context): Int = (items(ctx).maxOfOrNull { it.id } ?: 0) + 1

    /* ---------------- transactions ---------------- */

    fun txns(ctx: Context): MutableList<KioskTxn> {
        val raw = p(ctx).getString("txns", null) ?: return mutableListOf()
        val type = object : TypeToken<MutableList<KioskTxn>>() {}.type
        return gson.fromJson(raw, type)
    }

    fun addTxn(ctx: Context, txn: KioskTxn) {
        val list = txns(ctx)
        list.add(0, txn)
        while (list.size > 200) list.removeAt(list.size - 1)   // keep device log bounded
        p(ctx).edit().putString("txns", gson.toJson(list)).apply()
    }

    /* ---------------- Daraja keys (device-only) ---------------- */

    fun darajaKeys(ctx: Context): DarajaDirect.Keys? {
        val pr = p(ctx)
        val key = pr.getString("d_key", "") ?: ""
        val secret = pr.getString("d_secret", "") ?: ""
        val shortcode = pr.getString("d_shortcode", "") ?: ""
        val passkey = pr.getString("d_passkey", "") ?: ""
        if (key.isEmpty() || secret.isEmpty() || shortcode.isEmpty() || passkey.isEmpty()) return null
        val biz = pr.getString("d_biz", "paybill") ?: "paybill"
        val store = pr.getString("d_store", "") ?: ""
        val type = if (biz == "paybill") "CustomerPayBillOnline" else "CustomerBuyGoodsOnline"
        val partyB = if (biz == "till_store" && store.isNotEmpty()) store else shortcode
        return DarajaDirect.Keys(
            env = pr.getString("d_env", "sandbox") ?: "sandbox",
            consumerKey = key,
            consumerSecret = secret,
            shortcode = shortcode,
            passkey = passkey,
            transactionType = type,
            partyB = partyB,
            accountRef = (pr.getString("d_account_ref", "") ?: "").ifEmpty { "Shop" }
        )
    }

    fun saveDaraja(
        ctx: Context, env: String, key: String, secret: String, shortcode: String,
        passkey: String, biz: String, store: String, accountRef: String
    ) {
        p(ctx).edit()
            .putString("d_env", env)
            .putString("d_key", key)
            .putString("d_secret", secret)
            .putString("d_shortcode", shortcode)
            .putString("d_passkey", passkey)
            .putString("d_biz", biz)
            .putString("d_store", store)
            .putString("d_account_ref", accountRef)
            .apply()
    }

    fun hasKeys(ctx: Context): Boolean = darajaKeys(ctx) != null
}
