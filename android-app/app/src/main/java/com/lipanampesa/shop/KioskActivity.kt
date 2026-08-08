package com.lipanampesa.shop

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KIOSK MODE — completely server-free, single-device selling.
 * The phone calls Daraja directly (keys stored ONLY on this device), catalog and
 * transaction log live on-device. No backend, no hosting, works over Safaricom data.
 * Limitation: no callback URL exists in this mode, so the M-Pesa receipt number is
 * NOT captured automatically — see docs/KIOSK_MODE.md.
 */
class KioskActivity : AppCompatActivity() {

    private lateinit var actItem: AutoCompleteTextView
    private lateinit var etQuantity: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var tvTotal: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnPrompt: MaterialButton
    private lateinit var progress: ProgressBar

    private var items: MutableList<Item> = mutableListOf()
    private var selected: Item? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_kiosk)
        supportActionBar?.subtitle = getString(R.string.kiosk_mode)

        actItem = findViewById(R.id.actItem)
        etQuantity = findViewById(R.id.etQuantity)
        etPhone = findViewById(R.id.etPhone)
        tvTotal = findViewById(R.id.tvTotal)
        tvStatus = findViewById(R.id.tvStatus)
        btnPrompt = findViewById(R.id.btnPrompt)
        progress = findViewById(R.id.progress)

        findViewById<MaterialButton>(R.id.btnAddItem).setOnClickListener { showAddItemDialog() }
        findViewById<MaterialButton>(R.id.btnHistory).setOnClickListener { showHistory() }
        findViewById<MaterialButton>(R.id.btnKioskSettings).setOnClickListener { showSettings() }

        actItem.setOnItemClickListener { parent, _, position, _ ->
            selected = parent.getItemAtPosition(position) as Item
            updateTotal()
        }
        etQuantity.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = updateTotal()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnPrompt.setOnClickListener { doPrompt() }
        updateTotal()
    }

    override fun onResume() {
        super.onResume()
        reloadItems()
        if (!LocalStore.hasKeys(this)) {
            toast(getString(R.string.kiosk_no_keys))
        }
    }

    private fun reloadItems() {
        items = LocalStore.items(this)
        actItem.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, items))
    }

    private fun qty(): Int = etQuantity.text.toString().toIntOrNull()?.coerceAtLeast(1) ?: 1

    private fun updateTotal() {
        val sel = selected
        tvTotal.text = money((sel?.unitPrice ?: 0.0) * qty(), sel?.currency ?: "KES")
    }

    /* ---------------- direct STK push ---------------- */

    private fun doPrompt() {
        val sel = selected ?: run { toast(getString(R.string.pick_item_first)); return }
        val keys = LocalStore.darajaKeys(this) ?: run {
            toast(getString(R.string.kiosk_no_keys))
            showSettings()
            return
        }
        val phone = DarajaDirect.normalizePhone(etPhone.text.toString()) ?: run {
            toast(getString(R.string.enter_phone)); return
        }
        val amount = Math.round(sel.unitPrice * qty())
        val label = "${sel.name} × ${qty()}"

        setBusy(true)
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_pending))
        tvStatus.text = getString(R.string.sending)

        DarajaDirect.stkPush(keys, phone, amount.toInt(), sel.name) { result ->
            when (result) {
                is DarajaDirect.Result.Err -> {
                    setBusy(false)
                    tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_failed))
                    tvStatus.text = result.message
                }
                is DarajaDirect.Result.Ok -> {
                    tvStatus.text = getString(R.string.kiosk_waiting_query)
                    pollQuery(keys, result.value, label, phone, sel.unitPrice * qty())
                }
            }
        }
    }

    private fun pollQuery(
        keys: DarajaDirect.Keys,
        checkoutId: String,
        label: String,
        phone: String,
        amount: Double
    ) {
        var attempts = 0
        val task = object : Runnable {
            override fun run() {
                DarajaDirect.stkQuery(keys, checkoutId) { outcome ->
                    when (outcome.state) {
                        DarajaDirect.QueryState.SUCCESS -> {
                            setBusy(false)
                            onPaid(label, phone, checkoutId, amount, outcome.description)
                        }
                        DarajaDirect.QueryState.FAILED -> {
                            setBusy(false)
                            tvStatus.setTextColor(ContextCompat.getColor(this@KioskActivity, R.color.status_failed))
                            tvStatus.text = "${getString(R.string.payment_failed)}\n${outcome.description}"
                            saveTxn(label, phone, checkoutId, amount, "FAILED", outcome.description)
                        }
                        DarajaDirect.QueryState.PENDING -> {
                            tvStatus.text = outcome.description
                            if (++attempts < 18) handler.postDelayed(this, 5000) else {
                                setBusy(false)
                                tvStatus.text = getString(R.string.kiosk_timeout_check)
                            }
                        }
                    }
                }
            }
        }
        handler.postDelayed(task, 5000)
    }

    private fun onPaid(label: String, phone: String, checkoutId: String, amount: Double, desc: String) {
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_success))
        tvStatus.text = getString(R.string.payment_success)
        saveTxn(label, phone, checkoutId, amount, "SUCCESS", desc)
        val text = "${getString(R.string.payment_success)}\n$label\n${money(amount, "KES")}\n$phone\n\n" +
            getString(R.string.kiosk_receipt_note)
        AlertDialog.Builder(this)
            .setTitle(R.string.payment_success)
            .setMessage(text)
            .setPositiveButton(R.string.new_sale) { _, _ -> resetForm() }
            .setNeutralButton(R.string.share_receipt) { _, _ ->
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        },
                        getString(R.string.share_via)
                    )
                )
                resetForm()
            }
            .setCancelable(false)
            .show()
    }

    private fun saveTxn(label: String, phone: String, checkoutId: String, amount: Double, status: String, desc: String) {
        LocalStore.addTxn(
            this,
            KioskTxn(
                id = System.currentTimeMillis(),
                itemName = label,
                quantity = qty(),
                amount = amount,
                phone = phone,
                status = status,
                description = desc,
                checkoutId = checkoutId,
                createdAt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            )
        )
    }

    /* ---------------- dialogs: add item / history / settings ---------------- */

    private fun showAddItemDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_item, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etItemName)
        val etCategory = view.findViewById<TextInputEditText>(R.id.etItemCategory)
        val etBarcode = view.findViewById<TextInputEditText>(R.id.etItemBarcode)
        val etPrice = view.findViewById<TextInputEditText>(R.id.etItemPrice)
        view.findViewById<View>(R.id.etTaxRate).visibility = View.GONE
        view.findViewById<TextInputEditText>(R.id.etItemDescription).visibility = View.GONE

        AlertDialog.Builder(this)
            .setTitle(R.string.add_item)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                val price = etPrice.text.toString().toDoubleOrNull()
                if (name.isEmpty() || price == null || price < 0) return@setPositiveButton
                items.add(
                    Item(
                        id = LocalStore.nextItemId(this),
                        name = name,
                        category = etCategory.text.toString().trim().ifEmpty { null },
                        barcode = etBarcode.text.toString().trim().ifEmpty { null },
                        unitPrice = price,
                        currency = "KES",
                        description = null
                    )
                )
                LocalStore.saveItems(this, items)
                reloadItems()
                toast(getString(R.string.added_to_cart, name))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showHistory() {
        val txns = LocalStore.txns(this)
        if (txns.isEmpty()) { toast(getString(R.string.transactions) + ": 0"); return }
        val labels = txns.take(40).map {
            "${it.status} · ${it.itemName} · ${money(it.amount, "KES")} · ${it.createdAt}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.transactions)
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)) { d, w ->
                val t = txns[w]
                AlertDialog.Builder(this)
                    .setTitle("${t.status} · ${t.itemName}")
                    .setMessage(
                        "${money(t.amount, "KES")}\n${t.phone}\n${t.createdAt}\n\n${t.description}\n\nCheckout: ${t.checkoutId}"
                    )
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSettings() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_kiosk_settings, null)
        val rbPaybill = view.findViewById<RadioButton>(R.id.rbPaybill)
        val rbTill = view.findViewById<RadioButton>(R.id.rbTill)
        val rbTillStore = view.findViewById<RadioButton>(R.id.rbTillStore)
        val tilStore = view.findViewById<View>(R.id.tilStoreNumber)
        val etShortcode = view.findViewById<TextInputEditText>(R.id.etShortcode)
        val etStore = view.findViewById<TextInputEditText>(R.id.etStoreNumber)
        val etAccountRef = view.findViewById<TextInputEditText>(R.id.etAccountRef)
        val etKey = view.findViewById<TextInputEditText>(R.id.etConsumerKey)
        val etSecret = view.findViewById<TextInputEditText>(R.id.etConsumerSecret)
        val etPasskey = view.findViewById<TextInputEditText>(R.id.etPasskey)
        val rbSandbox = view.findViewById<RadioButton>(R.id.rbSandbox)
        val rbProduction = view.findViewById<RadioButton>(R.id.rbProduction)
        view.findViewById<RadioGroup>(R.id.rgBizType).setOnCheckedChangeListener { _, id ->
            tilStore.visibility = if (id == R.id.rbTillStore) View.VISIBLE else View.GONE
        }

        getSharedPreferences("kiosk", MODE_PRIVATE).apply {
            etShortcode.setText(getString("d_shortcode", ""))
            etStore.setText(getString("d_store", ""))
            etAccountRef.setText(getString("d_account_ref", ""))
            etKey.setText(getString("d_key", ""))
            etSecret.setText(getString("d_secret", ""))
            etPasskey.setText(getString("d_passkey", ""))
            if (getString("d_env", "sandbox") == "production") rbProduction.isChecked = true
            when (getString("d_biz", "paybill")) {
                "till" -> rbTill.isChecked = true
                "till_store" -> { rbTillStore.isChecked = true; tilStore.visibility = View.VISIBLE }
                else -> rbPaybill.isChecked = true
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.mpesa_settings)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val store = etStore.text.toString().trim()
                val biz = when {
                    rbTill.isChecked -> "till"
                    rbTillStore.isChecked -> "till_store"
                    else -> "paybill"
                }
                if (biz == "till_store" && store.isEmpty()) {
                    toast(getString(R.string.store_number) + " ?")
                    return@setPositiveButton
                }
                LocalStore.saveDaraja(
                    this,
                    if (rbProduction.isChecked) "production" else "sandbox",
                    etKey.text.toString().trim(),
                    etSecret.text.toString().trim(),
                    etShortcode.text.toString().trim(),
                    etPasskey.text.toString().trim(),
                    biz, store, etAccountRef.text.toString().trim()
                )
                toast(getString(R.string.settings_saved))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetForm() {
        selected = null
        actItem.setText("", false)
        etQuantity.setText("1")
        etPhone.setText("")
        tvStatus.text = ""
        updateTotal()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        btnPrompt.isEnabled = !busy
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
