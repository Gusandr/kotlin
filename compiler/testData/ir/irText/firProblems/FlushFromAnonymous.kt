// TARGET_BACKEND: JVM_IR
// SKIP_KLIB_TEST

// MUTE_SIGNATURE_COMPARISON_K2: JS_IR NATIVE
// ^ KT-57430

// FILE: Collector.java

public class Collector {
    public void flush() {}
}

// FILE: FlushFromAnonymous.kt

class Serializer() {
    fun serialize() {
        val messageCollector = createMessageCollector()
        try {

        } catch (e: Throwable) {
            messageCollector.flush()
        }
    }

    private fun createMessageCollector() = object : Collector() {}
}
