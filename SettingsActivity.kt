package com.bmw.chargenow.presentation.views.settings.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import com.bmw.chargenow.DaggerInjector
import com.bmw.chargenow.R
import com.bmw.chargenow.business.exceptions.BrowserNotFoundException
import com.bmw.chargenow.business.services.brand.IFeatureFlagService
import com.bmw.chargenow.business.services.whitelabel.WhiteLabelTheme
import com.bmw.chargenow.databinding.SettingsActivityBinding
import com.bmw.chargenow.presentation.customviews.toolbar.HeadlineToolbar
import com.bmw.chargenow.presentation.extensions.getColor
import com.bmw.chargenow.presentation.extensions.openWebDashboard
import com.bmw.chargenow.presentation.utils.applauncher.ICustomTabLauncher
import com.bmw.chargenow.presentation.utils.notifications.IDialogHelper
import com.bmw.chargenow.presentation.utils.notifications.ISnackbarHelper
import com.bmw.chargenow.presentation.utils.notifications.addNegativeButton
import com.bmw.chargenow.presentation.utils.notifications.addPositiveButton
import com.bmw.chargenow.presentation.utils.phone.ITelephoneHelper
import com.bmw.chargenow.presentation.views.billing.chargingrecord.ChargingRecordActivity
import com.bmw.chargenow.presentation.views.billing.contract.ContractActivity
import com.bmw.chargenow.presentation.views.billing.invoice.InvoiceActivity
import com.bmw.chargenow.presentation.views.rating.ownratings.OwnRatingsActivity
import com.bmw.chargenow.presentation.views.settings.AbstractSettingsActivity
import com.bmw.chargenow.presentation.views.settings.DebugSettingsActivity
import com.bmw.chargenow.presentation.views.settings.legalweb.LegalActivity
import com.bmw.chargenow.presentation.views.settings.pricelist.PriceListActivity
import com.bmw.chargenow.presentation.views.settings.reimbursement.REIMBURSEMENT_MESSAGE_EXTRA
import com.bmw.chargenow.presentation.views.settings.reimbursement.ReimbursementActivity
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
import com.bmw.chargenow.presentation.views.settings.usagedata.UsageDataActivity
import com.bmw.chargenow.presentation.views.vehicle.CarSelectionOpeningMode.FROM_SETTINGS
import com.bmw.chargenow.utils.Environment
import com.bmw.chargenow.utils.exhaustive
import com.google.android.material.composethemeadapter.MdcTheme
import io.reactivex.rxjava3.disposables.CompositeDisposable
import timber.log.Timber
import javax.inject.Inject

private const val REIMBURSEMENT_REQUEST_CODE = 0

/**
 * Handles the functionality displayed in the More Section
 */
class SettingsActivity : AbstractSettingsActivity() {

    companion object {
        fun newIntent(context: Context): Intent {
            return Intent(context, SettingsActivity::class.java)
        }
    }

    @Inject
    lateinit var viewModel: ISettingsViewModel

    @Inject
    lateinit var telephoneHelper: ITelephoneHelper

    @Inject
    lateinit var subscriptions: CompositeDisposable

    @Inject
    lateinit var environment: Environment

    @Inject
    lateinit var featureFlagService: IFeatureFlagService

    @Inject
    lateinit var snackbarHelper: ISnackbarHelper

    @Inject
    lateinit var dialogHelper: IDialogHelper

    @Inject
    lateinit var customTabLauncher: ICustomTabLauncher

    private var isStatusBarLightLoggedOut: Boolean = false

    private lateinit var binding: SettingsActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        DaggerInjector.get().appComponent.inject(this)
        isStatusBarLightLoggedOut = resources.getBoolean(R.bool.LIGHT_LOGIN_STATUS_BAR)
        super.onCreate(savedInstanceState)

        binding = DataBindingUtil.setContentView(this, R.layout.settings_activity)
        binding.lifecycleOwner = this
        binding.whiteLabelThemePalette = whiteLabelThemePalette

        // Slide in from bottom animation for this activity, slide out to top for the previous one.
        overridePendingTransition(R.anim.slide_in_from_bottom, R.anim.stay)

        window.setBackgroundDrawable(null)
        binding.versionName.text = viewModel.versionInformation

