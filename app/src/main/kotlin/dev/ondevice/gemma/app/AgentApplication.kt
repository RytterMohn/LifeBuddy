package dev.ondevice.gemma.app

import android.app.Application
import dev.ondevice.gemma.app.runtime.PhoneController
import dev.ondevice.gemma.app.data.AutoSkillStore
import dev.ondevice.gemma.app.i18n.AppLanguage
import android.content.res.Configuration

class AgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        PhoneController.initialize(this)
        AutoSkillStore.get(this).kick()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.systemChanged()
    }
}
