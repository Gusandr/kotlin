// FIR_IDENTICAL
// WITH_STDLIB

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57428

annotation class Ann

@delegate:Ann
val test1 by lazy { 42 }
