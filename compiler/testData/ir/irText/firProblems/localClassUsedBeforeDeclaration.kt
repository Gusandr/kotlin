// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: JS_IR NATIVE
// ^ KT-57430

fun box(): String {
    return object {
        val a = A("OK")
        inner class A(val ok: String)
    }.a.ok
}
