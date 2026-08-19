package com.tcrrry.desktopcast

import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.tcrrry.desktopcast.commercial.CommercialUiState
import com.tcrrry.desktopcast.commercial.EntitlementState
import com.tcrrry.desktopcast.commercial.ProductQuote
import com.tcrrry.desktopcast.commercial.formatCommercialRemaining
import kotlin.math.ceil
import kotlin.math.roundToInt

data class CastCommercialWaitingActions(
    val onBuyPro: () -> Unit,
    val onViewEntitlement: () -> Unit,
    val onRetry: () -> Unit,
)

/** Renders commercial access without changing the receiver lifecycle. */
class CastCommercialWaitingRenderer(
    root: View,
    actions: CastCommercialWaitingActions,
) {
    private val context: Context = root.context
    private val panel: View = root.findViewById(R.id.cast_commercial_waiting_panel)
    private val proStatus: TextView = root.findViewById(R.id.cast_commercial_waiting_pro_status)
    private val status: TextView = root.findViewById(R.id.cast_commercial_waiting_status)
    private val detail: TextView = root.findViewById(R.id.cast_commercial_waiting_detail)
    private val purchaseDetail: TextView =
        root.findViewById(R.id.cast_commercial_waiting_purchase_detail)
    private val actionsGroup: View = root.findViewById(R.id.cast_commercial_waiting_actions)
    private val purchaseAd: TextView = root.findViewById(R.id.cast_commercial_purchase_ad)
    private val buyPro: TextView = root.findViewById(R.id.cast_commercial_buy_pro)
    private val viewEntitlement: TextView =
        root.findViewById(R.id.cast_commercial_view_entitlement)
    private val retry: TextView = root.findViewById(R.id.cast_commercial_retry)

    init {
        purchaseAd.setOnClickListener { actions.onBuyPro() }
        buyPro.setOnClickListener { actions.onBuyPro() }
        viewEntitlement.setOnClickListener { actions.onViewEntitlement() }
        retry.setOnClickListener { actions.onRetry() }
    }

    fun render(state: CommercialUiState) {
        panel.isVisible = state.entitlement !is EntitlementState.Pro
        proStatus.isVisible = false
        purchaseAd.isVisible = false
        buyPro.isVisible = false
        viewEntitlement.isVisible = false
        retry.isVisible = false
        detail.isVisible = false
        purchaseDetail.isVisible = false
        status.isVisible = true
        status.setTextColor(ContextCompat.getColor(context, R.color.cast_text_secondary))
        purchaseDetail.setTextColor(ContextCompat.getColor(context, R.color.cast_text_secondary))

        when (val entitlement = state.entitlement) {
            EntitlementState.Checking -> {
                status.setText(R.string.cast_commercial_waiting_checking)
            }
            is EntitlementState.Trial -> {
                status.isVisible = false
                purchaseDetail.text = context.getString(
                    R.string.cast_commercial_waiting_trial,
                    formatCommercialRemaining(context, entitlement.remainingMillis),
                )
                purchaseDetail.isVisible = true
                renderPurchaseAd(state.quote)
                purchaseAd.isVisible = true
            }
            EntitlementState.Pro -> {
                status.isVisible = false
                renderProStatus()
                proStatus.isVisible = true
            }
            EntitlementState.Expired -> {
                status.isVisible = false
                purchaseDetail.setText(R.string.cast_commercial_waiting_expired_detail)
                purchaseDetail.isVisible = true
                renderPurchaseAd(state.quote)
                purchaseAd.isVisible = true
            }
            is EntitlementState.Error -> {
                status.setText(R.string.cast_commercial_waiting_error)
                detail.setText(R.string.cast_commercial_waiting_error_detail)
                detail.isVisible = true
                retry.isVisible = true
                viewEntitlement.isVisible = true
                status.setTextColor(ContextCompat.getColor(context, R.color.cast_error))
            }
        }
        actionsGroup.isVisible =
            purchaseAd.isVisible || buyPro.isVisible || viewEntitlement.isVisible || retry.isVisible
    }

    private fun renderProStatus() {
        val crown = ContextCompat.getDrawable(context, R.drawable.ic_commercial_pro_crown)
            ?.mutate()
            ?: return
        val label = context.getString(R.string.cast_commercial_waiting_pro)
        val glyphBounds = Rect()
        proStatus.paint.getTextBounds("权", 0, 1, glyphBounds)
        val targetGlyphHeight = glyphBounds.height().coerceAtLeast(1)
        val crownVisibleHeight = CROWN_VISIBLE_BOTTOM - CROWN_VISIBLE_TOP
        val crownHeight = ceil(targetGlyphHeight / crownVisibleHeight).toInt()
        val content = SpannableStringBuilder(" ")
        content.setSpan(
            InlineCrownSpan(
                drawable = crown,
                width = crownHeight,
                height = crownHeight,
                topOffset = glyphBounds.top -
                    (CROWN_VISIBLE_TOP * crownHeight).roundToInt(),
            ),
            0,
            1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        content.append(" ")
        content.append(label)
        proStatus.text = content
        proStatus.contentDescription = label
    }

    private fun renderPurchaseAd(quote: ProductQuote?) {
        if (quote == null) {
            purchaseAd.setText(R.string.cast_commercial_purchase_ad_fallback)
            purchaseAd.contentDescription = purchaseAd.text
            return
        }

        val ad = SpannableStringBuilder(
            context.getString(R.string.cast_commercial_purchase_ad_prefix),
        )
        val originalStart = ad.length
        ad.append(quote.originalPrice.text)
        ad.setSpan(
            StrikethroughSpan(),
            originalStart,
            ad.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        ad.setSpan(
            ForegroundColorSpan(
                ContextCompat.getColor(context, R.color.commercial_ad_original_price),
            ),
            originalStart,
            ad.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        ad.append(" ")
        val finalStart = ad.length
        ad.append(quote.finalPrice.text)
        ad.setSpan(
            StyleSpan(Typeface.BOLD),
            finalStart,
            ad.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        purchaseAd.text = ad
        purchaseAd.contentDescription = ad
    }

    private companion object {
        // The source crown vector's visible stroke spans y=3.1..20.9 in its 24-unit viewport.
        const val CROWN_VISIBLE_TOP = 3.1f / 24f
        const val CROWN_VISIBLE_BOTTOM = 20.9f / 24f
    }
}

/** Draws only the icon replacement; the surrounding text keeps its own font metrics. */
private class InlineCrownSpan(
    private val drawable: Drawable,
    private val width: Int,
    private val height: Int,
    private val topOffset: Int,
) : ReplacementSpan() {
    override fun getSize(
        paint: android.graphics.Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: android.graphics.Paint.FontMetricsInt?,
    ): Int = width

    override fun draw(
        canvas: android.graphics.Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: android.graphics.Paint,
    ) {
        drawable.setBounds(0, 0, width, height)
        canvas.save()
        canvas.translate(x, y + topOffset.toFloat())
        drawable.draw(canvas)
        canvas.restore()
    }
}
