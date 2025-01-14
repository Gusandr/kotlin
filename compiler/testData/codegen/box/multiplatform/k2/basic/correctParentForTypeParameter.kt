// IGNORE_BACKEND_K1: JS, JS_IR, JS_IR_ES6, WASM
// IGNORE_NATIVE_K1: mode=ONE_STAGE_MULTI_MODULE
// !LANGUAGE: +MultiPlatformProjects

// MODULE: lib
// FILE: lib.kt

package foo

fun transform(x: String, f: (String) -> String): String {
    return f(x) + "K"
}

// MODULE: platform()()(lib)
// FILE: platform.kt

package bar

import foo.*

fun box() = transform("") { "O" }