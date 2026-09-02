package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommercialStateMachineTest {
    private val quote = ProductQuote(
        quoteReference = "quote-1",
        productId = DeviceCommerceProductContract.PRODUCT_ID,
        sku = DeviceCommerceProductContract.SKU,
        productName = "03cast Pro",
        originalAmountCents = 6500,
        originalPrice = DisplayMoney("server-original"),
        calculatedAmountCents = 3900,
        finalAmountCents = 3900,
        finalPrice = DisplayMoney("server-final"),
        paymentRatioBps = 6000,
        discountLabel = "6折",
        discountCode = "icar 03",
        discountResolution = DiscountResolution.VALID,
        minimumChargeApplied = false,
        currency = "CNY",
        availablePaymentMethods = setOf(PaymentMethod.WECHAT, PaymentMethod.ALIPAY),
        expiresAtEpochMs = 20_000L,
    )

    @Test
    fun trialQueryUsesGatewayPricesWithoutClientCalculation() {
        val state = CommercialStateMachine().dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(100_000L, 5_000L),
                    quote = quote,
                )
            )
        )

        assertTrue(state.entitlement is EntitlementState.Trial)
        assertEquals("server-original", state.quote?.originalPrice?.text)
        assertEquals("server-final", state.quote?.finalPrice?.text)
        assertEquals(6500, state.quote?.originalAmountCents)
        assertEquals(3900, state.quote?.finalAmountCents)
    }

    @Test
    fun stableProRemainsVisibleWhileRefreshIsRunning() {
        val machine = CommercialStateMachine(
            CommercialUiState(entitlement = EntitlementState.Pro)
        )

        val state = machine.dispatch(CommercialAction.QueryStarted)

        assertEquals(EntitlementState.Pro, state.entitlement)
        assertTrue(state.queryRefreshing)
        assertFalse(CommercialAdPolicy.isVisible(state.entitlement))
    }

    @Test
    fun quoteChangedRequiresSecondConfirmation() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = quote,
                checkout = CheckoutState.CreatingPayment,
            )
        )
        val changed = quote.copy(
            quoteReference = "quote-2",
            finalAmountCents = 4200,
            finalPrice = DisplayMoney("latest-server-final"),
        )

        val state = machine.dispatch(CommercialAction.PaymentQuoteChanged(changed))

        assertEquals(changed, state.quote)
        assertEquals(QuoteNotice.PRICE_CHANGED, state.quoteNotice)
        assertEquals(CheckoutState.Details, state.checkout)
    }

    @Test
    fun paymentPendingThenPaidEnablesPro() {
        val session = PaymentSession(
            purchaseReference = "purchase-1",
            finalAmountCents = quote.finalAmountCents,
            finalAmount = quote.finalPrice,
            currency = quote.currency,
            expiresAtEpochMs = 30_000L,
            pollAfterMillis = 1_000L,
            qrCode = PaymentQrCode(PaymentQrFormat.IMAGE_URL, "https://fixture.invalid/qr"),
        )
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                quote = quote,
                checkout = CheckoutState.Details,
            )
        )

        machine.dispatch(CommercialAction.PaymentCreationStarted)
        machine.dispatch(CommercialAction.PaymentCreated(session))
        val paid = machine.dispatch(CommercialAction.PaymentPaid)

        assertEquals(EntitlementState.Pro, paid.entitlement)
        assertTrue(paid.checkout is CheckoutState.Paid)
        assertEquals(CommercialPage.ENTITLEMENT, CommercialPagePolicy.pageFor(paid.checkout))
    }

    @Test
    fun transientPollingFailureKeepsQrSession() {
        val session = PaymentSession(
            purchaseReference = "purchase-2",
            finalAmountCents = 3900,
            finalAmount = DisplayMoney("server-final"),
            currency = "CNY",
            expiresAtEpochMs = 30_000L,
            pollAfterMillis = 1_000L,
            qrCode = PaymentQrCode(PaymentQrFormat.IMAGE_URL, "https://fixture.invalid/qr"),
        )
        val state = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Expired,
                checkout = CheckoutState.AwaitingPayment(session),
            )
        ).dispatch(CommercialAction.PaymentRefreshFailed(CommercialFailure.NETWORK))

        val awaiting = state.checkout as CheckoutState.AwaitingPayment
        assertEquals(session, awaiting.session)
        assertEquals(CommercialFailure.NETWORK, awaiting.transientFailure)
        assertEquals(CommercialPage.QR, CommercialPagePolicy.pageFor(state.checkout))
    }

    @Test
    fun queryFailureRendersErrorAndRemovesStaleCheckout() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Pro,
                quote = quote,
                checkout = CheckoutState.Details,
            )
        )

        val state = machine.dispatch(CommercialAction.QueryFailed(CommercialFailure.NETWORK))

        assertEquals(EntitlementState.Error(CommercialFailure.NETWORK), state.entitlement)
        assertEquals(null, state.quote)
        assertEquals(CheckoutState.Hidden, state.checkout)
    }

    @Test
    fun revokedQueryFixesTheEntitlementPageOwner() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Pro,
                quote = quote,
                checkout = CheckoutState.AwaitingPayment(paymentSession()),
                navigationIntent = CommercialNavigationIntent.QR,
            )
        )

        val state = machine.dispatch(
            CommercialAction.QueryFailed(CommercialFailure.ENTITLEMENT_REVOKED),
        )

        assertEquals(
            EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
            state.entitlement,
        )
        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            state.navigationIntent,
        )
    }

    @Test
    fun revokedEntitlementCanOpenCheckoutAfterQuoteIsLoaded() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
                quote = quote,
                navigationIntent = CommercialNavigationIntent.ENTITLEMENT,
            )
        )

        val state = machine.dispatch(CommercialAction.CheckoutRequested)

        assertEquals(CheckoutState.Details, state.checkout)
        assertEquals(CommercialPage.ORDER, CommercialPagePolicy.pageFor(state.checkout))
        assertEquals(CommercialNavigationIntent.ORDER, state.navigationIntent)
    }

    @Test
    fun initialQuoteFailureReturnsToRevokedEntitlementPageForRetry() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
                navigationIntent = CommercialNavigationIntent.ENTITLEMENT,
            )
        )

        machine.dispatch(CommercialAction.QuoteStarted)
        val state = machine.dispatch(CommercialAction.QuoteFailed(CommercialFailure.NETWORK))

        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(CommercialNavigationIntent.ENTITLEMENT, state.navigationIntent)
        assertFalse(state.quoteRefreshing)
    }

    @Test
    fun lateQuoteCompletionCannotReopenOrderAfterUserReturnsToEntitlement() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
                navigationIntent = CommercialNavigationIntent.ENTITLEMENT,
            )
        )

        machine.dispatch(CommercialAction.QuoteStarted)
        machine.dispatch(CommercialAction.EntitlementPageRequested)

        val state = machine.dispatch(CommercialAction.QuoteCompleted(quote))

        assertEquals(quote, state.quote)
        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            state.navigationIntent,
        )
    }

    @Test
    fun latePaymentCreationCannotReopenQrAfterUserReturnsToEntitlement() {
        val session = paymentSession()
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
                checkout = CheckoutState.Details,
                navigationIntent = CommercialNavigationIntent.ORDER,
            )
        )

        machine.dispatch(CommercialAction.PaymentCreationStarted)
        machine.dispatch(CommercialAction.EntitlementPageRequested)

        val state = machine.dispatch(CommercialAction.PaymentCreated(session))

        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            state.navigationIntent,
        )
    }

    @Test
    fun latePendingSnapshotCannotReopenQrAfterUserReturnsToEntitlement() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
            )
        )
        machine.dispatch(CommercialAction.QueryStarted)
        machine.dispatch(CommercialAction.EntitlementPageRequested)

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = null,
                    pendingPayment = paymentSession(),
                )
            )
        )

        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            state.navigationIntent
        )
    }

    @Test
    fun lateEmptySnapshotCannotLeaveOrderPageAfterUserChoosesCheckout() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
            )
        )
        machine.dispatch(CommercialAction.QueryStarted)
        machine.dispatch(CommercialAction.CheckoutRequested)

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = quote,
                )
            )
        )

        assertEquals(CheckoutState.Details, state.checkout)
        assertEquals(CommercialPage.ORDER, CommercialPagePolicy.pageFor(state.checkout))
        assertEquals(CommercialNavigationIntent.ORDER, state.navigationIntent)
    }

    @Test
    fun freshLifecycleRestoresPendingQrWhenThereIsNoPageIntent() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
            )
        )
        machine.dispatch(CommercialAction.QueryStarted)

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = null,
                    pendingPayment = paymentSession(),
                )
            )
        )

        assertTrue(state.checkout is CheckoutState.AwaitingPayment)
        assertNull(state.navigationIntent)
        assertEquals(CommercialPage.QR, CommercialPagePolicy.pageFor(state.checkout))
    }

    @Test
    fun pendingSnapshotKeepsTheQrSessionCreatedByThePaymentOperation() {
        val session = paymentSession()
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
                checkout = CheckoutState.Details,
            )
        )
        machine.dispatch(CommercialAction.PaymentCreated(session))

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = null,
                    pendingPayment = session,
                )
            )
        )

        assertEquals(CheckoutState.AwaitingPayment(session), state.checkout)
        assertEquals(CommercialNavigationIntent.QR, state.navigationIntent)
    }

    @Test
    fun lateEmptySnapshotCannotClearQrOwnedByPaymentOperation() {
        val session = paymentSession()
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
                checkout = CheckoutState.Details,
            )
        )
        machine.dispatch(CommercialAction.PaymentCreated(session))

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = null,
                    pendingPayment = null,
                )
            )
        )

        assertEquals(CheckoutState.AwaitingPayment(session), state.checkout)
        assertEquals(CommercialNavigationIntent.QR, state.navigationIntent)
    }

    @Test
    fun newLifecycleCanClearAnOldQrWhenNoPendingSessionRemains() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                checkout = CheckoutState.AwaitingPayment(paymentSession()),
                navigationIntent = CommercialNavigationIntent.QR,
            )
        )

        machine.dispatch(CommercialAction.QueryStarted)
        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = quote,
                    pendingPayment = null,
                )
            )
        )

        assertEquals(CheckoutState.Hidden, state.checkout)
        assertNull(state.navigationIntent)
    }

    @Test
    fun pendingSnapshotDoesNotReplaceAnExplicitOrderPage() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
                checkout = CheckoutState.Details,
            )
        )
        machine.dispatch(CommercialAction.CheckoutRequested)

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Trial(40_000L, 20_000L),
                    quote = null,
                    pendingPayment = paymentSession(),
                )
            )
        )

        assertEquals(CheckoutState.Details, state.checkout)
        assertEquals(quote, state.quote)
        assertEquals(CommercialPage.ORDER, CommercialPagePolicy.pageFor(state.checkout))
    }

    @Test
    fun authoritativeProSnapshotAlwaysClosesOrderPage() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
            )
        )
        machine.dispatch(CommercialAction.CheckoutRequested)

        val state = machine.dispatch(
            CommercialAction.QueryCompleted(
                EntitlementSnapshot(
                    entitlement = EntitlementState.Pro,
                    quote = null,
                    pendingPayment = paymentSession(),
                )
            )
        )

        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(EntitlementState.Pro, state.entitlement)
        assertEquals(CommercialNavigationIntent.ENTITLEMENT, state.navigationIntent)
    }

    @Test
    fun terminalPaymentFailureLeavesTheOrderPageAsTheOwner() {
        val state = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                checkout = CheckoutState.AwaitingPayment(paymentSession()),
                navigationIntent = CommercialNavigationIntent.QR,
            )
        ).dispatch(CommercialAction.PaymentRefreshFailed(CommercialFailure.PAYMENT))

        assertTrue(state.checkout is CheckoutState.Error)
        assertEquals(CommercialNavigationIntent.ORDER, state.navigationIntent)
    }

    @Test
    fun revokedPaymentFailureAlwaysReturnsToEntitlementPage() {
        val state = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Pro,
                checkout = CheckoutState.AwaitingPayment(paymentSession()),
                navigationIntent = CommercialNavigationIntent.QR,
            )
        ).dispatch(CommercialAction.PaymentRefreshFailed(CommercialFailure.ENTITLEMENT_REVOKED))

        assertEquals(
            EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
            state.entitlement,
        )
        assertEquals(CheckoutState.Hidden, state.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            state.navigationIntent,
        )
    }

    @Test
    fun latePaymentPollFailureCannotNavigateAfterQrBack() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
            )
        )
        machine.dispatch(CommercialAction.PaymentCreated(paymentSession()))
        machine.dispatch(CommercialAction.EntitlementPageRequested)

        val afterPending = machine.dispatch(CommercialAction.PaymentPending)
        assertEquals(CheckoutState.Hidden, afterPending.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            afterPending.navigationIntent
        )

        val afterNetworkFailure = machine.dispatch(
            CommercialAction.PaymentRefreshFailed(CommercialFailure.NETWORK)
        )
        val afterExpiry = machine.dispatch(CommercialAction.PaymentExpired)

        assertEquals(CheckoutState.Hidden, afterNetworkFailure.checkout)
        assertEquals(CheckoutState.Hidden, afterExpiry.checkout)
        assertEquals(
            CommercialNavigationIntent.ENTITLEMENT,
            afterExpiry.navigationIntent
        )
    }

    @Test
    fun transientLateQueryFailureKeepsTheExplicitPageIntent() {
        val machine = CommercialStateMachine(
            CommercialUiState(
                entitlement = EntitlementState.Trial(40_000L, 20_000L),
                quote = quote,
            )
        )
        machine.dispatch(CommercialAction.QueryStarted)
        machine.dispatch(CommercialAction.CheckoutRequested)

        val state = machine.dispatch(
            CommercialAction.QueryFailed(CommercialFailure.NETWORK)
        )

        assertEquals(CheckoutState.Details, state.checkout)
        assertEquals(quote, state.quote)
        assertEquals(CommercialNavigationIntent.ORDER, state.navigationIntent)
        assertFalse(state.queryRefreshing)
    }

    private fun paymentSession() = PaymentSession(
        purchaseReference = "purchase-late-query",
        finalAmountCents = quote.finalAmountCents,
        finalAmount = quote.finalPrice,
        currency = quote.currency,
        expiresAtEpochMs = 30_000L,
        pollAfterMillis = 1_000L,
        qrCode = PaymentQrCode(PaymentQrFormat.IMAGE_URL, "https://fixture.invalid/late-qr"),
    )
}
