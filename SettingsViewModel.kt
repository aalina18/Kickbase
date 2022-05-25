package com.bmw.chargenow.presentation.views.settings.settings

import android.app.Activity
import androidx.annotation.VisibleForTesting
import com.bmw.chargenow.BuildConfig
import com.bmw.chargenow.R
import com.bmw.chargenow.business.services.billing.IPricingService
import com.bmw.chargenow.business.services.billing.PricingInfoState
import com.bmw.chargenow.business.services.brand.IFeatureFlagService
import com.bmw.chargenow.business.services.brand.RoutePlannerType.PREMIUM
import com.bmw.chargenow.business.services.user.IUserService
import com.bmw.chargenow.business.services.whitelabel.IWhiteLabelThemeService
import com.bmw.chargenow.data.openinformer.CustomerAccountType
import com.bmw.chargenow.presentation.utils.auth.IAppAuthLoginCommand
import com.bmw.chargenow.presentation.utils.lokalise.ILokaliseHelper
import com.bmw.chargenow.presentation.utils.market.IMarketManager
import com.bmw.chargenow.presentation.utils.phone.ITelephoneHelper
import com.bmw.chargenow.presentation.utils.whitelabeltheme.DefaultWhiteLabelThemeProvider
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.CallHotlineNumber
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ContractStateUpdate
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.HotlineNumberError
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.OnStatusBarColorChange
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.OnUserDataRetrieved
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.OnUserLoggedIn
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.OnUserLoggedOut
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ShowDebugSettingsSection
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ShowFaq
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ShowHotlineNumber
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ShowMyVehicle
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ShowPricing
import com.bmw.chargenow.presentation.views.settings.settings.SettingsRoute.ShowReimbursementSection
import com.bmw.chargenow.utils.BuildType
import com.bmw.chargenow.utils.ISchedulerProvider
import com.bmw.chargenow.utils.Optional
import com.bmw.chargenow.utils.analytics.AnalyticsEvent
import com.bmw.chargenow.utils.analytics.IAnalyticsTracker
import com.bmw.chargenow.utils.toNullable
import com.bmw.chargenow.utils.toOptional
import com.jakewharton.rxrelay3.BehaviorRelay
import com.jakewharton.rxrelay3.PublishRelay
import com.lokalise.sdk.LokaliseResources
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.rx3.asFlowable
import timber.log.Timber
import javax.inject.Inject

@VisibleForTesting
const val FAQ_URL_STRING_KEY = "faq_url"

@VisibleForTesting
const val FAQ_URL_FLEET_COUPLED_STRING_KEY = "faq_url_oem_fleet_coupling"

