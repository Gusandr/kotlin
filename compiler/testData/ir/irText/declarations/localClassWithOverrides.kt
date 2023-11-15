// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57430

fun outer() {
    abstract class ALocal {
        abstract fun afun()
        abstract val aval: Int
        abstract var avar: Int
    }

    class Local : ALocal() {
        override fun afun() {}
        override val aval = 1
        override var avar = 2
    }
}
