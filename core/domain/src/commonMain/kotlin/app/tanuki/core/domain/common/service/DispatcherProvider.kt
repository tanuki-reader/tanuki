package app.tanuki.core.domain.common.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

/**
 * The set of coroutine dispatchers the app schedules work on, behind an interface so call sites never
 * name a concrete [Dispatchers] member, and so tests can inject a provider backed by a single
 * (e.g. `Unconfined`/test) dispatcher — keeping suspend/Flow work synchronous without each call site
 * hardcoding a dispatcher.
 *
 */
interface DispatcherProvider {
    /** CPU-bound work (sorting, parsing, mapping). Backed by [Dispatchers.Default]. */
    val default: CoroutineDispatcher

    /** Blocking I/O — SQLDelight/disk/network. Backed by [IO]. */
    val io: CoroutineDispatcher

    /** The platform main/UI thread. Backed by [Dispatchers.Main]. */
    val main: CoroutineDispatcher
}

/**
 * Production [DispatcherProvider] backed by the real [Dispatchers] pools. Bound app-wide via Metro so
 * repositories and use cases receive it by constructor injection.
 */
@Inject
@ContributesBinding(AppScope::class)
class StandardDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher = Dispatchers.Default
    override val io: CoroutineDispatcher = Dispatchers.IO
    override val main: CoroutineDispatcher = Dispatchers.Main
}