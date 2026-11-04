package dev.jarvis.universal.control.actions

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import dev.jarvis.universal.control.ActionRegistry
import dev.jarvis.universal.control.ActionResult
import dev.jarvis.universal.control.ActionSpec
import dev.jarvis.universal.control.ArgSpec
import dev.jarvis.universal.control.ArgType
import dev.jarvis.universal.control.Args
import dev.jarvis.universal.control.Danger
import dev.jarvis.universal.control.PermissionKind
import dev.jarvis.universal.control.PermissionRequirement
import dev.jarvis.universal.util.Logx
import kotlinx.coroutines.delay
import java.util.ArrayList

/**
 * Contacts, calls and SMS through the documented Android interfaces.
 *
 * Honesty rules enforced here:
 *  - placing a call reports what Telecom accepted, and whether a call is actually in progress
 *    after a short verification delay; it never claims a call "connected";
 *  - sending an SMS reports hand-off to the mobile network, not delivery;
 *  - per-SIM routing is only used when a SIM can be mapped to a Telecom account with public
 *    APIs, otherwise the result says Android picked the default SIM.
 */
object CommsActions {

    private const val TAG = "CommsActions"

    fun register(registry: ActionRegistry, deps: ActionDeps) {

        registry.register(
            ActionSpec(
                id = "contacts_search",
                description = "Search the owner's contacts and return matching names with phone numbers.",
                args = listOf(
                    ArgSpec("query", ArgType.STRING, required = true, maxLength = 60),
                    ArgSpec("limit", ArgType.INTEGER, required = false, min = 1.0, max = 20.0)
                ),
                permissions = listOf(
                    PermissionRequirement(
                        Manifest.permission.READ_CONTACTS,
                        "Contacts",
                        PermissionKind.RUNTIME
                    )
                ),
                danger = Danger.SENSITIVE
            ) { args -> searchContacts(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "list_sims",
                description = "List SIM subscriptions with slot, carrier and display name where Android permits.",
                permissions = listOf(
                    PermissionRequirement(
                        Manifest.permission.READ_PHONE_STATE,
                        "Phone state",
                        PermissionKind.RUNTIME
                    )
                )
            ) { listSims(deps) }
        )

        registry.register(
            ActionSpec(
                id = "call_contact",
                description = "Place a phone call to a contact name or number, optionally choosing a SIM slot.",
                args = listOf(
                    ArgSpec("contact", ArgType.STRING, required = false, maxLength = 80),
                    ArgSpec("number", ArgType.STRING, required = false, maxLength = 24),
                    ArgSpec("sim_slot", ArgType.INTEGER, required = false, min = 0.0, max = 3.0)
                ),
                permissions = listOf(
                    PermissionRequirement(Manifest.permission.READ_CONTACTS, "Contacts", PermissionKind.RUNTIME)
                ),
                requiresConfirmation = true,
                danger = Danger.SENSITIVE
            ) { args -> call(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "sms_compose",
                description = "Open the messaging app with an SMS pre-filled. Nothing is sent.",
                args = listOf(
                    ArgSpec("contact", ArgType.STRING, required = false, maxLength = 80),
                    ArgSpec("number", ArgType.STRING, required = false, maxLength = 24),
                    ArgSpec("body", ArgType.STRING, required = false, maxLength = 900)
                ),
                permissions = listOf(
                    PermissionRequirement(Manifest.permission.READ_CONTACTS, "Contacts", PermissionKind.RUNTIME)
                ),
                requiresConfirmation = true,
                danger = Danger.SENSITIVE
            ) { args -> composeSms(deps, args) }
        )

        registry.register(
            ActionSpec(
                id = "sms_send",
                description = "Send an SMS directly through the mobile network.",
                args = listOf(
                    ArgSpec("contact", ArgType.STRING, required = false, maxLength = 80),
                    ArgSpec("number", ArgType.STRING, required = false, maxLength = 24),
                    ArgSpec("body", ArgType.STRING, required = true, maxLength = 900)
                ),
                permissions = listOf(
                    PermissionRequirement(Manifest.permission.SEND_SMS, "Send SMS", PermissionKind.RUNTIME),
                    PermissionRequirement(Manifest.permission.READ_CONTACTS, "Contacts", PermissionKind.RUNTIME)
                ),
                requiresConfirmation = true,
                danger = Danger.DESTRUCTIVE
            ) { args -> sendSms(deps, args) }
        )
    }

    // ---------------------------------------------------------------- contacts

    data class Contact(val name: String, val number: String)

    fun search(deps: ActionDeps, query: String, limit: Int): List<Contact> {
        val resolver: ContentResolver = deps.app.contentResolver
        val uri: Uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection =
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val args = arrayOf("%$query%", "%$query%")
        val out = mutableListOf<Contact>()
        try {
            resolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(projection[0])
                val numberIdx = cursor.getColumnIndex(projection[1])
                while (cursor.moveToNext() && out.size < limit * 3) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val number = cursor.getString(numberIdx) ?: continue
                    if (out.any { it.number == number }) continue
                    out.add(Contact(name, number))
                }
            }
        } catch (se: SecurityException) {
            Logx.w(TAG, "contacts query denied")
        }
        return out.take(limit)
    }

