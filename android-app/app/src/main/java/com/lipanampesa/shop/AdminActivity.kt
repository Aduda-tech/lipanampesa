package com.lipanampesa.shop

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Admin console: manage the catalog usable by ANY shop.
 * - Tap an item to edit (incl. price & tax adjustments — price changes audited server-side)
 * - Add new items manually
 * - Bulk-import items + prices from a CSV or Excel file (barcode match = update price)
 * - Create cashier/admin user accounts
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var api: ApiService
    private var items: List<Item> = emptyList()
    private lateinit var listView: ListView
    private lateinit var progress: ProgressBar

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { importFile(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)
        api = ApiClient.service(this)
        Session(this).shopName?.let { supportActionBar?.subtitle = it }
        listView = findViewById(R.id.listItems)
        progress = findViewById(R.id.progress)

        findViewById<MaterialButton>(R.id.btnAdd).setOnClickListener { showItemDialog(null) }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener { pickFile.launch("*/*") }
        findViewById<MaterialButton>(R.id.btnAddUser).setOnClickListener { showUserDialog() }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<MaterialButton>(R.id.btnTransactions).setOnClickListener {
            startActivity(Intent(this, TransactionsActivity::class.java))
        }
        listView.setOnItemClickListener { _, _, position, _ -> showItemDialog(items[position]) }
    }

    override fun onResume() {
        super.onResume()
        loadItems()
    }

    private fun loadItems() {
        progress.visibility = View.VISIBLE
        api.items().go({ list ->
            progress.visibility = View.GONE
            items = list
            val labels = list.map {
                val tax = if (it.taxRate > 0) " +${(it.taxRate * 100).toInt()}% tax" else ""
                "${it.name} — ${money(it.unitPrice, it.currency)}$tax${it.category?.let { c -> " ($c)" } ?: ""}"
            }
            listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        }, { err ->
            progress.visibility = View.GONE
            toast(err)
        })
    }

    /** Add or edit an item. On save calls POST /items or PUT /items/:id. */
    private fun showItemDialog(item: Item?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_item, null)
        val etName = view.findViewById<TextInputEditText>(R.id.etItemName)
        val etCategory = view.findViewById<TextInputEditText>(R.id.etItemCategory)
        val etBarcode = view.findViewById<TextInputEditText>(R.id.etItemBarcode)
        val etPrice = view.findViewById<TextInputEditText>(R.id.etItemPrice)
        val etTax = view.findViewById<TextInputEditText>(R.id.etTaxRate)
        val etDesc = view.findViewById<TextInputEditText>(R.id.etItemDescription)

        item?.let {
            etName.setText(it.name)
            etCategory.setText(it.category ?: "")
            etBarcode.setText(it.barcode ?: "")
            etPrice.setText(it.unitPrice.toString())
            etTax.setText(it.taxRate.toString())
            etDesc.setText(it.description ?: "")
        }

        AlertDialog.Builder(this)
            .setTitle(if (item == null) R.string.add_item else R.string.edit_item)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                val price = etPrice.text.toString().toDoubleOrNull()
                val tax = etTax.text.toString().toDoubleOrNull() ?: 0.0
                if (name.isEmpty() || price == null || price < 0 || tax < 0 || tax > 1) {
                    toast(getString(R.string.item_name) + " / " + getString(R.string.price) + " ?")
                    return@setPositiveButton
                }
                val body = ItemBody(
                    name = name,
                    category = etCategory.text.toString().trim().ifEmpty { null },
                    barcode = etBarcode.text.toString().trim().ifEmpty { null },
                    unitPrice = price,
                    currency = "KES",
                    description = etDesc.text.toString().trim().ifEmpty { null },
                    taxRate = tax
                )
                if (item == null) {
                    api.createItem(body).go({ loadItems() }, { toast(it) })
                } else {
                    api.updateItem(item.id, body).go({ loadItems() }, { toast(it) })
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Create a cashier or admin account (POST /api/auth/users). */
    private fun showUserDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_user, null)
        val etUsername = view.findViewById<TextInputEditText>(R.id.etNewUsername)
        val etPassword = view.findViewById<TextInputEditText>(R.id.etNewPassword)
        val rbAdmin = view.findViewById<RadioButton>(R.id.rbAdmin)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_user)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val username = etUsername.text.toString().trim()
                val password = etPassword.text.toString()
                if (username.isEmpty() || password.length < 6) {
                    toast(getString(R.string.username) + " / " + getString(R.string.password) + " (min 6)")
                    return@setPositiveButton
                }
                val body = mapOf(
                    "username" to username,
                    "password" to password,
                    "role" to if (rbAdmin.isChecked) "admin" else "cashier"
                )
                api.createUser(body).go({
                    toast(getString(R.string.user_created, username))
                }, { toast(it) })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Lipa na M-Pesa settings: the admin enters till/paybill number, store number,
     * operator name and the Daraja portal keys — the server derives the correct STK
     * transaction type (Paybill vs Buy Goods vs store-based till) automatically.
     */
    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val rbPaybill = view.findViewById<android.widget.RadioButton>(R.id.rbPaybill)
        val rbTill = view.findViewById<android.widget.RadioButton>(R.id.rbTill)
        val rbTillStore = view.findViewById<android.widget.RadioButton>(R.id.rbTillStore)
        val tilStore = view.findViewById<View>(R.id.tilStoreNumber)
        val etShortcode = view.findViewById<TextInputEditText>(R.id.etShortcode)
        val etStore = view.findViewById<TextInputEditText>(R.id.etStoreNumber)
        val etOperator = view.findViewById<TextInputEditText>(R.id.etOperatorName)
        val etAccountRef = view.findViewById<TextInputEditText>(R.id.etAccountRef)
        val etKey = view.findViewById<TextInputEditText>(R.id.etConsumerKey)
        val etSecret = view.findViewById<TextInputEditText>(R.id.etConsumerSecret)
        val etPasskey = view.findViewById<TextInputEditText>(R.id.etPasskey)
        val rbSandbox = view.findViewById<android.widget.RadioButton>(R.id.rbSandbox)
        val rbProduction = view.findViewById<android.widget.RadioButton>(R.id.rbProduction)
        val cbGoLive = view.findViewById<android.widget.CheckBox>(R.id.cbGoLive)
        val tvCallback = view.findViewById<android.widget.TextView>(R.id.tvCallbackUrl)
        val rgBiz = view.findViewById<android.widget.RadioGroup>(R.id.rgBizType)
        rgBiz.setOnCheckedChangeListener { _, checkedId ->
            tilStore.visibility = if (checkedId == R.id.rbTillStore) View.VISIBLE else View.GONE
        }

        // Prefill current settings
        api.getShop().go({ s ->
            when (s.businessType) {
                "till" -> rbTill.isChecked = true
                "till_store" -> { rbTillStore.isChecked = true; tilStore.visibility = View.VISIBLE }
                else -> rbPaybill.isChecked = true
            }
            etShortcode.setText(s.shortcode ?: "")
            etStore.setText(s.storeNumber ?: "")
            etOperator.setText(s.operatorName ?: "")
            etAccountRef.setText(s.accountRef ?: "")
            etKey.setText(s.consumerKey ?: "") // key is readable; secrets masked server-side
            if (s.darajaEnv == "production") rbProduction.isChecked = true else rbSandbox.isChecked = true
            cbGoLive.isChecked = !s.useMock
            tvCallback.text = getString(R.string.callback_hint, s.callbackUrl ?: "—")
        }, { toast(it) })

        AlertDialog.Builder(this)
            .setTitle(R.string.mpesa_settings)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val body = mutableMapOf<String, Any?>(
                    "business_type" to when {
                        rbTill.isChecked -> "till"
                        rbTillStore.isChecked -> "till_store"
                        else -> "paybill"
                    },
                    "shortcode" to etShortcode.text.toString().trim().ifEmpty { null },
                    "store_number" to etStore.text.toString().trim().ifEmpty { null },
                    "operator_name" to etOperator.text.toString().trim().ifEmpty { null },
                    "account_ref" to etAccountRef.text.toString().trim().ifEmpty { null },
                    "daraja_env" to if (rbProduction.isChecked) "production" else "sandbox",
                    "use_mock" to !cbGoLive.isChecked
                )
                // Secrets: only send when the admin actually typed something
                // (server keeps the stored value otherwise; masked placeholders are blocked).
                etKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { body["consumer_key"] = it }
                etSecret.text.toString().trim().takeIf { it.isNotEmpty() }?.let { body["consumer_secret"] = it }
                etPasskey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { body["passkey"] = it }

                api.putShop(body).go({ resp ->
                    val msg = resp["message"]?.toString() ?: getString(R.string.settings_saved)
                    toast(msg)
                }, { toast(it) })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Upload a CSV/XLSX price list to POST /api/items/import. */
    private fun importFile(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Cannot read file")
            val name = "catalog" + (if (uri.toString().endsWith(".csv", true)) ".csv" else ".xlsx")
            val body = bytes.toRequestBody("application/octet-stream".toMediaType())
            val part = MultipartBody.Part.createFormData("file", name, body)
            progress.visibility = View.VISIBLE
            api.importItems(part).go({ r ->
                progress.visibility = View.GONE
                toast(getString(R.string.import_done, r.inserted, r.updated, r.skipped))
                loadItems()
            }, { err ->
                progress.visibility = View.GONE
                toast(err)
            })
        } catch (e: Exception) {
            toast(e.message ?: "Import failed")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
