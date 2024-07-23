package com.stripe.android.paymentsheet

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.SavedStateHandle
import com.stripe.android.PaymentConfiguration
import com.stripe.android.common.exception.stripeErrorMessage
import com.stripe.android.core.strings.ResolvableString
import com.stripe.android.core.strings.resolvableString
import com.stripe.android.model.ConfirmPaymentIntentParams
import com.stripe.android.model.ConfirmSetupIntentParams
import com.stripe.android.model.ConfirmStripeIntentParams
import com.stripe.android.model.PaymentIntent
import com.stripe.android.model.SetupIntent
import com.stripe.android.model.StripeIntent
import com.stripe.android.payments.paymentlauncher.InternalPaymentResult
import com.stripe.android.payments.paymentlauncher.PaymentLauncher
import com.stripe.android.payments.paymentlauncher.PaymentLauncherContract
import com.stripe.android.payments.paymentlauncher.StripePaymentLauncherAssistedFactory
import com.stripe.android.paymentsheet.addresselement.AddressDetails
import com.stripe.android.paymentsheet.addresselement.toConfirmPaymentIntentShipping
import com.stripe.android.paymentsheet.model.PaymentSelection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Provider

/**
 * This interface handles the process of confirming a [StripeIntent]. This interface can only handle confirming one
 * intent at a time.
 */
