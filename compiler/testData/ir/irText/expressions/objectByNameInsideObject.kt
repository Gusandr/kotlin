// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: JS_IR NATIVE
// ^ KT-57428

open class Base(val f1: () -> Any)

object Thing : Base({ Thing }) {
    fun test1() = Thing
    fun test2() = this
}
