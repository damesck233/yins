package moe.damesck.yins

import android.app.Application
import moe.damesck.yins.root.RootShell

class YinsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RootShell.init()
    }
}
