package com.lightningkite.lightningserver.security

import com.lightningkite.lightningserver.db.Condition
import com.lightningkite.lightningserver.db.FieldCollection
import com.lightningkite.lightningserver.db.HasEmail
import com.lightningkite.lightningserver.db.HasId
import com.lightningkite.lightningserver.db.interceptChangePerInstance
import com.lightningkite.lightningserver.db.interceptCreate
import com.lightningkite.lightningserver.db.postChange
import com.lightningkite.lightningserver.db.postCreate
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.db.ModelInfo
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailClient
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.schedule.Schedule
import com.lightningkite.lightningserver.schedule.ScheduledTask
import com.lightningkite.lightningserver.schedule.schedule
import com.lightningkite.lightningserver.settings.generalSettings
import kotlinx.coroutines.flow.toList
import kotlin.time.Duration

fun <MODEL, ID : Comparable<ID>> FieldCollection<MODEL>.adminSecurityInterceptors(
    email: () -> EmailClient,
    whitelistedDomains: Set<String>,
    isAdmin: Condition<MODEL>
): FieldCollection<MODEL> where MODEL : HasId<ID>, MODEL : HasEmail {
    suspend fun allAdmins() = find(isAdmin)
    suspend fun informNewAdmin(newAdmin: MODEL) {
        email().send(
            Email(
                subject = "New Admin on ${generalSettings().projectName} - ${newAdmin.email}",
                to = listOf(),
                bcc = allAdmins().toList().map { EmailLabeledValue(it.email) },
                // TODO: Context?
                plainText = """
                A new admin (${newAdmin.email}) has been added to ${generalSettings().projectName}.
                If this is not correct, please go shut down the account immediately and inform your developers who will investigate.
            """.trimIndent()
            )
        )
    }
    return this
        .interceptCreate { new ->
            if (isAdmin(new) && new.email.substringAfterLast('@') !in whitelistedDomains) {
                generalSettings().emergencyContact?.let {
                    email().send(
                        Email(
                            subject = "Suspicious add admin attempt for ${generalSettings().projectName}",
                            to = listOf(EmailLabeledValue(it, "${generalSettings().projectName} Admin")),
                            plainText = "An attempt to add ${new.email} as an administrator was made, which did not succeed because ${
                                new.email.substringAfterLast(
                                    '@'
                                )
                            } is not in the whitelist (${whitelistedDomains.joinToString()})."
                        )
                    )
                }
                throw BadRequestException("${new.email} is not in the whitelisted domains.")
            }
            new
        }
        .interceptChangePerInstance(true) { item, mod ->
            val new = mod(item)
            if (isAdmin(new) && new.email.substringAfterLast('@') !in whitelistedDomains) {
                generalSettings().emergencyContact?.let {
                    email().send(
                        Email(
                            subject = "Suspicious add admin attempt for ${generalSettings().projectName}",
                            to = listOf(EmailLabeledValue(it, "${generalSettings().projectName} Admin")),
                            plainText = "An attempt to add ${new.email} as an administrator was made, which did not succeed because ${
                                new.email.substringAfterLast(
                                    '@'
                                )
                            } is not in the whitelist (${whitelistedDomains.joinToString()})."
                        )
                    )
                }
                throw BadRequestException("${new.email} is not in the whitelisted domains.")
            }
            mod
        }
        .postCreate { new ->
            if (isAdmin(new)) {
                informNewAdmin(new)
            }
        }
        .postChange { old, new ->
            if (isAdmin(new) && !isAdmin(old)) {
                informNewAdmin(new)
            }
        }
}

fun <USER : HasId<*>?, T, ID : Comparable<ID>> ServerPath.adminReportSchedule(
    info: ModelInfo<USER, T, ID>,
    email: () -> EmailClient,
    frequency: Duration,
    isAdmin: Condition<T>
): ScheduledTask where T : HasId<ID>, T : HasEmail = schedule("$this/adminReportSchedule", frequency) {
    val admins = info.collection().find(isAdmin).toList()
    email().send(
        Email(
            subject = "Admin List Check In for ${generalSettings().projectName}",
            to = admins.map { EmailLabeledValue(it.email) },
            plainText = """
                This is a regular check-in (every $frequency) for who should administrate ${generalSettings().projectName}.
                
                These users have administrative access:
                
            """.trimIndent() + admins.joinToString("\n", "", "\n\n") { it.email } + """
                Please review these individuals and remove any who no longer need administrative access.
            """.trimIndent()
        )
    )
}