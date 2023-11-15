// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57434

class Delegate(val value: String) {
    operator fun getValue(thisRef: Any?, property: Any?) = value
}

class DelegateProvider(val value: String) {
    operator fun provideDelegate(thisRef: Any?, property: Any?) = Delegate(value)
}

fun foo() {
    val testMember by DelegateProvider("OK")
}

