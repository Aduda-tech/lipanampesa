package com.lipanampesa.shop

import com.google.gson.annotations.SerializedName

data class LoginRequest(val username: String, val password: String)

data class User(
    val username: String,
    val role: String,
    @SerializedName("display_name") val displayName: String?
)

data class ShopRef(val id: Int, val name: String, val mode: String?)

data class ShopSettings(
    val id: Int,
    val name: String,
    @SerializedName("use_mock") val useMock: Boolean,
    @SerializedName("daraja_env") val darajaEnv: String?,
    @SerializedName("business_type") val businessType: String?,
    val shortcode: String?,
    @SerializedName("store_number") val storeNumber: String?,
    @SerializedName("operator_name") val operatorName: String?,
    @SerializedName("account_ref") val accountRef: String?,
    @SerializedName("consumer_key") val consumerKey: String?,
    @SerializedName("consumer_secret") val consumerSecret: String?,
    val passkey: String?,
    @SerializedName("charge_target") val chargeTarget: String?,
    @SerializedName("callback_url") val callbackUrl: String?
)

data class LoginResponse(val token: String, val user: User, val shop: ShopRef?)

data class Item(
    val id: Int,
    val name: String,
    val category: String?,
    val barcode: String?,
    @SerializedName("unit_price") val unitPrice: Double,
    val currency: String = "KES",
    val description: String?,
    @SerializedName("tax_rate") val taxRate: Double = 0.0,
    val active: Int = 1
) {
    override fun toString(): String = name
}

data class ItemBody(
    val name: String,
    val category: String?,
    val barcode: String?,
    @SerializedName("unit_price") val unitPrice: Double,
    val currency: String? = "KES",
    val description: String?,
    @SerializedName("tax_rate") val taxRate: Double = 0.0
)

data class PromptItem(
    @SerializedName("item_id") val itemId: Int,
    val quantity: Int
)

data class PromptRequest(
    val items: List<PromptItem>,
    @SerializedName("customer_phone") val customerPhone: String,
    @SerializedName("account_reference") val accountReference: String? = null
)

data class PromptResponse(
    @SerializedName("transaction_id") val transactionId: Int,
    @SerializedName("checkout_request_id") val checkoutRequestId: String,
    val status: String,
    val amount: Double,
    @SerializedName("tax_amount") val taxAmount: Double = 0.0,
    val currency: String,
    val mode: String,
    val message: String?
)

data class CartLineItem(
    val name: String,
    val quantity: Int,
    @SerializedName("unit_price") val unitPrice: Double,
    @SerializedName("line_total") val lineTotal: Double
)

data class TxnStatus(
    val id: Int,
    val status: String,
    @SerializedName("mpesa_receipt") val mpesaReceipt: String?,
    @SerializedName("result_desc") val resultDesc: String?,
    @SerializedName("item_name") val itemName: String?,
    val quantity: Int,
    val amount: Double,
    @SerializedName("tax_amount") val taxAmount: Double = 0.0,
    val currency: String,
    val items: List<CartLineItem>?,
    @SerializedName("customer_phone") val customerPhone: String?,
    @SerializedName("created_at") val createdAt: String?
)

data class Txn(
    val id: Int,
    @SerializedName("item_name") val itemName: String,
    val quantity: Int,
    val amount: Double,
    @SerializedName("tax_amount") val taxAmount: Double = 0.0,
    val currency: String,
    @SerializedName("customer_phone") val customerPhone: String,
    @SerializedName("mpesa_receipt") val mpesaReceipt: String?,
    val status: String,
    @SerializedName("result_desc") val resultDesc: String?,
    @SerializedName("cashier_username") val cashierUsername: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("items_json") val itemsJson: String?
)

data class ImportResult(
    val ok: Boolean,
    val inserted: Int,
    val updated: Int,
    val skipped: Int,
    @SerializedName("total_rows") val totalRows: Int
)

data class Summary(
    val date: String,
    @SerializedName("success_count") val successCount: Double?,
    @SerializedName("success_amount") val successAmount: Double?,
    @SerializedName("pending_count") val pendingCount: Double?
)
