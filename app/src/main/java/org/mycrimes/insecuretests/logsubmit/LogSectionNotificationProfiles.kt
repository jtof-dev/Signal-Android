package org.mycrimes.insecuretests.logsubmit

import android.content.Context
import org.mycrimes.insecuretests.database.SignalDatabase
import org.mycrimes.insecuretests.keyvalue.SignalStore
import org.mycrimes.insecuretests.notifications.profiles.NotificationProfile

class LogSectionNotificationProfiles : LogSection {
  override fun getTitle(): String = "NOTIFICATION PROFILES"

  override fun getContent(context: Context): CharSequence {
    val profiles: List<NotificationProfile> = SignalDatabase.notificationProfiles.getProfiles()

    val output = StringBuilder()

    output.append("Manually enabled profile: ${SignalStore.notificationProfileValues().manuallyEnabledProfile}\n")
    output.append("Manually enabled until  : ${SignalStore.notificationProfileValues().manuallyEnabledUntil}\n")
    output.append("Manually disabled at    : ${SignalStore.notificationProfileValues().manuallyDisabledAt}\n")
    output.append("Now                     : ${System.currentTimeMillis()}\n\n")

    output.append("Profiles:\n")
    if (profiles.isEmpty()) {
      output.append("  No notification profiles")
    } else {
      profiles.forEach { profile ->
        output.append("  Profile ${profile.id}\n")
        output.append("    allowMentions   : ${profile.allowAllMentions}\n")
        output.append("    allowCalls      : ${profile.allowAllCalls}\n")
        output.append("    schedule enabled: ${profile.schedule.enabled}\n")
        output.append("    schedule start  : ${profile.schedule.start}\n")
        output.append("    schedule end    : ${profile.schedule.end}\n")
        output.append("    schedule days   : ${profile.schedule.daysEnabled.sorted()}\n")
      }
    }

    return output.toString()
  }
}