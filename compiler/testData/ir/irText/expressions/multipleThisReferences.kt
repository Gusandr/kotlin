// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: JS_IR NATIVE
// ^ KT-57430

class Outer {
    open inner class Inner(val x: Int)
}

class Host(val y: Int) {
    fun Outer.test() = object : Outer.Inner(42) {
        val xx = x + y
    }
}
