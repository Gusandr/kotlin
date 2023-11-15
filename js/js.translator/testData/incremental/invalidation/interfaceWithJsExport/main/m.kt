fun box(): String {
    val foo: Foo = FooImpl()
    return foo.foo()
}