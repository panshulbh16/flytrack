package app.flytrack.feature.importticket

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ImportModule {
    @Binds
    @Singleton
    abstract fun bindExtractor(impl: AndroidTicketTextExtractor): TicketTextExtractor

    @Binds
    @Singleton
    abstract fun bindToday(impl: ClockImportToday): ImportToday
}
