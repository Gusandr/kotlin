// FIR_IDENTICAL
// !LANGUAGE: +ContextReceivers

// MUTE_SIGNATURE_COMPARISON_K2: ANY
// ^ KT-57428

class Context

context(Context)
fun f(): String = TODO()

fun f(): Any = TODO()

fun test() {
    with(Context()) {
        f().length
    }
}