internal class IntentConfirmationHandler(
    private val arguments: Args,
    private val intentConfirmationInterceptor: IntentConfirmationInterceptor,
    private val paymentLauncherFactory: (ActivityResultLauncher<PaymentLauncherContract.Args>) -> PaymentLauncher,
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val savedStateHandle: SavedStateHandle,
) {
    private var paymentLauncher: PaymentLauncher? = null
    private var deferredIntentConfirmationType: DeferredIntentConfirmationType?
        get() = savedStateHandle[DEFERRED_INTENT_CONFIRMATION_TYPE]
        set(value) {
            savedStateHandle[DEFERRED_INTENT_CONFIRMATION_TYPE] = value
        }

    private var completableResult: CompletableDeferred<Result>? = if (isAwaitingForPaymentResult()) {
        CompletableDeferred()
    } else {
        null
    }

    /**
     * Indicates if this handler has been reloaded from process death. This occurs if the handler was confirming
     * an intent before did not complete the process before process death.
     */
    val hasReloadedFromProcessDeath = isAwaitingForPaymentResult()

    /**
     * Registers activities tied to confirmation process to the lifecycle.
     *
     * @param activityResultCaller a class that can call [Activity.startActivityForResult]-style APIs
     * @param lifecycleOwner a class tied to an Android [Lifecycle]
     */
    fun register(activityResultCaller: ActivityResultCaller, lifecycleOwner: LifecycleOwner) {
        paymentLauncher = paymentLauncherFactory(
            activityResultCaller.registerForActivityResult(
                PaymentLauncherContract(),
                ::onPaymentResult
            )
        )

        lifecycleOwner.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    paymentLauncher = null
                    super.onDestroy(owner)
                }
            }
        )
    }

    /**
     * Starts the confirmation process with a given [StripeIntent] and [PaymentSelection] instance. Result from this
     * method can be received from [awaitIntentResult]. This method cannot return a result since the confirmation
     * process can be handed off to another [Activity] to handle after starting it.
     *
     * @param intent the Stripe intent to confirm
     * @param paymentSelection the customer's payment selection to use for confirming the intent
     */
    fun start(
        initializationMode: PaymentSheet.InitializationMode,
        intent: StripeIntent,
        paymentSelection: PaymentSelection?
    ) {
        if (completableResult?.isActive == true) {
            return
        }

        completableResult = CompletableDeferred()

        coroutineScope.launch {
            val nextStep = intentConfirmationInterceptor.intercept(
                initializationMode = initializationMode,
                paymentSelection = paymentSelection,
                shippingValues = arguments.shippingDetails?.toConfirmPaymentIntentShipping(),
                context = context,
            )

            deferredIntentConfirmationType = nextStep.deferredIntentConfirmationType

            when (nextStep) {
                is IntentConfirmationInterceptor.NextStep.HandleNextAction -> {
                    handleNextAction(
                        clientSecret = nextStep.clientSecret,
                        stripeIntent = intent,
                    )
                }
                is IntentConfirmationInterceptor.NextStep.Confirm -> {
                    confirmStripeIntent(nextStep.confirmParams)
                }
                is IntentConfirmationInterceptor.NextStep.Fail -> {
                    onFailure(
                        cause = nextStep.cause,
                        message = nextStep.message,
                        type = ErrorType.NextStep,
                    )
                }
                is IntentConfirmationInterceptor.NextStep.Complete -> {
                    onPaymentResult(InternalPaymentResult.Completed(intent))
                }
            }
        }
    }

    /**
     * Waits for an intent result to be returned following a call to start an intent confirmation process through the
     * [start] method. If no such call has been made, will return null.
     *
     * @return result of intent confirmation process or null if not started.
     */
    suspend fun awaitIntentResult(): Result? {
        return completableResult?.await()
    }

    private fun handleNextAction(
        clientSecret: String,
        stripeIntent: StripeIntent,
    ) = withPaymentLauncher { launcher ->
        /*
         * In case of process death, we should store that we waiting for a payment result to return from a
         * payment confirmation activity
         */
        storeIsAwaitingForPaymentResult()

        when (stripeIntent) {
            is PaymentIntent -> {
                launcher.handleNextActionForPaymentIntent(clientSecret)
            }
            is SetupIntent -> {
                launcher.handleNextActionForSetupIntent(clientSecret)
            }
        }
    }

    private fun confirmStripeIntent(
        confirmStripeIntentParams: ConfirmStripeIntentParams
    ) = withPaymentLauncher { launcher ->
        /*
         * In case of process death, we should store that we waiting for a payment result to return from a
         * payment confirmation activity
         */
        storeIsAwaitingForPaymentResult()

        when (confirmStripeIntentParams) {
            is ConfirmPaymentIntentParams -> {
                launcher.confirm(confirmStripeIntentParams)
            }
            is ConfirmSetupIntentParams -> {
                launcher.confirm(confirmStripeIntentParams)
            }
        }
    }

    private fun onPaymentResult(result: InternalPaymentResult) {
        val intentResult = when (result) {
            is InternalPaymentResult.Completed -> Result.Succeeded(
                intent = result.intent,
                deferredIntentConfirmationType = deferredIntentConfirmationType
            )
            is InternalPaymentResult.Failed -> Result.Failed(
                cause = result.throwable,
                message = result.throwable.stripeErrorMessage(),
                type = ErrorType.Payment,
            )
            is InternalPaymentResult.Canceled -> Result.Canceled
        }

        deferredIntentConfirmationType = null

        completableResult?.complete(intentResult)

        removeIsAwaitingForPaymentResult()
    }

    private fun onFailure(
        cause: Throwable,
        message: ResolvableString,
        type: ErrorType,
    ) {
        completableResult?.complete(
            Result.Failed(
                cause = cause,
                message = message,
                type = type,
            )
        )
    }

    private fun withPaymentLauncher(action: (PaymentLauncher) -> Unit) {
        paymentLauncher?.let(action) ?: run {
            onFailure(
                cause = IllegalArgumentException(
                    "No 'PaymentLauncher' instance was created before starting confirmation. Did you call register?"
                ),
                message = resolvableString(R.string.stripe_something_went_wrong),
                type = ErrorType.Fatal,
            )
        }
    }

    private fun storeIsAwaitingForPaymentResult() {
        savedStateHandle[AWAITING_PAYMENT_RESULT_KEY] = true
    }

    private fun removeIsAwaitingForPaymentResult() {
        savedStateHandle.remove<Boolean>(AWAITING_PAYMENT_RESULT_KEY)
    }

    private fun isAwaitingForPaymentResult(): Boolean {
        return savedStateHandle.get<Boolean>(AWAITING_PAYMENT_RESULT_KEY) ?: false
    }

    internal data class Args(
        val shippingDetails: AddressDetails?,
    )

    /**
     * Defines the result types that [IntentConfirmationHandler] can return after completing the confirmation process.
     */
    sealed interface Result {
        /**
         * Indicates that the confirmation process was canceled by the customer.
         */
        data object Canceled : Result

        /**
         * Indicates that the confirmation process has been successfully completed. A [StripeIntent] with an updated
         * state is returned as part of the result as well.
         */
        data class Succeeded(
            val intent: StripeIntent,
            val deferredIntentConfirmationType: DeferredIntentConfirmationType?
        ) : Result

        /**
         * Indicates that the confirmation process has failed. A cause and potentially a resolvable message are
         * returned as part of the result.
         */
        data class Failed(
            val cause: Throwable,
            val message: ResolvableString,
            val type: ErrorType,
        ) : Result
    }

    /**
     * Types of errors that can occur when confirming an intent.
     */
    enum class ErrorType {
        /**
         * Fatal confirmation error that occurred while confirming a payment. This should never happen.
         */
        Fatal,

        /**
         * Indicates an error when processing a payment during the confirmation process.
         */
        Payment,

        /**
         * Indicates an error occurred when determining the next step for the confirmation process.
         */
        NextStep,
    }

    class Factory(
        private val paymentSheetArguments: PaymentSheetContractV2.Args,
        private val intentConfirmationInterceptor: IntentConfirmationInterceptor,
        private val paymentConfigurationProvider: Provider<PaymentConfiguration>,
        private val stripePaymentLauncherAssistedFactory: StripePaymentLauncherAssistedFactory,
        private val savedStateHandle: SavedStateHandle,
        private val application: Application,
    ) {
        fun create(scope: CoroutineScope): IntentConfirmationHandler {
            return IntentConfirmationHandler(
                arguments = Args(
                    shippingDetails = paymentSheetArguments.config.shippingDetails,
                ),
                paymentLauncherFactory = { hostActivityLauncher ->
                    stripePaymentLauncherAssistedFactory.create(
                        publishableKey = { paymentConfigurationProvider.get().publishableKey },
                        stripeAccountId = { paymentConfigurationProvider.get().stripeAccountId },
                        hostActivityLauncher = hostActivityLauncher,
                        statusBarColor = paymentSheetArguments.statusBarColor,
                        includePaymentSheetAuthenticators = true,
                    )
                },
                intentConfirmationInterceptor = intentConfirmationInterceptor,
                context = application,
                coroutineScope = scope,
                savedStateHandle = savedStateHandle,
            )
        }
    }

    internal companion object {
        private const val AWAITING_PAYMENT_RESULT_KEY = "AwaitingPaymentResult"
        private const val DEFERRED_INTENT_CONFIRMATION_TYPE = "DeferredIntentConfirmationType"
    }
}
