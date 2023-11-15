// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57434

fun <TT> outer() {
    fun <T> test1(i: Int, j: T) {}

    fun test2(i: Int = 0, j: String = "") {}

    fun test3(vararg args: String) {}

    fun String.textExt1(i: Int, j: TT) {}
}
