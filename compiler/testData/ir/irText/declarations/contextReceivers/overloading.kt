// FIR_IDENTICAL
// !LANGUAGE: +ContextReceivers

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57428

context(Int, String)
fun foo(): Int {
    return this@Int + 42
}

context(Int)
fun foo(): Int {
    return this@Int + 42
}

fun test() {
    with(42) {
        foo()
    }
}