    private fun searchContacts(deps: ActionDeps, args: Args): ActionResult {
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return ActionResult.fail("missing_argument", "Tell me who to look for.")
        val limit = args.int("limit") ?: 5
        val matches = search(deps, query, limit)
        if (matches.isEmpty()) {
            return ActionResult.ok("I found no contact matching $query.", mapOf("count" to 0))
        }
        val names = matches.map { "${it.name}: ${it.number}" }
        return ActionResult.ok(
            spoken = "I found ${matches.size} match${if (matches.size == 1) "" else "es"}: " +
                matches.joinToString(", ") { "${it.name}, ${it.number}" } + ".",
            data = mapOf("count" to matches.size, "contacts" to matches.map { mapOf("name" to it.name, "number" to it.number) }, "lines" to names)
        )
    }

    // ---------------------------------------------------------------- SIMs

    private fun subscriptionManager(deps: ActionDeps): SubscriptionManager? =
        deps.app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    private fun activeSubscriptions(deps: ActionDeps): List<SubscriptionInfo> = try {
        subscriptionManager(deps)?.activeSubscriptionInfoList.orEmpty()
    } catch (se: SecurityException) {
        Logx.w(TAG, "subscription list denied (missing READ_PHONE_STATE?)")
        emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    private fun listSims(deps: ActionDeps): ActionResult {
        val subs = activeSubscriptions(deps)
        if (subs.isEmpty()) {
            return ActionResult.ok(
                "I cannot see any active SIM subscriptions. Phone permission may be missing, or this device has no SIM.",
                mapOf("count" to 0)
            )
        }
        val rows = subs.map { info ->
            mapOf(
                "slot" to info.simSlotIndex,
                "subscription_id" to info.subscriptionId,
                "carrier" to (info.carrierName?.toString() ?: "unknown"),
                "display_name" to (info.displayName?.toString() ?: "unknown"),
                "number" to (runCatching { info.number }.getOrNull() ?: "")
            )
        }
        val spoken = subs.joinToString(". ") {
            "${it.displayName ?: "SIM"} in slot ${it.simSlotIndex}, ${it.carrierName ?: "unknown carrier"}"
        } + "."
        return ActionResult.ok(spoken, mapOf("count" to subs.size, "sims" to rows))
    }

    /**
     * Maps a SIM slot to a Telecom phone account when public APIs allow it.
     * Returns null when the mapping is not determinable; callers must say so.
     */
    private fun phoneAccountForSlot(deps: ActionDeps, slot: Int?): PhoneAccountHandle? {
        if (slot == null) return null
        val telecom = deps.app.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
        val accounts = try {
            telecom.callCapablePhoneAccounts
        } catch (t: Throwable) {
            return null
        }
        if (accounts.isEmpty()) return null
        if (accounts.size == 1) return accounts.first()
        val subs = activeSubscriptions(deps)
        val target = subs.firstOrNull { it.simSlotIndex == slot } ?: return null
        val iccid = runCatching { target.iccId }.getOrNull()
        if (!iccid.isNullOrBlank()) {
            accounts.firstOrNull { it.id == iccid }?.let { return it }
        }
        // Some OEMs use the subscription id as the handle id.
        accounts.firstOrNull { it.id == target.subscriptionId.toString() }?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- calls

    data class ResolvedTarget(val displayName: String, val number: String)

    private fun resolveTarget(deps: ActionDeps, args: Args): Result<ResolvedTarget> {
        val rawNumber = args.string("number")?.trim()
        if (!rawNumber.isNullOrBlank()) {
            if (!rawNumber.matches(Regex("[+0-9 ()-]{3,24}"))) {
                return Result.failure(IllegalArgumentException("That does not look like a phone number."))
            }
            return Result.success(ResolvedTarget(rawNumber, rawNumber))
        }
        val name = args.string("contact")?.trim()
        if (name.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("Tell me who to contact or which number to use."))
        }
        val matches = search(deps, name, 5)
        val exact = matches.filter { it.name.equals(name, ignoreCase = true) }
        val chosen = when {
            exact.size == 1 -> exact.first()
            matches.size == 1 -> matches.first()
            matches.isEmpty() -> return Result.failure(
                NoSuchElementException("I could not find a contact matching $name.")
            )
            else -> return Result.failure(
                IllegalArgumentException(
                    "I found several contacts matching $name: " +
                        matches.joinToString(", ") { it.name } + ". Which one?"
                )
            )
        }
        return Result.success(ResolvedTarget(chosen.name, chosen.number))
    }

    private suspend fun call(deps: ActionDeps, args: Args): ActionResult {
        val target = resolveTarget(deps, args).getOrElse { error ->
            val ambiguous = error is IllegalArgumentException
            return ActionResult(
                ok = false,
                spoken = error.message ?: "I could not resolve that contact.",
                error = if (ambiguous) "ambiguous_or_invalid" else "contact_not_found"
            )
        }
        val slot = args.int("sim_slot")
        val handle = phoneAccountForSlot(deps, slot)
        val telecom = deps.app.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return ActionResult.fail("telecom_unavailable", "Calling is not available on this device.")
        val uri = Uri.parse("tel:${Uri.encode(target.number)}")
        val extras = Bundle().apply {
            if (handle != null) putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        val hasCallPermission = deps.permissions.isGranted(Manifest.permission.CALL_PHONE)
        if (!hasCallPermission) {
            val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                deps.app.startActivity(dial)
                ActionResult(
                    ok = true,
                    spoken = "I opened the dialer for ${target.displayName}. I cannot place calls without phone permission, so you need to press call.",
                    data = mapOf("dialer_opened" to true, "placed" to false, "number" to target.number),
                    error = null
                )
            } catch (t: Throwable) {
                ActionResult.fail("dial_failed", "I could not open the dialer.")
            }
        }
        return try {
            telecom.placeCall(uri, extras)
            // Give Telecom a moment, then verify whether a call is actually in progress.
            delay(1400)
            val inCall = runCatching { telecom.isInCall }.getOrDefault(false)
            val simNote = when {
                handle != null -> "using the SIM you selected"
                slot != null -> "using Android's default SIM; I could not verify the selected SIM on this device"
                else -> "using Android's default SIM"
            }
            ActionResult(
                ok = true,
                spoken = "Calling ${target.displayName} $simNote. " +
                    if (inCall) "The call is in progress." else "Android accepted the call request; I cannot confirm whether it connected.",
                data = mapOf(
                    "number" to target.number,
                    "sim_selected" to (handle != null),
                    "in_call" to inCall,
                    "connected" to false,
                    "note" to "Android does not expose call connection state to third-party apps."
                )
            )
        } catch (se: SecurityException) {
            ActionResult.fail("call_permission", "I need phone permission to place calls.")
        } catch (t: Throwable) {
            Logx.e(TAG, "placeCall failed", t)
            ActionResult.fail("call_failed", "Android refused the call request.")
        }
    }

    // ---------------------------------------------------------------- SMS

    private fun composeSms(deps: ActionDeps, args: Args): ActionResult {
        val target = resolveTarget(deps, args).getOrElse { error ->
            return ActionResult(
                ok = false,
                spoken = error.message ?: "I could not resolve that contact.",
                error = "contact_not_found"
            )
        }
        val body = args.string("body").orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target.number)}")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (body.isNotBlank()) putExtra("sms_body", body)
        }
        return try {
            deps.app.startActivity(intent)
            ActionResult.ok(
                "I drafted the message to ${target.displayName}. Press send in your messaging app to send it.",
                mapOf("drafted" to true, "sent" to false, "number" to target.number)
            )
        } catch (t: Throwable) {
            ActionResult.fail("compose_failed", "No messaging app accepted the draft.")
        }
    }

    private fun sendSms(deps: ActionDeps, args: Args): ActionResult {
        val target = resolveTarget(deps, args).getOrElse { error ->
            return ActionResult(
                ok = false,
                spoken = error.message ?: "I could not resolve that contact.",
                error = "contact_not_found"
            )
        }
        val body = args.string("body")?.trim().orEmpty()
        if (body.isEmpty()) return ActionResult.fail("missing_argument", "What should the message say?")
        return try {
            val manager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                deps.app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val sms = manager
                ?: return ActionResult.fail("sms_unavailable", "No SMS service is available on this device.")
            val parts = sms.divideMessage(body)
            sms.sendMultipartTextMessage(
                target.number,
                null,
                ArrayList(parts),
                null,
                null
            )
            ActionResult.ok(
                "I handed the message to the mobile network for ${target.displayName} " +
                    "in ${parts.size} part${if (parts.size == 1) "" else "s"}. " +
                    "Delivery confirmation is not available to me.",
                mapOf(
                    "handed_off" to true,
                    "delivered" to false,
                    "parts" to parts.size,
                    "number" to target.number
                )
            )
        } catch (se: SecurityException) {
            ActionResult.fail("sms_permission", "I need SMS permission to send messages.")
        } catch (t: Throwable) {
            Logx.e(TAG, "sendSms failed", t)
            ActionResult.fail("sms_failed", "Android rejected the SMS.")
        }
    }
}
