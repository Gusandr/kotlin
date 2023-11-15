// FIR_IDENTICAL
//!LANGUAGE: +DefinitelyNonNullableTypes

// MUTE_SIGNATURE_COMPARISON_K2: ANY
// ^ KT-57428

fun <T> (T & Any).foo() {}
fun <T> foo(l: (T & Any) -> Unit) {}

fun box() {
    "".foo<String?>()
    foo<String?> { "$it" }
}
