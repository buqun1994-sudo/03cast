package com.ninepointnine.desktopcast.commercial

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class CloudDeviceCommercialGatewayTest {
    @Test
    fun fixtureStartsTrialWithServerSuppliedStagingQuote() = runBlocking {
        val fixture = FixtureHarness()

        val result = fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready
        val trial = result.snapshot.entitlement as EntitlementState.Trial
        val quote = requireNotNull(result.snapshot.quote)

        assertEquals(7L * 24 * 60 * 60 * 1000, trial.remainingMillis)
        assertEquals(2, quote.originalAmountCents)
        assertEquals(1, quote.finalAmountCents)
        assertEquals(5_000, quote.paymentRatioBps)
        assertEquals("5折", quote.discountLabel)
    }

    @Test
    fun productionFixtureMatches03castCloudContract() = runBlocking {
        val fixture = FixtureHarness()
        fixture.transport.pricing = DebugCommercialFixtureCatalog.production

        val quote = fixture.gateway.requestQuote(
            DebugCommercialFixtureCatalog.PUBLIC_CAMPAIGN_CODE,
            fixture.now,
        ) as QuoteRequestResult.Ready

        assertEquals(6_500, quote.quote.originalAmountCents)
        assertEquals(3_900, quote.quote.calculatedAmountCents)
        assertEquals(3_900, quote.quote.finalAmountCents)
        assertEquals("CNY", quote.quote.currency)
    }

    @Test
    fun quoteChangeReturnsLatestQuoteBeforeOrderCreation() = runBlocking {
        val fixture = FixtureHarness()
        val initial = (fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready).snapshot.quote!!
        fixture.transport.paymentOutcome = DebugPaymentOutcome.QUOTE_CHANGED

        val changed = fixture.gateway.createPayment(initial, PaymentMethod.WECHAT, fixture.now)
            as PaymentCreationResult.QuoteChanged

        assertEquals(DiscountResolution.NONE, changed.latestQuote.discountResolution)
        assertTrue(
            fixture.gateway.createPayment(
                changed.latestQuote,
                PaymentMethod.WECHAT,
                fixture.now,
            ) is PaymentCreationResult.Ready
        )
    }

    @Test
    fun paidPollPersistsLicenseAndEnablesLocalProAccess() = runBlocking {
        val fixture = FixtureHarness()
        val quote = (fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready).snapshot.quote!!
        val payment = fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
            as PaymentCreationResult.Ready
        fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID

        assertEquals(PaymentStatusResult.Paid, fixture.gateway.refreshPayment(payment.session, fixture.now))
        assertTrue(fixture.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value)
        assertTrue(
            fixture.licenseRepository.accessDecision(fixture.now) is
                CommercialAccessDecision.Allowed
        )
        assertEquals(
            EntitlementState.Pro,
            (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
                .snapshot.entitlement,
        )
    }

    @Test
    fun transientNetworkFailurePreservesLocallyVerifiedTrial() = runBlocking {
        val fixture = FixtureHarness()
        fixture.gateway.queryEntitlement(fixture.now)
        fixture.transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR

        val result = fixture.gateway.forceQueryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready

        assertTrue(result.snapshot.entitlement is EntitlementState.Trial)
    }

    private class FixtureHarness {
        val now = 1_700_000_000_000L
        val signer = TestFixtureSigner()
        val transport = FixtureDeviceCommerceTransport(signer) { now }
        val store = MapStore()
        val identity = TestIdentityProvider("a".repeat(64))
        val licenseRepository = CommercialLicenseRepository(
            store = store,
            identityProvider = identity,
            trust = DeviceCommerceLicenseTrust(signer.keyId, signer.publicKey()),
        )
        val gateway = CloudDeviceCommercialGateway(
            api = DeviceCommerceJsonApi(transport),
            identityProvider = identity,
            store = store,
            trialRepository = FirstOpenTrialRepository(store),
            licenseRepository = licenseRepository,
            clientVersion = "test",
        )
    }

    private class TestFixtureSigner : FixtureLicenseSigner {
        private val keys = generateKeys()
        override val keyId: String = DebugCommercialFixtureCatalog.LICENSE_KEY_ID
        override fun publicKey() = keys.public
        override fun sign(payload: ByteArray): ByteArray = signWith(keys, payload)
    }

    private class TestIdentityProvider(
        private val fingerprint: String,
    ) : DeviceIdentityProvider {
        private val keys = generateKeys()

        override fun loadOrCreate(): DeviceCommercialIdentity = identity()

        override fun signChallenge(challenge: ByteArray): ByteArray =
            signWith(keys, CommercialSignatureMessages.challenge(challenge))

        override fun signPurchasePoll(input: PurchasePollProofInput): ByteArray =
            signWith(keys, CommercialSignatureMessages.purchasePoll(input))

        override fun beginRecovery(rotateKnownKey: Boolean): RecoveryDeviceIdentitySession =
            object : RecoveryDeviceIdentitySession {
                override val identity: DeviceCommercialIdentity = identity()
                override fun signChallenge(challenge: ByteArray): ByteArray =
                    signWith(keys, CommercialSignatureMessages.challenge(challenge))
                override fun signWithPreviousKeyIfAvailable(challenge: ByteArray): ByteArray? = null
                override fun commit(): Boolean = true
                override fun abort() = Unit
            }

        private fun identity() = DeviceCommercialIdentity(
            publicKeySpkiBase64 = Base64.getEncoder().encodeToString(keys.public.encoded),
            publicKeySha256 = CommercialDigests.sha256Hex(keys.public.encoded),
            deviceFingerprintSha256 = fingerprint,
            signingCertSha256 = "c".repeat(64),
            attestationStatus = DeviceAttestationStatus.UNAVAILABLE,
        )
    }

    private class MapStore : SecureCommercialStore {
        private val values = mutableMapOf<SecureCommercialRecord, ByteArray>()

        override fun read(record: SecureCommercialRecord): SecureStoreReadResult =
            values[record]?.let { SecureStoreReadResult.Value(it.copyOf()) }
                ?: SecureStoreReadResult.Missing

        override fun write(record: SecureCommercialRecord, bytes: ByteArray): Boolean {
            values[record] = bytes.copyOf()
            return true
        }

        override fun delete(record: SecureCommercialRecord): Boolean {
            values.remove(record)
            return true
        }
    }

    private companion object {
        fun generateKeys(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        fun signWith(keys: KeyPair, payload: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(payload)
                sign()
            }
    }
}
