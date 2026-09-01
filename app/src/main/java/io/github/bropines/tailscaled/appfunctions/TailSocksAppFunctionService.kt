package io.github.bropines.tailscaled.appfunctions

import androidx.appfunctions.AppFunctionService

// NOTE: still abstract — enabling this needs the androidx AppFunctions alpha10
// onExecuteFunction wiring to the generated invoker, which must be verified on
// device. The functions themselves are gated and corrected so they behave once
// this is finished.
abstract class TailSocksAppFunctionService : AppFunctionService()
