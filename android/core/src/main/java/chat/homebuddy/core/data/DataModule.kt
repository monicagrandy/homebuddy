package chat.homebuddy.core.data

import android.content.Context
import androidx.room.Room
import chat.homebuddy.core.data.conversations.CachedConversationsRepository
import chat.homebuddy.core.data.conversations.ConversationsLocalDataSource
import chat.homebuddy.core.data.conversations.ConversationsRemoteDataSource
import chat.homebuddy.core.data.conversations.ConversationsRepository
import chat.homebuddy.core.data.conversations.RetrofitConversationsRemoteDataSource
import chat.homebuddy.core.data.conversations.RoomConversationsLocalDataSource
import chat.homebuddy.core.data.documents.CachedDocumentsRepository
import chat.homebuddy.core.data.documents.DocumentsLocalDataSource
import chat.homebuddy.core.data.documents.DocumentsRemoteDataSource
import chat.homebuddy.core.data.documents.DocumentsRepository
import chat.homebuddy.core.data.documents.RetrofitDocumentsRemoteDataSource
import chat.homebuddy.core.data.documents.RoomDocumentsLocalDataSource
import chat.homebuddy.core.data.documents.UploadFiles
import chat.homebuddy.core.data.documents.UploadProcessor
import chat.homebuddy.core.data.documents.UploadScheduler
import chat.homebuddy.core.data.profile.CachedProfileRepository
import chat.homebuddy.core.data.profile.ProfileLocalDataSource
import chat.homebuddy.core.data.profile.ProfileRemoteDataSource
import chat.homebuddy.core.data.profile.ProfileRepository
import chat.homebuddy.core.data.profile.RetrofitProfileRemoteDataSource
import chat.homebuddy.core.data.profile.RoomProfileLocalDataSource
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
    fun documentsLocalDataSource(database: HomeBuddyDatabase): DocumentsLocalDataSource =
        RoomDocumentsLocalDataSource(database.documents(), database.uploads())

    @Provides
    @Singleton
    fun documentsRemoteDataSource(api: HomeBuddyApi): DocumentsRemoteDataSource = RetrofitDocumentsRemoteDataSource(api)

    @Provides
    @Singleton
    fun documentsRepository(
        remote: DocumentsRemoteDataSource,
        local: DocumentsLocalDataSource,
        files: UploadFiles,
        scheduler: UploadScheduler,
    ): DocumentsRepository = CachedDocumentsRepository(remote, local, files, scheduler)

    @Provides
    @Singleton
    fun uploadProcessor(remote: DocumentsRemoteDataSource, local: DocumentsLocalDataSource, files: UploadFiles): UploadProcessor =
        UploadProcessor(remote, local, files)

    @Provides
    @Singleton
    fun profileLocalDataSource(database: HomeBuddyDatabase): ProfileLocalDataSource = RoomProfileLocalDataSource(database.profile())

    @Provides
    @Singleton
    fun profileRemoteDataSource(api: HomeBuddyApi): ProfileRemoteDataSource = RetrofitProfileRemoteDataSource(api)

    @Provides
    @Singleton
    fun profileRepository(remote: ProfileRemoteDataSource, local: ProfileLocalDataSource): ProfileRepository =
        CachedProfileRepository(remote, local)

    @Provides
    @Singleton
    fun conversationsLocalDataSource(database: HomeBuddyDatabase): ConversationsLocalDataSource =
        RoomConversationsLocalDataSource(database.conversations())

    @Provides
    @Singleton
    fun conversationsRemoteDataSource(api: HomeBuddyApi, streams: QueryStreamClient): ConversationsRemoteDataSource =
        RetrofitConversationsRemoteDataSource(api, streams)

    @Provides
    @Singleton
    fun conversationsRepository(remote: ConversationsRemoteDataSource, local: ConversationsLocalDataSource): ConversationsRepository =
        CachedConversationsRepository(remote, local)
}
