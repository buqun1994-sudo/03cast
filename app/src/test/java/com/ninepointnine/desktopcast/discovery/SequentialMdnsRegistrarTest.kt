package com.ninepointnine.desktopcast.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SequentialMdnsRegistrarTest {
    @Test
    fun airplayStartsOnlyAfterRaopCompletes() {
        val backend = FakeBackend()
        val registrar = SequentialMdnsRegistrar(backend)
        var result: MdnsRegistrationResult? = null

        registrar.start(raop(), airplay()) { result = it }

        assertEquals(listOf("_raop._tcp"), backend.registrationOrder)
        assertEquals(null, result)

        backend.succeed("_raop._tcp")
        assertEquals(listOf("_raop._tcp", "_airplay._tcp"), backend.registrationOrder)

        backend.succeed("_airplay._tcp")
        assertTrue(result!!.complete)
    }

    @Test
    fun airplayStillRegistersWhenRaopFails() {
        val backend = FakeBackend()
        val registrar = SequentialMdnsRegistrar(backend)
        var result: MdnsRegistrationResult? = null

        registrar.start(raop(), airplay()) { result = it }
        backend.fail("_raop._tcp", 3)

        assertEquals(listOf("_raop._tcp", "_airplay._tcp"), backend.registrationOrder)
        backend.succeed("_airplay._tcp")

        assertFalse(result!!.complete)
        assertEquals(3, result!!.failures["_raop._tcp"])
        assertTrue("_airplay._tcp" in result!!.registeredTypes)
    }

    @Test
    fun stopUnregistersInReverseOrderAndIgnoresLateCallbacks() {
        val backend = FakeBackend()
        val registrar = SequentialMdnsRegistrar(backend)
        var callbackCount = 0

        registrar.start(raop(), airplay()) { callbackCount++ }
        backend.succeed("_raop._tcp")
        registrar.stop()
        backend.succeed("_airplay._tcp")

        assertEquals(listOf("_airplay._tcp", "_raop._tcp"), backend.unregistrationOrder)
        assertEquals(0, callbackCount)
    }

    @Test
    fun restartUnregistersOldPairBeforeNewRegistration() {
        val backend = FakeBackend()
        val registrar = SequentialMdnsRegistrar(backend)

        registrar.start(raop(), airplay()) {}
        backend.succeed("_raop._tcp")
        backend.succeed("_airplay._tcp")
        registrar.start(raop(), airplay()) {}

        assertEquals(listOf("_airplay._tcp", "_raop._tcp"), backend.unregistrationOrder)
        assertEquals(3, backend.registrationOrder.size)
        assertEquals("_raop._tcp", backend.registrationOrder.last())
    }

    private fun raop() = MdnsServiceSpec("001122334455@03投屏", "_raop._tcp", 7000, emptyMap())
    private fun airplay() = MdnsServiceSpec("03投屏", "_airplay._tcp", 7000, emptyMap())

    private class FakeBackend : MdnsRegistrationBackend {
        data class FakeHandle(val type: String) : MdnsRegistrationBackend.Handle

        val registrationOrder = mutableListOf<String>()
        val unregistrationOrder = mutableListOf<String>()
        private val callbacks = mutableMapOf<String, MdnsRegistrationBackend.Callback>()

        override fun register(
            spec: MdnsServiceSpec,
            callback: MdnsRegistrationBackend.Callback,
        ): MdnsRegistrationBackend.Handle {
            registrationOrder += spec.type
            callbacks[spec.type] = callback
            return FakeHandle(spec.type)
        }

        override fun unregister(handle: MdnsRegistrationBackend.Handle) {
            unregistrationOrder += (handle as FakeHandle).type
        }

        fun succeed(type: String) = callbacks.getValue(type).registered()
        fun fail(type: String, code: Int) = callbacks.getValue(type).failed(code)
    }
}
