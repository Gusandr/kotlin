// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^KT-57434

fun test1(x: Int, y: Int = 0, z: String = "abc") {
    fun local(xx: Int = x, yy: Int = y, zz: String = z) {}
}