class SettingsViewModel @Inject constructor(
    private val telephoneHelper: ITelephoneHelper,
    private val analyticsTracker: IAnalyticsTracker,
    private val scheduler: ISchedulerProvider,
    private val userService: IUserService,
    private val lokaliseResources: LokaliseResources,
    private val appAuthLoginCommand: IAppAuthLoginCommand,
    private val featureFlagService: IFeatureFlagService,
    buildType: BuildType,
    pricingService: IPricingService,
    whiteLabelThemeService: IWhiteLabelThemeService,
    defaultWhiteLabelProvider: DefaultWhiteLabelThemeProvider,
    lokaliseHelper: ILokaliseHelper,
    marketManager: IMarketManager,
) : ISettingsViewModel {

    override val supportsWithdrawalSection: Boolean = featureFlagService.supportsWithdrawalSection

    private val userLogOutRelay = PublishRelay.create<Unit>()
    private val hotlineNumberRelay = PublishRelay.create<Unit>()
    private val callHotlineRelay = PublishRelay.create<String>()
    private val debugSettingsRelay = BehaviorRelay.createDefault(buildType)

    private val isLoggedInStream = userService.isLoggedIn().toFlowable()

    /** Since [isLoggedInStream] returns the status only once, the login status must be updated manually after logout */
    private val loginInAndOutStream = Flowable
        .merge(
            isLoggedInStream,
            userLogOutRelay.toFlowable(BackpressureStrategy.LATEST).map { false }
        )


    private val userLoggedInSource = loginInAndOutStream
        .map { isLoggedIn ->
            Timber.d("Login: SettingsVM loggedInAndOutStream with value '$isLoggedIn'")
            trackLoggedIn(isLoggedIn)
            if (isLoggedIn) {
                OnUserLoggedIn(featureFlagService.supportsInvoices)
            } else {
                OnUserLoggedOut
            }
        }

    private val statusBarUpdateSource = Flowable.combineLatest(
        whiteLabelThemeStream,
        loginInAndOutStream
    ) { whiteLabelThemeOptional, isLoggedIn ->
        OnStatusBarColorChange(
            isLoggedIn,
            whiteLabelThemeOptional.toNullable() ?: defaultWhiteLabelProvider.defaultWhiteLabelTheme
        )
    }

    private val showMyVehicleSource = loginInAndOutStream
        .flatMapSingle { featureFlagService.routePlannerType }
        .map { routePlannerType ->
            ShowMyVehicle(routePlannerType == PREMIUM)
        }

    private val userAccountTypeSource = isLoggedInStream
        .filter { loggedIn ->
            loggedIn
        }.flatMapSingle {
            Timber.d("Login: SettingsVM call userAccountType")
            userService.userAccountType
        }.map { accountType ->
            Timber.d("Login: SettingsVM map OnUserDataRetrieved")
            val mappedAccountType = when {
                featureFlagService.isFleetsAppOrFleetCoupled -> null
                accountType.toNullable() == CustomerAccountType.BUSINESS -> R.string.settings_account_type_business
                else -> R.string.settings_account_type_private
            }

            OnUserDataRetrieved(
                userService.userName,
                mappedAccountType,
            )
        }

    private val hotlineNumberStream = hotlineNumberRelay
        .toFlowable(BackpressureStrategy.LATEST)
        .flatMap {
            isLoggedInStream
        }
        .filter { loggedIn ->
            loggedIn
        }
        .flatMapSingle {
            telephoneHelper.fetchHotlineNumber()
                .onErrorReturn { "" }
        }

    private val hotlineNumberSource = hotlineNumberStream
        .map { number ->
            when (number) {
                "" -> HotlineNumberError
                else -> ShowHotlineNumber(number)
            }
        }

    private val callHotlineSource = callHotlineRelay.map { numberToCall ->
        CallHotlineNumber(numberToCall)
    }.toFlowable(BackpressureStrategy.LATEST)

    private val showPricingSectionSource = Flowable.fromSingle(pricingService.getPricingInfoState())
        .onErrorResumeWith(Flowable.empty())
        .map { pricingInfoState ->
            val show = pricingInfoState != PricingInfoState.HIDDEN &&
                pricingInfoState != PricingInfoState.LOGIN &&
                featureFlagService.supportsTariffsPdf
            ShowPricing(show)
        }

    private val reimbursementDetailsStream = loginInAndOutStream
        .filter { loggedIn ->
            loggedIn && featureFlagService.supportsReimbursement
        }.flatMapSingle {
            Timber.d("Login: SettingsVM call contracts")
            // Update the contracts to make sure the selected contract has up to date homeCharging info
            userService.contracts
        }.map {
            val selectedContract = userService.getSelectedContract()
            selectedContract?.hasHomeChargingAndReimbursement ?: false
        }.onErrorReturn { false }

    private val reimbursementDetailsSource = reimbursementDetailsStream
        /**
         * Reduce moving/laggy UI as we first attempt to use the existing contract,
         * then get the remotely fetched contract from reimbursementDetailsStream.
         */
        .startWithItem(
            userService.getSelectedContract()?.hasHomeChargingAndReimbursement ?: false
        ).filter { showReimbursement ->
            // requires second filter due to .startWithItem
            showReimbursement && featureFlagService.supportsReimbursement
        }
        .map {
            ShowReimbursementSection
        }

    private val debugSettingsSource = debugSettingsRelay.toFlowable(BackpressureStrategy.LATEST)
        .filter { buildType ->
            buildType == BuildType.BETA || buildType == BuildType.DEBUG
        }.map {
            ShowDebugSettingsSection
        }

    private val contractLockedSource = userService.isSelectedContractLocked
        .map { isLocked ->
            if (featureFlagService.isFleetsAppOrFleetCoupled) {
                ContractStateUpdate(false)
            } else {
                ContractStateUpdate(isLocked)
            }
        }

    private val faqSource = isLoggedInStream
        .filter { isLoggedIn ->
            isLoggedIn
        }.map {
            val url = if (featureFlagService.isFleetCoupled) {
                lokaliseHelper.getStringOrNullForSpecificLocale(
                    FAQ_URL_FLEET_COUPLED_STRING_KEY, marketManager.marketSpecificLocale
                )
            } else {
                lokaliseHelper.getStringOrNullForSpecificLocale(
                    FAQ_URL_STRING_KEY, marketManager.marketSpecificLocale
                )
            }

            ShowFaq(url)
        }

    private val sources: List<Flowable<out SettingsRoute>> = listOf(
        userLoggedInSource,
        statusBarUpdateSource,
        showMyVehicleSource,
        showPricingSectionSource,
        hotlineNumberSource,
        userAccountTypeSource,
        reimbursementDetailsSource,
        debugSettingsSource,
        contractLockedSource,
        faqSource,
        callHotlineSource
    )

    override val nextRoute: Flowable<SettingsRoute>
        get() = Flowable.merge(sources).observeOn(scheduler.mainThreadScheduler())

    override val versionInformation: String
        get() {
            val versionText = lokaliseResources.getString(
                R.string.settings_software_version_text
            )
            val versionName = BuildConfig.VERSION_NAME
            val versionCode = BuildConfig.VERSION_CODE
            return "$versionText: $versionName ($versionCode)"
        }

    override val supportsRegistration = featureFlagService.supportsRegistration

    override fun login(activity: Activity) {
        appAuthLoginCommand.execute(activity)
    }

    override fun logout() {
        userService.logout()
        userLogOutRelay.accept(Unit)
    }

    override fun fetchHotlineNumber() {
        hotlineNumberRelay.accept(Unit)
    }

    override fun getSelectedContractName(): String? {
        return if (featureFlagService.isFleetsAppOrFleetCoupled) {
            null
        } else {
            userService.getSelectedContract()?.contractName
        }
    }

    override fun callHotlineNumber(numberToCall: String) {
        analyticsTracker.trackEvent(AnalyticsEvent.DcsHotline)
        callHotlineRelay.accept(numberToCall)
    }

    private fun trackLoggedIn(loggedIn: Boolean) {
        analyticsTracker.trackPeopleEvent(
            mapOf(AnalyticsEvent.AnalyticsPropertiesKey.IS_SIGNED_IN to loggedIn.toString())
        )
    }
}
