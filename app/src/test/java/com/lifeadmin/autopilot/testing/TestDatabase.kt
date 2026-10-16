package com.lifeadmin.autopilot.testing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lifeadmin.autopilot.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers

fun inMemoryDatabase(): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

/** File-backed database (WAL, multiple connections) — matches production for UI tests. */
fun fileDatabase(name: String = "ui-test.db"): AppDatabase {
    val context = ApplicationProvider.getApplicationContext<Context>()
    context.deleteDatabase(name)
    return Room.databaseBuilder(context, AppDatabase::class.java, name)
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
