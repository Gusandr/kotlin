// MODULE: m1-common
// FILE: common.kt

open class Base<R> {
    <!INCOMPATIBLE_MATCHING{JVM}!>open fun foo(): R = null!!<!>
}

<!INCOMPATIBLE_MATCHING{JVM}!>expect open class Foo<R, T : R> : Base<R> {
}<!>

// MODULE: m2-jvm()()(m1-common)
// FILE: jvm.kt

actual open class Foo<E, F : E> : Base<E>() {
    // Return type mismatch isn't reported in K2 because K2 doesn't compare return types on frontend.
    // It reports INCOMPATIBLE_MATCHING on backend instead KT-60961.
    override fun foo(): F = null!!
}
