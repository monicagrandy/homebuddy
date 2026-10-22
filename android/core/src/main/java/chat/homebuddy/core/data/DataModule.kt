package chat.homebuddy.core.data

import android.content.Context
import androidx.room.Room
import chat.homebuddy.core.network.HomeBuddyApi
import chat.homebuddy.core.network.QueryStreamClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HomeBuddyDatabase =
        // Only a cache of the server's data, so a schema change can simply start over.
        Room.databaseBuilder(context, HomeBuddyDatabase::class.java, "homebuddy.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun uploadFiles(@ApplicationContext context: Context): UploadFiles = UploadFiles(File(context.filesDir, "uploads"))

    @Provides
    @Singleton
    fun uploadScheduler(@ApplicationContext context: Context): UploadScheduler = WorkManagerUploadScheduler(context)

    @Provides
    @Singleton
    fun documentsRepository(
        api: HomeBuddyApi,
        database: HomeBuddyDatabase,
        files: UploadFiles,
        scheduler: UploadScheduler,
    ): DocumentsRepository = DocumentsRepository(api, database.documents(), database.uploads(), files, scheduler)

    @Provides
    @Singleton
    fun uploadProcessor(api: HomeBuddyApi, database: HomeBuddyDatabase, files: UploadFiles): UploadProcessor =
        UploadProcessor(api, database.uploads(), database.documents(), files)

    @Provides
    @Singleton
    fun profileRepository(api: HomeBuddyApi, database: HomeBuddyDatabase): ProfileRepository =
        ProfileRepository(api, database.profile())

    @Provides
    @Singleton
    fun conversationsRepository(api: HomeBuddyApi, streams: QueryStreamClient, database: HomeBuddyDatabase): ConversationsRepository =
        ConversationsRepository(api, streams, database.conversations())

    @Provides
    @Singleton
    fun clientConfigRepository(api: HomeBuddyApi): ClientConfigRepository = ClientConfigRepository(api)
}
