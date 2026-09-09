package moe.damesck.yins

/** [isActive] is replaced by the module's own hook when LSPosed has loaded it into this process. */
object ModuleStatus {
    @JvmStatic
    fun isActive(): Boolean = false
}
