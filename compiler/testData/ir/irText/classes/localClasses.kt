// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57430

fun outer() {
    class LocalClass {
        fun foo() {}
    }
    LocalClass().foo()
}
