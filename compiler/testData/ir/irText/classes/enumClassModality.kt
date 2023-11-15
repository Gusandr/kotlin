// IGNORE_BACKEND: JS_IR
// IGNORE_BACKEND: JS_IR_ES6

// MUTE_SIGNATURE_COMPARISON_K2: JS_IR NATIVE
// ^ KT-57775

// KT-61141: absent enum fake_overrides: finalize, getDeclaringClass, clone
// IGNORE_BACKEND: NATIVE

enum class TestFinalEnum1 {
    X1
}

enum class TestFinalEnum2(val x: Int) {
    X1(1)
}

enum class TestFinalEnum3 {
    X1
    ;

    fun doStuff() {}
}

enum class TestOpenEnum1 {
    X1 {
        override fun toString() = "X1"
    }
}

enum class TestOpenEnum2 {
    X1 {
        override fun foo() {}
    };

    open fun foo() {}
}

enum class TestAbstractEnum1 {
    X1 {
        override fun foo() {}
    };

    abstract fun foo()
}

interface IFoo {
    fun foo()
}

enum class TestAbstractEnum2 : IFoo {
    X1 {
        override fun foo() {}
    }
}