        initClickListeners()
        initViews()
        setupBrandSpecificViews()
    }

    override fun onResume() {
        super.onResume()
        // Refresh the contract layout, useful if logged out unexpectedly.
        bindToViewModel()

        initContract()
        initHotline()
        initWithdrawal()
        showSupportHeader()
    }

    override fun onPause() {
        super.onPause()
        subscriptions.clear()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FROM_SETTINGS.requestCode && resultCode == Activity.RESULT_CANCELED) {
            snackbarHelper.showSnackbarError(binding.rootView, R.string.generic_error)
        } else if (requestCode == REIMBURSEMENT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val message = data?.extras?.getString(REIMBURSEMENT_MESSAGE_EXTRA)
            message?.let {
                snackbarHelper.showSnackbarSuccess(binding.rootView, null, it)
            }
        }
    }

    /**
     * Only re-enable the login button once the foreground LoginActivity was closed,
     * and this activity has focus again.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        binding.loginButton.isEnabled = hasFocus
        binding.registrationButton.isEnabled = hasFocus
    }

    /**
     * Clicks on the back button will show a slide in/out animation
     */
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(R.anim.stay, R.anim.slide_out_to_bottom)
    }

    private fun initViews() {
        binding.blockedAccountView.setContent {
            val whiteLabelTheme by this@SettingsActivity.whiteLabelThemePalette.getWhiteLabelThemeState()
            BlockedAccount(
                accountButtonListener = {
                    this.openWebDashboard(environment)
                },
                whiteLabelTheme = whiteLabelTheme
            )
        }
    }

    private fun setupBrandSpecificViews() {
        // Only show Ratings for supported brands.
        if (featureFlagService.supportsRating) {
            binding.ownReviews.visibility = VISIBLE
        }

        if (featureFlagService.supportsContractView) {
            binding.contractsList.visibility = VISIBLE
        }

        if (featureFlagService.supportsLogin) {
            binding.loginButton.visibility = VISIBLE
            binding.registrationButton.isVisible = viewModel.supportsRegistration
        }
    }

    private fun bindToViewModel() {
        subscriptions.add(
            viewModel.nextRoute
                .subscribe({
                    handleNextRoute(it)
                }, { e ->
                    Timber.e(e, "An error occurred at nextRoute().")
                })
        )
    }

    private fun handleNextRoute(route: SettingsRoute) {
        when (route) {
            is CallHotlineNumber -> {
                telephoneHelper.callNumber(this, telephoneHelper.getCallableHotlineNumber(route.numberToCall))
            }
            is ShowMyVehicle -> onShowMyVehicle(route.show)
            is OnUserLoggedIn -> onUserLoggedIn(route.supportsInvoices)
            is OnUserLoggedOut -> onUserLoggedOut()
            is ShowPricing -> onShowPricing(route.show)
            is ShowHotlineNumber -> onShowHotlineNumber(route.phoneNumber)
            is HotlineNumberError -> onHotlineNumberError()
            is OnUserDataRetrieved -> initUserData(route.accountType, route.userName)
            is ShowReimbursementSection -> onShowReimbursementSection()
            is ShowDebugSettingsSection -> onShowDebugSettingsSection()
            is ContractStateUpdate -> {
                binding.blockedAccountView.isVisible = route.isLocked
            }
            is OnStatusBarColorChange -> setStatusBarColor(route.isLoggedIn, route.whiteLabelTheme)
            is ShowFaq -> initFaq(route.url)
        }.exhaustive
    }

    private fun onShowDebugSettingsSection() {
        enableDebugSettings()
    }

    private fun onShowMyVehicle(show: Boolean) {
        binding.carSelectionSectionView.isVisible = show
    }

    private fun onHotlineNumberError() {
        binding.hotlineButton.visibility = GONE
        showSupportHeader()
    }

    private fun onShowPricing(show: Boolean) {
        binding.pricingInformation.visibility = if (show) VISIBLE else GONE
    }

    private fun onShowHotlineNumber(phoneNumber: String) {
        binding.hotlineButton.visibility = VISIBLE
        binding.hotlineButton.optionalText = phoneNumber
        showSupportHeader()
    }

    /**
     * Updates the UI to show the logged in state
     *
     * Hides login and register button
     * Shows logout button and user dependent items.
     */
    private fun onUserLoggedIn(supportsInvoices: Boolean) {
        with(binding) {
            loggedInToolbar.visibility = VISIBLE

            invoiceList.isVisible = supportsInvoices

            containerLoggedIn.visibility = VISIBLE
            logoutContainer.visibility = VISIBLE
            containerLegalLoggedIn.visibility = VISIBLE

            loggedOutClose.visibility = GONE
            loggedOutHeader.visibility = GONE
        }
    }

    /**
     * Updates the UI to show the logged out state
     *
     * Shows and enables login and register button,
     * Hides logout button and user dependent items
     */
    private fun onUserLoggedOut() {
        with(binding) {
            loginButton.isEnabled = true
            registrationButton.isEnabled = true

            loggedInToolbar.visibility = GONE
            containerLegalLoggedIn.visibility = GONE
            containerLoggedIn.visibility = GONE
            logoutContainer.visibility = GONE
            accountHeader.visibility = GONE
            reimbursementDetails.visibility = GONE

            loggedOutHeader.visibility = VISIBLE
            loggedOutClose.visibility = VISIBLE
        }
    }

    /**
     * Sets the status bar color depending on the logged in state
     *
     *  - Logged in, the status bar should be light.
     *  - Logged out, the status bar can be light, depending on the OEM configuration.
     */
    fun setStatusBarColor(isLoggedIn: Boolean, whiteLabelTheme: WhiteLabelTheme) {
        when {
            isLoggedIn -> {
                setLightStatusBar()
                window.statusBarColor = ContextCompat.getColor(this, R.color.white)
            }
            isStatusBarLightLoggedOut -> {
                setLightStatusBar()
                window.statusBarColor = whiteLabelTheme.viewSettingsLoginColor.getColor()
            }
            else -> {
                clearLightStatusBar()
                window.statusBarColor = whiteLabelTheme.primaryColor.getColor()
            }
        }
    }

    private fun initUserData(
        @StringRes accountType: Int?,
        userName: String?
    ) {
        binding.loggedInToolbar.setContent {
            MdcTheme {
                val whiteLabelTheme by whiteLabelThemePalette.getWhiteLabelThemeState()
                HeadlineToolbar(
                    title = userName ?: "",
                    subtitle = accountType?.let { getString(it) },
                    rightIconRes = R.drawable.ic_close,
                    whiteLabelTheme = whiteLabelTheme,
                    onRightIconClick = { onBackPressed() }
                )
            }
        }
    }

    private fun initContract() {
        val name = viewModel.getSelectedContractName()
        name?.let {
            binding.contractsList.optionalText = it
        } ?: run {
            binding.contractsList.optionalText = ""
        }
    }

    private fun initHotline() {
        if (!featureFlagService.supportsCustomerHotline) {
            return
        }
        if (featureFlagService.supportsLocalizedCustomerHotline) {
            showSupportHeader()
            binding.hotlineButton.visibility = VISIBLE
            binding.hotlineButton.optionalText = telephoneHelper.getFormattedHotlineNumber()
        } else {
            viewModel.fetchHotlineNumber()
        }
    }

    private fun initFaq(url: String?) {
        if (url == null || !customTabLauncher.isValidWebUrl(url)) {
            binding.faqButton.isVisible = false
            return
        }

        binding.faqButton.isVisible = true
        showSupportHeader()
        binding.faqButton.setOnClickListener {
            try {
                customTabLauncher.openLinkInCustomTab(Uri.parse(url), this@SettingsActivity)
            } catch (exception: BrowserNotFoundException) {
                showBrowserNotAvailableDialog()
            } catch (exception: Exception) {
                // DEVPD-24706: Add some feedback for the user
                Timber.e(exception, "Cannot parse '$url'")
            }
        }
    }

    private fun initWithdrawal() {
        binding.revocation.isVisible = viewModel.supportsWithdrawalSection
    }

    /**
     * Show Support header if at least one sub-item is visible
     */
    private fun showSupportHeader() = with(binding) {
        supportHeader.isVisible = faqButton.isVisible || hotlineButton.isVisible
    }

    /**
     * Show Account header if at least one sub-item is visible
     */
    private fun showAccountHeader() = with(binding) {
        accountHeader.isVisible = reimbursementDetails.isVisible
    }

    private fun onShowReimbursementSection() {
        binding.reimbursementDetails.visibility = VISIBLE
        showAccountHeader()
    }

    /**
     * Configures the listeners for each option in the bottom toolbar
     */
    private fun initClickListeners() {
        binding.loggedOutClose.setOnClickListener { onBackPressed() }

        binding.loginButton.setOnClickListener {
            login()
        }

        binding.registrationButton.setOnClickListener {
            register()
        }

        binding.contractsList.setOnClickListener {
            startActivity(ContractActivity.newIntent(this))
        }

        binding.ownReviews.setOnClickListener {
            startActivity(OwnRatingsActivity.newIntent(this))
        }

        binding.chargingRecordList.setOnClickListener {
            startActivity(ChargingRecordActivity.newIntent(this))
        }

        binding.invoiceList.setOnClickListener {
            startActivity(InvoiceActivity.newIntent(this))
        }

        binding.hotlineButton.setOnClickListener {
            callHotline()
        }

        binding.reimbursementDetails.setOnClickListener {
            startActivityForResult(ReimbursementActivity.newIntent(this), REIMBURSEMENT_REQUEST_CODE)
        }

        binding.termsAndConditions.setOnClickListener {
            startActivity(LegalActivity.newIntent(this, LegalActivity.Option.TERMS))
        }

        binding.revocation.setOnClickListener {
            startActivity(LegalActivity.newIntent(this, LegalActivity.Option.REVOCATION))
        }

        binding.usageData.setOnClickListener {
            startActivity(UsageDataActivity.newIntent(this))
        }

        binding.privacyInfo.setOnClickListener {
            startActivity(LegalActivity.newIntent(this, LegalActivity.Option.PRIVACY))
        }

        binding.imprintButton.setOnClickListener {
            startActivity(LegalActivity.newIntent(this, LegalActivity.Option.IMPRINT))
        }

        binding.pricingInformation.setOnClickListener {
            startActivity(PriceListActivity.newIntent(this))
        }

        binding.logoutButton.setOnClickListener {
            displayLogoutDialog()
        }
    }

    private fun callHotline() {
        val number = binding.hotlineButton.optionalText.toString()
        viewModel.callHotlineNumber(number)
    }

    private fun enableDebugSettings() {
        binding.debugSettings.visibility = VISIBLE
        binding.debugSettings.setOnClickListener {
            startActivity(DebugSettingsActivity.newIntent(this))
        }
    }

    private fun login() {
        try {
            binding.loginButton.isEnabled = false
            viewModel.login(this)
        } catch (exception: BrowserNotFoundException) {
            showBrowserNotAvailableDialog()
            binding.loginButton.isEnabled = true
        }
    }

    private fun register() {
        try {
            binding.registrationButton.isEnabled = false
            customTabLauncher.openLinkInCustomTab(Uri.parse(getString(R.string.REGISTRATION_URL)), this)
        } catch (exception: BrowserNotFoundException) {
            showBrowserNotAvailableDialog()
            binding.registrationButton.isEnabled = true
        } catch (exception: NullPointerException) {
            // This case should never happen, since the button is only shown if the Registration url is not blank
            Timber.e(exception, "Registration url is null")
            binding.registrationButton.isEnabled = true
        }
    }

    /**
     * Shows the logout dialog to the user.
     */
    private fun displayLogoutDialog() {
        dialogHelper.createWhitelabeledDialog(this, null, R.string.settings_logout_confirmation_message)
            .show {
                addNegativeButton(
                    R.string.dialog_cancel,
                    { dismiss() },
                    whiteLabelThemePalette.getCurrentWhiteLabelTheme()
                )
                addPositiveButton(
                    R.string.settings_logout,
                    {
                        viewModel.logout()
                        dismiss()
                    },
                    whiteLabelThemePalette.getCurrentWhiteLabelTheme(),
                    true
                )
            }
    }

    private fun showBrowserNotAvailableDialog() {
        dialogHelper.createBrowserNotFoundDialog(this, featureFlagService.loginUsesAppLinks).show()
    }
}
