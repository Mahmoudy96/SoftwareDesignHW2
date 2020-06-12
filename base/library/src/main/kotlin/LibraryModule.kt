import Utils.statsStorage
import Utils.torrentStorage
import Utils.peerStorage
import Storage.*
import Storage.PeerStorage
import Storage.TorrentStorage
import com.google.inject.Inject
import com.google.inject.Provides
import com.google.inject.Singleton
import dev.misfitlabs.kotlinguice4.KotlinModule

import il.ac.technion.cs.softwaredesign.storage.*

class LibraryModule : KotlinModule() {
    override fun configure() {
        bind<Peer>().to<PeerStorage>()
        bind<Statistics>().to<StatisticsStorage>()
        bind<Torrent>().to<TorrentStorage>()
        bind<TorrentStats>().to<TorrentStatistics>()
        bind<File>().to<FileStorage>()
        bind<Info>().to<InfoStorage>()
        bind<Bitfield>().to<BitfieldStorage>()

    }

    //Binding with annotations(tutorial on Guice )
    @Provides
    @Singleton
    @Inject
    @torrentStorage
    fun provideTorrentStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("torrent".toByteArray()).get()

    }

    @Provides
    @Singleton
    @Inject
    @statsStorage
    fun provideStatsStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("statistics".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @peerStorage
    fun providePeerStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("peers".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.torrentStats
    fun providesTorrentStatsStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("torrentStats".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.fileStorage
    fun providesFileStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("fileStorage".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.infoStorage
    fun providesInfoStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("infoStorage".toByteArray()).get()
    }

    @Provides
    @Singleton
    @Inject
    @Utils.bitfieldStorage
    fun providesBitfieldStorage(factory: SecureStorageFactory): SecureStorage {
        return factory.open("bitfieldStorage".toByteArray()).get()
    }
}