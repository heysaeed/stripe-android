package com.stripe.android.paymentelement.confirmation

import android.os.Parcelable
import androidx.activity.result.ActivityResultCaller
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.Turbine
import com.stripe.android.common.exception.stripeErrorMessage
import com.stripe.android.model.StripeIntent
import kotlinx.parcelize.Parcelize

internal class FakeConfirmationDefinition(
    private val onAction: (
        confirmationOption: ConfirmationHandler.Option.PaymentMethod.Saved,
        intent: StripeIntent
    ) -> ConfirmationDefinition.ConfirmationAction<LauncherArgs> = { _, _ ->
        val exception = IllegalStateException("Failed!")

        ConfirmationDefinition.ConfirmationAction.Fail(
            cause = exception,
            message = exception.stripeErrorMessage(),
            errorType = ConfirmationHandler.Result.Failed.ErrorType.Internal,
        )
    },
    private val confirmationResult: ConfirmationHandler.Result = ConfirmationHandler.Result.Canceled(
        action = ConfirmationHandler.Result.Canceled.Action.InformCancellation,
    ),
    private val launcher: Launcher = Launcher(),
) : ConfirmationDefinition<
    ConfirmationHandler.Option.PaymentMethod.Saved,
    FakeConfirmationDefinition.Launcher,
    FakeConfirmationDefinition.LauncherArgs,
    FakeConfirmationDefinition.LauncherResult
    > {
    private val _launchCalls = Turbine<LaunchCall>()
    val launchCalls: ReceiveTurbine<LaunchCall> = _launchCalls

    private val _createLauncherCalls = Turbine<CreateLauncherCall>()
    val createLauncherCalls: ReceiveTurbine<CreateLauncherCall> = _createLauncherCalls

    private val _toPaymentConfirmationResultCalls = Turbine<ToPaymentConfirmationResultCall>()
    val toPaymentConfirmationResultCalls: ReceiveTurbine<ToPaymentConfirmationResultCall> =
        _toPaymentConfirmationResultCalls

    override val key: String = "Test"

    override fun option(
        confirmationOption: ConfirmationHandler.Option
    ): ConfirmationHandler.Option.PaymentMethod.Saved? {
        return confirmationOption as? ConfirmationHandler.Option.PaymentMethod.Saved
    }

    override suspend fun action(
        confirmationOption: ConfirmationHandler.Option.PaymentMethod.Saved,
        intent: StripeIntent
    ): ConfirmationDefinition.ConfirmationAction<LauncherArgs> {
        return onAction(confirmationOption, intent)
    }

    override fun launch(
        launcher: Launcher,
        arguments: LauncherArgs,
        confirmationOption: ConfirmationHandler.Option.PaymentMethod.Saved,
        intent: StripeIntent
    ) {
        _launchCalls.add(
            LaunchCall(
                launcher = launcher,
                arguments = arguments,
                confirmationOption = confirmationOption,
                intent = intent,
            )
        )
    }

    override fun createLauncher(
        activityResultCaller: ActivityResultCaller,
        onResult: (LauncherResult) -> Unit
    ): Launcher {
        _createLauncherCalls.add(
            CreateLauncherCall(
                activityResultCaller = activityResultCaller,
                onResult = onResult,
            )
        )

        return launcher
    }

    override fun toPaymentConfirmationResult(
        confirmationOption: ConfirmationHandler.Option.PaymentMethod.Saved,
        deferredIntentConfirmationType: DeferredIntentConfirmationType?,
        intent: StripeIntent,
        result: LauncherResult
    ): ConfirmationHandler.Result {
        _toPaymentConfirmationResultCalls.add(
            ToPaymentConfirmationResultCall(
                confirmationOption = confirmationOption,
                deferredIntentConfirmationType = deferredIntentConfirmationType,
                intent = intent,
                result = result,
            )
        )

        return confirmationResult
    }

    class LaunchCall(
        val launcher: Launcher,
        val arguments: LauncherArgs,
        val confirmationOption: ConfirmationHandler.Option.PaymentMethod.Saved,
        val intent: StripeIntent
    )

    class CreateLauncherCall(
        val activityResultCaller: ActivityResultCaller,
        val onResult: (LauncherResult) -> Unit
    )

    class ToPaymentConfirmationResultCall(
        val confirmationOption: ConfirmationHandler.Option.PaymentMethod.Saved,
        val deferredIntentConfirmationType: DeferredIntentConfirmationType?,
        val intent: StripeIntent,
        val result: LauncherResult
    )

    class Launcher

    data class LauncherArgs(
        val amount: Long,
    )

    @Parcelize
    data class LauncherResult(
        val amount: Long,
    ) : Parcelable
}
