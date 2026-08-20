package com.ninepointnine.desktopcast.discovery

data class MdnsServiceSpec(
    val name: String,
    val type: String,
    val port: Int,
    val attributes: Map<String, String>,
)

interface MdnsRegistrationBackend {
    interface Handle

    interface Callback {
        fun registered()
        fun failed(errorCode: Int)
    }

    fun register(spec: MdnsServiceSpec, callback: Callback): Handle
    fun unregister(handle: Handle)
}

data class MdnsRegistrationResult(
    val registeredTypes: Set<String>,
    val failures: Map<String, Int>,
) {
    val complete: Boolean get() = failures.isEmpty() && registeredTypes.size == 2
}

class SequentialMdnsRegistrar(
    private val backend: MdnsRegistrationBackend,
) {
    private val lock = Any()
    private val handles = mutableListOf<MdnsRegistrationBackend.Handle>()
    private var generation = 0
    private var specs = emptyList<MdnsServiceSpec>()
    private var registered = linkedSetOf<String>()
    private var failures = linkedMapOf<String, Int>()
    private var completion: ((MdnsRegistrationResult) -> Unit)? = null

    fun start(
        raop: MdnsServiceSpec,
        airplay: MdnsServiceSpec,
        onComplete: (MdnsRegistrationResult) -> Unit,
    ) {
        stop()
        val currentGeneration: Int
        synchronized(lock) {
            generation += 1
            currentGeneration = generation
            specs = listOf(raop, airplay)
            registered = linkedSetOf()
            failures = linkedMapOf()
            completion = onComplete
        }
        registerIndex(currentGeneration, 0)
    }

    fun stop() {
        val oldHandles: List<MdnsRegistrationBackend.Handle>
        synchronized(lock) {
            generation += 1
            oldHandles = handles.toList()
            handles.clear()
            specs = emptyList()
            registered.clear()
            failures.clear()
            completion = null
        }
        oldHandles.asReversed().forEach(backend::unregister)
    }

    private fun registerIndex(expectedGeneration: Int, index: Int) {
        val spec = synchronized(lock) {
            if (generation != expectedGeneration || index !in specs.indices) return
            specs[index]
        }
        val callback = object : MdnsRegistrationBackend.Callback {
            override fun registered() = finishStep(expectedGeneration, index, spec, null)
            override fun failed(errorCode: Int) = finishStep(expectedGeneration, index, spec, errorCode)
        }
        val handle = backend.register(spec, callback)
        synchronized(lock) {
            if (generation == expectedGeneration) {
                handles += handle
            } else {
                backend.unregister(handle)
            }
        }
    }

    private fun finishStep(
        expectedGeneration: Int,
        index: Int,
        spec: MdnsServiceSpec,
        errorCode: Int?,
    ) {
        val shouldContinue: Boolean
        val result: MdnsRegistrationResult?
        val callback: ((MdnsRegistrationResult) -> Unit)?
        synchronized(lock) {
            if (generation != expectedGeneration) return
            if (errorCode == null) registered += spec.type else failures[spec.type] = errorCode
            shouldContinue = index + 1 < specs.size
            if (shouldContinue) {
                result = null
                callback = null
            } else {
                result = MdnsRegistrationResult(registered.toSet(), failures.toMap())
                callback = completion
            }
        }
        if (shouldContinue) {
            registerIndex(expectedGeneration, index + 1)
        } else if (result != null) {
            callback?.invoke(result)
        }
    }
}
