package cn.weiguang.app

import android.app.Application
import androidx.room.Room
import cn.weiguang.app.data.AppDatabase
import cn.weiguang.app.data.Repository

class WeiguangApp : Application() {
    val database by lazy { Room.databaseBuilder(this, AppDatabase::class.java, "weiguang.db").build() }
    val repository by lazy { Repository(this, database) }
}
