// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: JS_IR NATIVE
// ^ KT-57428

fun interface Foo {
    fun invoke(): String
}

fun foo(f: Foo) = f.invoke()

fun test() = foo { "OK" }
