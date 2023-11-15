// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: ANY
// ^ KT-57755, KT-57430

fun main() {
    class Foo {
        @Suppress("WRONG_MODIFIER_CONTAINING_DECLARATION")
        companion object {
            fun bar() {}
        }
    }
    Foo.Companion.bar()
}
