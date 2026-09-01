package io.github.bropines.tailscaled.appfunctions

import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceDelegate
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.internal.AppFunctionInventory
import androidx.appfunctions.internal.NullTranslatorSelector
import androidx.appfunctions.internal.`$AggregatedAppFunctionInventory_Impl`
import androidx.appfunctions.internal.`$AggregatedAppFunctionInvoker_Impl`
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bridges the system's execute requests to the KSP-generated invoker/inventory.
 *
 * This mirrors the library's own PlatformAppFunctionService (alpha10), which is
 * @RestrictTo(LIBRARY_GROUP) and therefore reimplemented here, with two
 * deliberate differences:
 * - the generated `$Aggregated...._Impl` classes are constructed directly
 *   instead of located through Dependencies' reflection lookup, so R8 cannot
 *   silently break the wiring;
 * - functions are invoked on [Dispatchers.IO] rather than Main, because
 *   several of them make blocking LocalAPI calls (Appctr.getStatusJSON etc.)
 *   and this service runs in the app's main process.
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA)
class TailSocksAppFunctionService : AppFunctionService() {

    private val inventory = `$AggregatedAppFunctionInventory_Impl`()
    private lateinit var delegate: AppFunctionServiceDelegate
    private lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        delegate = AppFunctionServiceDelegate(
            this,
            Dispatchers.IO,
            inventory,
            `$AggregatedAppFunctionInvoker_Impl`(),
            // No schema-defined functions in this app, so no translators apply.
            NullTranslatorSelector(),
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onExecuteFunction(
        request: ExecuteAppFunctionRequest,
        cancellationSignal: CancellationSignal,
        callback: Consumer<ExecuteAppFunctionResponse>,
    ) {
        val job = scope.launch {
            // The functions themselves return ERROR_CANCELLED when cancelled,
            // so the response is delivered unconditionally, as the library does.
            callback.accept(executeFunction(request))
        }
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    private suspend fun executeFunction(
        request: ExecuteAppFunctionRequest
    ): ExecuteAppFunctionResponse =
        try {
            delegate.executeFunction(request)
        } catch (e: AppFunctionException) {
            ExecuteAppFunctionResponse.Error(e)
        } catch (e: Exception) {
            ExecuteAppFunctionResponse.Error(AppFunctionAppUnknownException(e.message))
        }

    override fun resolveInventory(): AppFunctionInventory? = inventory
}
