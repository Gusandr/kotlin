// FIR_IDENTICAL

// MUTE_SIGNATURE_COMPARISON_K2: NATIVE
// ^ KT-57430, KT-57778

interface NestedGroupFragment

private fun addMavenOptionsGroupFragment() = addOptionsGroup<Int>()

private fun <S> addOptionsGroup() = object: NestedGroupFragment {}
