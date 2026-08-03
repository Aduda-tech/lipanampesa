package com.lipanampesa.shop

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton

/** Transaction history (the "transactions database") with M-Pesa confirmation numbers. */
class TransactionsActivity : AppCompatActivity() {

    private lateinit var api: ApiService
    private lateinit var listView: ListView
    private lateinit var tvSummary: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)
        api = ApiClient.service(this)
        listView = findViewById(R.id.listTxns)
        tvSummary = findViewById(R.id.tvSummary)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        swipeRefresh.setOnRefreshListener { reload() }
        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener { reload() }
        listView.setOnItemClickListener { parent, _, position, _ ->
            (parent.getItemAtPosition(position) as? Txn)?.let { showDetail(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        swipeRefresh.isRefreshing = true
        api.transactions().go({ list ->
            swipeRefresh.isRefreshing = false
            listView.adapter = TxnAdapter(this, list)
        }, { err ->
            swipeRefresh.isRefreshing = false
            toast(err)
        })

        api.summary().go({ s ->
            tvSummary.text = getString(
                R.string.today_summary,
                (s.successCount ?: 0.0).toInt(),
                money(s.successAmount ?: 0.0, "KES"),
                (s.pendingCount ?: 0.0).toInt()
            )
        }, { /* summary is nice-to-have */ })
    }

    /** Full detail dialog: every cart line, tax, receipt, cashier, checkout id. */
    private fun showDetail(t: Txn) {
        val sb = StringBuilder()
        val lines = itemLines(t.itemsJson, t.currency)
        if (lines.isNotEmpty()) {
            lines.forEach { sb.appendLine(it) }
            sb.appendLine("--------------------")
        } else {
            sb.appendLine("${t.itemName} × ${t.quantity}")
        }
        if (t.taxAmount > 0) sb.appendLine("${getString(R.string.tax)}: ${money(t.taxAmount, t.currency)}")
        sb.appendLine("${getString(R.string.total_amount)}: ${money(t.amount, t.currency)}")
        sb.appendLine("${t.customerPhone}")
        t.mpesaReceipt?.let { sb.appendLine(getString(R.string.receipt_fmt, it)) }
        t.cashierUsername?.let { sb.appendLine("Cashier: $it") }
        t.createdAt?.let { sb.appendLine(it) }
        t.resultDesc?.takeIf { t.status != "SUCCESS" }?.let { sb.appendLine(it) }

        AlertDialog.Builder(this)
            .setTitle("${t.status} · #${t.id}")
            .setMessage(sb.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private class TxnAdapter(context: Context, txns: List<Txn>) :
        ArrayAdapter<Txn>(context, 0, txns) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(context).inflate(R.layout.item_transaction, parent, false)
            val t = getItem(position) ?: return view
            val line1 = view.findViewById<TextView>(R.id.tvLine1)
            val line2 = view.findViewById<TextView>(R.id.tvLine2)
            val line3 = view.findViewById<TextView>(R.id.tvLine3)
            val status = view.findViewById<TextView>(R.id.tvStatus)

            line1.text = t.itemName
            line2.text = "${money(t.amount, t.currency)}  ·  ${t.customerPhone}"
            line3.text = listOfNotNull(
                t.mpesaReceipt?.let { "Receipt: $it" },
                t.cashierUsername?.let { "by $it" },
                t.createdAt
            ).joinToString("  ·  ")
            status.text = t.status
            val color = when (t.status) {
                "SUCCESS" -> R.color.status_success
                "PENDING" -> R.color.status_pending
                else -> R.color.status_failed
            }
            status.setTextColor(ContextCompat.getColor(context, color))
            return view
        }
    }
}
