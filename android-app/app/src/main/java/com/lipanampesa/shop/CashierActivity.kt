package com.lipanampesa.shop

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

data class CartLine(val item: Item, var qty: Int) {
    fun lineTotal(): Double = item.unitPrice * qty * (1 + item.taxRate)
}

/**
 * Cashier / sales-rep screen (v1.1 — multi-item cart):
 * pick items (dropdown or barcode scan) -> ADD TO CART -> enter customer phone
 * -> PROMPT PAYMENT sends ONE STK Push for the whole bill -> polls until paid,
 * then shows a shareable receipt with the M-Pesa confirmation number.
 */
class CashierActivity : AppCompatActivity() {

    private lateinit var api: ApiService
    private lateinit var session: Session
    private var items: List<Item> = emptyList()
    private var selected: Item? = null
    private val cart = mutableListOf<CartLine>()

    private lateinit var actItem: AutoCompleteTextView
    private lateinit var etQuantity: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var tvUnitPrice: TextView
    private lateinit var tvTotal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDay: TextView
    private lateinit var listCart: ListView
    private lateinit var btnPrompt: MaterialButton
    private lateinit var btnAddToCart: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var cartAdapter: ArrayAdapter<String>

    private val handler = Handler(Looper.getMainLooper())

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { lookupBarcode(it) }
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
            else Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cashier)
        session = Session(this)
        api = ApiClient.service(this)
        session.shopName?.let { supportActionBar?.subtitle = it }

        actItem = findViewById(R.id.actItem)
        etQuantity = findViewById(R.id.etQuantity)
        etPhone = findViewById(R.id.etPhone)
        tvUnitPrice = findViewById(R.id.tvUnitPrice)
        tvTotal = findViewById(R.id.tvTotal)
        tvStatus = findViewById(R.id.tvStatus)
        tvDay = findViewById(R.id.tvDay)
        listCart = findViewById(R.id.listCart)
        btnPrompt = findViewById(R.id.btnPrompt)
        btnAddToCart = findViewById(R.id.btnAddToCart)
        progress = findViewById(R.id.progress)

        cartAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listCart.adapter = cartAdapter
        listCart.setOnItemClickListener { _, _, position, _ -> confirmRemoveLine(position) }

        actItem.setOnItemClickListener { parent, _, position, _ ->
            selected = parent.getItemAtPosition(position) as Item
            refreshPrices()
        }
        etQuantity.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshPrices()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnAddToCart.setOnClickListener { addToCart() }
        btnPrompt.setOnClickListener { doPrompt() }

        refreshPrices()
        loadItems()
    }

    override fun onResume() {
        super.onResume()
        refreshDaySummary()
    }

    /* ---------------- catalog (with offline cache) ---------------- */

    private fun loadItems() {
        api.items().go({ list ->
            items = list
            session.cachedItemsJson = Gson().toJson(list)
            bindItems()
        }, { _ ->
            // Fall back to the last downloaded catalog so selling can continue briefly
            val cached = session.cachedItemsJson
            if (!cached.isNullOrEmpty()) {
                val type = object : TypeToken<List<Item>>() {}.type
                items = Gson().fromJson(cached, type)
                bindItems()
                toast(getString(R.string.offline_catalog))
            }
        })
    }

    private fun bindItems() {
        actItem.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))
    }

    private fun lookupBarcode(code: String) {
        api.itemByBarcode(code).go({ item ->
            selected = item
            actItem.setText(item.name, false)
            etQuantity.setText("1")
            addToCart() // scanned items go straight into the cart
        }, { toast(getString(R.string.no_item_barcode, code)) })
    }

    private fun refreshDaySummary() {
        api.summary().go({ s ->
            tvDay.text = getString(
                R.string.today_summary,
                (s.successCount ?: 0.0).toInt(),
                money(s.successAmount ?: 0.0, "KES"),
                (s.pendingCount ?: 0.0).toInt()
            )
        }, { /* nice-to-have */ })
    }

    /* ---------------- cart ---------------- */

    private fun qty(): Int = etQuantity.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0

    private fun addToCart() {
        val sel = selected ?: run { toast(getString(R.string.pick_item_first)); return }
        val q = qty().coerceAtLeast(1)
        val existing = cart.find { it.item.id == sel.id }
        if (existing != null) existing.qty += q else cart.add(CartLine(sel, q))
        toast(getString(R.string.added_to_cart, "${sel.name} × $q"))
        refreshCart()
    }

    private fun confirmRemoveLine(position: Int) {
        val line = cart.getOrNull(position) ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.remove_line, "${line.item.name} × ${line.qty}"))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                cart.removeAt(position)
                refreshCart()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshCart() {
        cartAdapter.clear()
        cartAdapter.addAll(cart.map { "${it.qty} × ${it.item.name} — ${money(it.lineTotal(), it.item.currency)}" })
        refreshPrices()
    }

    private fun refreshPrices() {
        val sel = selected
        tvUnitPrice.text = sel?.let { getString(R.string.unit_price_fmt, money(it.unitPrice, it.currency)) } ?: ""
        val total = cart.sumOf { it.lineTotal() }
        val currency = cart.firstOrNull()?.item?.currency ?: session.currency
        tvTotal.text = money(total, currency)
    }

    /* ---------------- STK push flow ---------------- */

    private fun doPrompt() {
        // Quick-sale convenience: item selected but never added -> add it automatically.
        if (cart.isEmpty() && selected != null) addToCart()
        if (cart.isEmpty()) { toast(getString(R.string.cart_empty)); return }
        val phone = etPhone.text.toString().trim()
        if (phone.isEmpty()) { toast(getString(R.string.enter_phone)); return }

        val lines = cart.map { PromptItem(it.item.id, it.qty) }
        val ref = cart.first().item.name.take(12)

        setBusy(true)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
        tvStatus.text = getString(R.string.sending)

        api.prompt(PromptRequest(lines, phone, ref)).go({ resp ->
            setBusy(false)
            tvStatus.text = resp.message ?: getString(R.string.waiting_customer)
            pollStatus(resp.checkoutRequestId)
        }, { err ->
            setBusy(false)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_failed))
            tvStatus.text = err
        })
    }

    private fun pollStatus(checkout: String) {
        val task = object : Runnable {
            var attempts = 0
            override fun run() {
                api.status(checkout).go({ t ->
                    when (t.status) {
                        "SUCCESS" -> onPaid(t)
                        "FAILED", "TIMEOUT" -> onFailed(t)
                        else -> retry(this)
                    }
                }, { retry(this) })
            }

            fun retry(r: Runnable) {
                if (++attempts < 50) handler.postDelayed(r, 3000)
            }
        }
        handler.postDelayed(task, 3000)
    }

    private fun receiptText(t: TxnStatus): String {
        val sb = StringBuilder()
        sb.appendLine(getString(R.string.payment_success))
        sb.appendLine(getString(R.string.app_name))
        sb.appendLine("--------------------------------")
        val lines = t.items ?: emptyList()
        if (lines.isNotEmpty()) {
            lines.forEach { sb.appendLine("${it.quantity} × ${it.name} — ${money(it.lineTotal, t.currency)}") }
        } else {
            sb.appendLine("${t.itemName} × ${t.quantity}")
        }
        sb.appendLine("--------------------------------")
        if (t.taxAmount > 0) sb.appendLine("${getString(R.string.tax)}: ${money(t.taxAmount, t.currency)}")
        sb.appendLine("${getString(R.string.total_amount)}: ${money(t.amount, t.currency)}")
        sb.appendLine(getString(R.string.receipt_fmt, t.mpesaReceipt ?: "—"))
        sb.appendLine("${t.customerPhone ?: ""}")
        return sb.toString()
    }

    private fun onPaid(t: TxnStatus) {
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
        tvStatus.text = "${getString(R.string.payment_success)}\n${getString(R.string.receipt_fmt, t.mpesaReceipt ?: "—")}"
        refreshDaySummary()
        AlertDialog.Builder(this)
            .setTitle(R.string.payment_success)
            .setMessage(receiptText(t))
            .setPositiveButton(R.string.new_sale) { _, _ -> resetForm() }
            .setNeutralButton(R.string.share_receipt) { _, _ -> shareReceipt(t) }
            .setCancelable(false)
            .show()
    }

    private fun shareReceipt(t: TxnStatus) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, receiptText(t))
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
        resetForm()
    }

    private fun onFailed(t: TxnStatus) {
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_failed))
        tvStatus.text = "${getString(R.string.payment_failed)}\n${t.resultDesc ?: ""}"
    }

    private fun resetForm() {
        selected = null
        cart.clear()
        cartAdapter.clear()
        actItem.setText("", false)
        etQuantity.setText("1")
        etPhone.setText("")
        tvStatus.text = ""
        refreshPrices()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnPrompt.isEnabled = !busy
    }

    /* ---------------- menu: scan / language / currency / txns / logout ---------------- */

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.cashier_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_scan -> { cameraPermission.launch(Manifest.permission.CAMERA); true }
        R.id.action_transactions -> { startActivity(Intent(this, TransactionsActivity::class.java)); true }
        R.id.action_language -> { showLanguagePicker(); true }
        R.id.action_currency -> { showCurrencyPicker(); true }
        R.id.action_logout -> {
            Session(this).clearAuth()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun launchScanner() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(true)
        )
    }

    private fun showLanguagePicker() {
        val langs = arrayOf("English", "Kiswahili")
        AlertDialog.Builder(this)
            .setTitle(R.string.language)
            .setItems(langs) { _, which ->
                val tag = if (which == 1) "sw" else "en"
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            }
            .show()
    }

    private fun showCurrencyPicker() {
        val codes = arrayOf("KES", "USD", "EUR", "GBP", "TZS", "UGX", "RWF")
        AlertDialog.Builder(this)
            .setTitle(R.string.currency)
            .setItems(codes) { _, which ->
                session.currency = codes[which]
                refreshPrices()
                toast(codes[which])
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
