// FIR_IDENTICAL
// SKIP_KLIB_TEST
// IGNORE_BACKEND: JS_IR, JS_IR_ES6
// IGNORE_BACKEND: NATIVE
// STATUS: This should not work in JS & NATIVE: Cloneable is JVM-specific API

class A : Cloneable

interface I : Cloneable

class C : I

class OC : I {
    override fun clone(): OC = OC()
}
