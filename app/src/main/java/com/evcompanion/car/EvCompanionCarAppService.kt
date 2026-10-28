package com.evcompanion.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class EvCompanionCarAppService : CarAppService() {

    // Accepts any car-app host. Fine for this private/testing-track app (it only displays
    // read-only vehicle data); a public release should restrict this to the real Android
    // Auto/Automotive OS host signatures via HostValidator.Builder(...).addAllowedHosts(...).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = EvCompanionSession()
}
