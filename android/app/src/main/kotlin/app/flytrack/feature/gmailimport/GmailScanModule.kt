package app.flytrack.feature.gmailimport

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class GmailScanModule {
    @Binds
    abstract fun bindTrackedKeys(impl: RepositoryTrackedKeysSource): TrackedKeysSource

    @Binds
    abstract fun bindPdfReader(impl: AndroidPdfTextReader): PdfTextReader
}
