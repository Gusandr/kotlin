
class Wrapper {
    val publicDummy = object : Bar {}
    private val privateDummy = object : Bar {}
    val publicBar1 = object : Bar by publicDummy {}
    val publicBar2 = object : Bar by privateDummy {}
    private val privateBar1 = object : Bar by publicDummy {}
    private val privateBar2 = object : Bar by privateDummy {}
}

interface Bar {
    val foo: String
        get() = ""
}
