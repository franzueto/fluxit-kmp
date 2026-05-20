package dev.franzueto.fluxit.shared.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

actual class DriverFactory(
    private val context: Context,
) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(
            schema = FluxItDatabase.Schema,
            context = context,
            name = DatabaseName.PRODUCTION,
            callback =
                object : AndroidSqliteDriver.Callback(FluxItDatabase.Schema) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        super.onConfigure(db)
                        db.enableWriteAheadLogging()
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        db.setForeignKeyConstraintsEnabled(true)
                    }
                },
        )
}
