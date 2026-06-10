package com.fluxit.data

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(entities = [ListEntity::class, ItemEntity::class], version = 1)
@ConstructedBy(FluxItDatabaseConstructor::class)
abstract class FluxItDatabase : RoomDatabase() {
    abstract fun listDao(): ListDao
    abstract fun itemDao(): ItemDao
}

@Suppress("NO_ACTUAL_FOR_EXPECT", "EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object FluxItDatabaseConstructor : RoomDatabaseConstructor<FluxItDatabase> {
    override fun initialize(): FluxItDatabase
}
