package com.samin.notouchgesture.nearby

import android.content.Context

/** Process-local owner so the foreground service and Activity use the same Nearby connection. */
object NearbyRuntime {
    @Volatile
    private var manager: NearbyTransferManager? = null

    fun get(context: Context): NearbyTransferManager = synchronized(this) {
        manager ?: NearbyTransferManager(context.applicationContext).also { manager = it }
    }
}
