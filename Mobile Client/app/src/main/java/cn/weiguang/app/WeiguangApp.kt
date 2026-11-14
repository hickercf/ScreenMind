package cn.weiguang.app

import android.app.Application
import androidx.room.Room
import cn.weiguang.app.data.AppDatabase
import cn.weiguang.app.data.Repository
import cn.weiguang.app.data.MIGRATION_1_2
import cn.weiguang.app.data.MIGRATION_2_3

class WeiguangApp : Application() {
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "weiguang.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build() }
    val repository by lazy { Repository(this, database) }
}

