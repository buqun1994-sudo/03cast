package com.ninepointnine.desktopcast.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CommercialAccessTest {
    @Test
    fun storageReadFailureFailsClosed() {
        val gate = VerifiedLicenseAccessGate(
            store = FakeStore(readFailure = true),
            verifier = verifier(generateKeyPair()),
        )

        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.STORAGE_FAILURE),
            gate.evaluate(NOW),
        )
    }

    @Test
    fun revocationMarkerTakesPrecedenceOverStoredLicense() {
        val keys = generateKeyPair()
        val payload = "payload".toByteArray()
        val store = FakeStore().apply {
            values[SecureCommercialRecord.ACCESS_REVOCATION] = byteArrayOf(1)
            values[SecureCommercialRecord.LICENSE] = byteArrayOf(1)
        }

        val result = VerifiedLicenseAccessGate(store, verifier(keys)).evaluate(NOW)

        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
            result,
        )
        assertTrue(payload.isNotEmpty())
    }

    @Test
    fun licenseClockRollbackFailsClosed() {
        val store = FakeStore().apply {
            values[SecureCommercialRecord.LICENSE_CLOCK] =
                SecureCommercialRecordCodec.encodeLong(
                    NOW + TrialPolicy.CLOCK_ROLLBACK_TOLERANCE_MS + 1
                )
        }

        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.CLOCK_ROLLBACK),
            VerifiedLicenseAccessGate(store, verifier(generateKeyPair())).evaluate(NOW),
        )
    }

    @Test
    fun validSignedProLicenseAllowsOfflineGraceAndPersistsClock() {
        val keys = generateKeyPair()
        val payload = "payload".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(keys, payload), KEY_ID)
        val store = FakeStore().apply {
            values[SecureCommercialRecord.LICENSE] = SignedLicenseEnvelopeCodec.encode(envelope)
        }

        val result = VerifiedLicenseAccessGate(store, verifier(keys)).evaluate(NOW)

        assertEquals(
            CommercialAccessDecision.Allowed(
                tier = CommercialTier.PRO,
                expiresAtEpochMs = NOW + 20_000L,
                refreshAfterEpochMs = NOW + 10_000L,
                offlineGraceUntilEpochMs = NOW + 20_000L,
            ),
            result,
        )
        assertTrue(store.values.containsKey(SecureCommercialRecord.LICENSE_CLOCK))
    }

    @Test
    fun revokedAccessReachesSharedUiListenersImmediately() {
        val snapshots = mutableListOf<EntitlementSnapshot>()
        val coordinator = CommercialEntitlementCoordinator(
            gateway = NoopGateway,
            accessGate = CommercialAccessGate {
                CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED)
            },
            nowEpochMs = { NOW },
        )
        coordinator.addListener(snapshots::add)

        coordinator.evaluate(NOW)

        assertEquals(1, snapshots.size)
        assertEquals(
            EntitlementState.Error(CommercialFailure.ENTITLEMENT_REVOKED),
            snapshots.single().entitlement,
        )
        assertEquals(null, snapshots.single().quote)
        assertEquals(null, snapshots.single().pendingPayment)
    }

    private fun verifier(keys: java.security.KeyPair): LicenseVerifier = LicenseVerifier(
        trustedPublicKey = keys.public,
        expectedKeyId = KEY_ID,
        expectedProductId = DeviceCommerceProductContract.PRODUCT_ID,
        expectedDevicePublicKeySha256 = DEVICE_KEY,
        expectedDeviceKeyVersion = 1,
        parser = LicenseClaimsParser { validClaims() },
    )

    private fun validClaims() = LicenseClaims(
        version = 1,
        licenseId = "license",
        keyId = KEY_ID,
        productId = DeviceCommerceProductContract.PRODUCT_ID,
        devicePublicKeySha256 = DEVICE_KEY,
        deviceKeyVersion = 1,
        tier = CommercialTier.PRO,
        issuedAtEpochMs = NOW - 10_000L,
        expiresAtEpochMs = NOW + 10_000L,
        offlineGraceUntilEpochMs = NOW + 20_000L,
        trialEndsAtEpochMs = null,
    )

    private fun generateKeyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun sign(keyPair: java.security.KeyPair, payload: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }

    private class FakeStore(private val readFailure: Boolean = false) : SecureCommercialStore {
        val values = mutableMapOf<SecureCommercialRecord, ByteArray>()

        override fun read(record: SecureCommercialRecord): SecureStoreReadResult {
            if (readFailure) return SecureStoreReadResult.Failure
            return values[record]?.let(SecureStoreReadResult::Value)
                ?: SecureStoreReadResult.Missing
        }

        override fun write(record: SecureCommercialRecord, bytes: ByteArray): Boolean {
            values[record] = bytes.copyOf()
            return true
        }

        override fun delete(record: SecureCommercialRecord): Boolean {
            values.remove(record)
            return true
        }
    }

    private object NoopGateway : DeviceCommercialGateway {
        override suspend fun queryEntitlement(nowEpochMs: Long): EntitlementQueryResult =
            EntitlementQueryResult.Failure(CommercialFailure.UNKNOWN)

        override suspend fun requestQuote(
            discountCode: String,
            nowEpochMs: Long,
        ): QuoteRequestResult = QuoteRequestResult.Failure(CommercialFailure.UNKNOWN)

        override suspend fun createPayment(
            quote: ProductQuote,
            method: PaymentMethod,
            nowEpochMs: Long,
        ): PaymentCreationResult = PaymentCreationResult.Failure(CommercialFailure.UNKNOWN)

        override suspend fun refreshPayment(
            session: PaymentSession,
            nowEpochMs: Long,
        ): PaymentStatusResult = PaymentStatusResult.Failure(CommercialFailure.UNKNOWN)

        override suspend fun restorePurchase(nowEpochMs: Long): PurchaseRecoveryResult =
            PurchaseRecoveryResult.Failure(CommercialFailure.UNKNOWN)
    }

    private companion object {
        const val NOW = 10_000_000L
        const val KEY_ID = "03cast-test-key"
        val DEVICE_KEY = "d".repeat(64)
    }
}
