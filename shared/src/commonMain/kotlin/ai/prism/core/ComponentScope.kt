package ai.prism.core

import com.arkivanov.essenty.lifecycle.LifecycleOwner
import com.arkivanov.essenty.lifecycle.doOnDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

/** Корутинная область, которая отменяется вместе с жизненным циклом компонента. */
fun LifecycleOwner.componentScope(context: CoroutineContext): CoroutineScope {
    val scope = CoroutineScope(context + SupervisorJob())
    lifecycle.doOnDestroy { scope.cancel() }
    return scope
}
