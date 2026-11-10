package com.example.posewear

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.security.MessageDigest

fun dp(ctx: Context, v: Int): Int =
    (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

fun toast(msg: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(App.ctx, msg, Toast.LENGTH_SHORT).show()
    }
}

fun toastLong(msg: String) {
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(App.ctx, msg, Toast.LENGTH_LONG).show()
    }
}

fun md5(s: String): String {
    val b = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    return b.joinToString("") { "%02x".format(it) }
}

fun strInt(s: String, def: Int): Int = s.trim().toIntOrNull() ?: def
fun strDouble(s: String, def: Double): Double = s.trim().toDoubleOrNull() ?: def
fun strLong(s: String, def: Long): Long = s.trim().toLongOrNull() ?: def

/** 可中止标记（等价于桌面版 GenerateWorker._cancel）。 */
class AbortFlag {
    @Volatile var aborted = false
    fun set() { aborted = true }
}

/** 程序化表单，参考 novel-studio-app 的 FormBuilder，避免为每个设置项写 XML。 */
object Form {
    private fun row(ctx: Context, label: String): LinearLayout {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
        }
        val tv = TextView(ctx).apply {
            text = label
            textSize = 13f
            setTextColor(ctx.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(
                ctx.resources.getDimensionPixelSize(R.dimen.form_label_w),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        r.addView(tv)
        return r
    }

    fun edit(
        ctx: Context, container: LinearLayout, key: String, label: String,
        value: String, hint: String = "", numeric: Boolean = false,
        multiLine: Boolean = false
    ): EditText {
        val r = row(ctx, label)
        val et = EditText(ctx).apply {
            setText(value)
            this.hint = hint
            textSize = 13f
            setSingleLine(!multiLine)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (multiLine) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                gravity = android.view.Gravity.TOP
            }
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        r.addView(et)
        container.addView(r)
        et.tag = key
        return et
    }

    fun spinner(
        ctx: Context, container: LinearLayout, label: String,
        options: List<String>, selected: Int
    ): Spinner {
        val r = row(ctx, label)
        val sp = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, options).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setBackgroundResource(R.drawable.bg_input)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (selected in options.indices) setSelection(selected)
        }
        r.addView(sp)
        container.addView(r)
        return sp
    }
}
