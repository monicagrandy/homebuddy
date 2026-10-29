package chat.homebuddy.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import chat.homebuddy.core.network.HomeBuddyJson
import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The schema versions Room has exported (schemas/), oldest first. */
fun exportedSchemaVersions(): List<Int> =
    File(SCHEMAS).listFiles().orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.sorted()

/**
 * Creates the database [name] as it was at [version], from Room's exported schema, and runs
 * [inserts] (SQL) on it: a phone that last ran an older build, for upgrade tests.
 */
fun createDatabaseAt(context: Context, name: String, version: Int, vararg inserts: String) {
    context.deleteDatabase(name)
    val file = context.getDatabasePath(name)
    file.parentFile!!.mkdirs()
    val schema = HomeBuddyJson.parseToJsonElement(File("$SCHEMAS/$version.json").readText())
        .jsonObject.getValue("database").jsonObject
    SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
        schema.getValue("entities").jsonArray.forEach { entity ->
            val table = entity.jsonObject
            val sql = table.getValue("createSql").jsonPrimitive.content
                .replace("\${TABLE_NAME}", table.getValue("tableName").jsonPrimitive.content)
            old.execSQL(sql)
        }
        schema.getValue("setupQueries").jsonArray.forEach { old.execSQL(it.jsonPrimitive.content) }
        inserts.forEach(old::execSQL)
        old.version = version
    }
}

/** Opens [name] the way the app does, upgrading it if it's older. */
fun openAsTheAppDoes(context: Context, name: String): HomeBuddyDatabase =
    Room.databaseBuilder(context, HomeBuddyDatabase::class.java, name)
        .withHomeBuddyMigrations()
        .allowMainThreadQueries()
        .build()

private const val SCHEMAS = "schemas/chat.homebuddy.core.data.HomeBuddyDatabase"
