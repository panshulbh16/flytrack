package app.flytrack.di

import javax.inject.Qualifier

/** Dispatcher for blocking or network work (Dispatchers.IO in production). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher for CPU-bound work such as parsing (Dispatchers.Default in production). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** A CoroutineScope that lives as long as the process, for work that must outlive a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
