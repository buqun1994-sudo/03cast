package com.tcrrry.desktopcast.commercial

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CommercialSecurityTest {
    @Test
    fun challengeAndPurchasePollMessagesFollowProtocol() {
        val challenge = byteArrayOf(0, 1, 2, -1)
        assertArrayEquals(challenge, CommercialSignatureMessages.challenge(challenge))
        assertEquals(
            "purchase\npoll\n1234\nnonce",
            CommercialSignatureMessages.purchasePoll(
                PurchasePollProofInput("purchase", "poll", 1234L, "nonce")
            ).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun invalidSignatureIsRejectedBeforeClaimsParserRuns() {
        val trusted = generateKeyPair()
        val payload = "payload".toByteArray()
        var parserCalls = 0
        val verifier = LicenseVerifier(
            trustedPublicKey = trusted.public,
            expectedKeyId = KEY_ID,
            expectedProductId = DeviceCommerceProductContract.PRODUCT_ID,
            expectedDevicePublicKeySha256 = DEVICE_KEY,
            expectedDeviceKeyVersion = 1,
            parser = LicenseClaimsParser {
                parserCalls += 1
                validClaims()
            },
        )

        val result = verifier.verify(
            SignedLicenseEnvelope(payload, sign(generateKeyPair(), payload), KEY_ID),
            NOW,
        )

        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.SIGNATURE),
            result,
        )
        assertEquals(0, parserCalls)
    }

    @Test
    fun productAndDeviceClaimsAreBoundToThe03castContract() {
        val keys = generateKeyPair()
        val payload = "signed".toByteArray()
        val envelope = SignedLicenseEnvelope(payload, sign(keys, payload), KEY_ID)

        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.PRODUCT),
            verifier(keys) { validClaims().copy(productId = "03lyrics") }
                .verify(envelope, NOW),
        )
        assertEquals(
            LicenseVerificationResult.Invalid(LicenseVerificationFailure.DEVICE),
            verifier(keys) { validClaims().copy(devicePublicKeySha256 = "e".repeat(64)) }
                .verify(envelope, NOW),
        )
    }

    @Test
    fun signedEnvelopeRoundTripsAndTrialRollbackFailsClosed() {
        val envelope = SignedLicenseEnvelope(
            rawPayload = byteArrayOf(0, 1, 2),
            signature = byteArrayOf(3, 4),
            keyId = KEY_ID,
        )
        val decoded = SignedLicenseEnvelopeCodec.decode(SignedLicenseEnvelopeCodec.encode(envelope))
        assertArrayEquals(envelope.rawPayload, decoded.rawPayload)
        assertArrayEquals(envelope.signature, decoded.signature)
        assertEquals(envelope.keyId, decoded.keyId)

        val clock = TrialClockState(1_000_000L, 2_000_000L)
        assertEquals(
            TrialEvaluation.ClockRollback,
            TrialPolicy.evaluate(
                clock,
                2_000_000L - TrialPolicy.CLOCK_ROLLBACK_TOLERANCE_MS - 1,
                2_000_000L,
            ),
        )
        assertTrue(
            TrialPolicy.evaluate(clock, 2_000_000L, 2_000_000L)
                is TrialEvaluation.Active
        )
        assertFalse(decoded.keyId.isBlank())
    }

    private fun verifier(
        keys: java.security.KeyPair,
        claims: () -> LicenseClaims = ::validClaims,
    ) = LicenseVerifier(
        trustedPublicKey = keys.public,
        expectedKeyId = KEY_ID,
        expectedProductId = DeviceCommerceProductContract.PRODUCT_ID,
        expectedDevicePublicKeySha256 = DEVICE_KEY,
        expectedDeviceKeyVersion = 1,
        parser = LicenseClaimsParser { claims() },
    )

    private fun validClaims() = LicenseClaims(
        version = 1,
        licenseId = "license",
        keyId = KEY_ID,
        productId = DeviceCommerceProductContract.PRODUCT_ID,
        devicePublicKeySha256 = DEVICE_KEY,
        deviceKeyVersion = 1,
        tier = CommercialTier.PRO,
        issuedAtEpochMs = NOW - 1_000L,
        expiresAtEpochMs = NOW + 1_000L,
        offlineGraceUntilEpochMs = NOW + 2_000L,
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

    private companion object {
        const val NOW = 10_000_000L
        const val KEY_ID = "03cast-test-key"
        val DEVICE_KEY = "d".repeat(64)
    }
}
