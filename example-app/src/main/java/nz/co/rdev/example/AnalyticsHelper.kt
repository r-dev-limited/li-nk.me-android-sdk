package nz.co.rdev.example

import android.content.Context
import android.os.Bundle
import android.util.Log
import me.link.sdk.LinkPayload

/**
 * A helper class demonstrating how to map LinkMe payloads to various analytics providers.
 *
 * NOTE: This class contains commented-out code for the actual SDK calls.
 * You should uncomment the relevant sections after adding the corresponding dependencies to your build.gradle.
 */
object AnalyticsHelper {
    private const val TAG = "AnalyticsHelper"

    fun logToAnalytics(context: Context, payload: LinkPayload?) {
        val utm = payload?.utm ?: return

        Log.d(TAG, "Received LinkMe Payload with UTM: $utm")

        // 1. Log to Firebase Analytics (Google Analytics 4)
        logToFirebase(context, payload)

        // 2. Log to PostHog
        logToPostHog(payload)
    }

    private fun logToFirebase(context: Context, payload: LinkPayload) {
        val utm = payload.utm ?: return
        
        // Requires: implementation("com.google.firebase:firebase-analytics-ktx:...")
        // import com.google.firebase.analytics.FirebaseAnalytics
        // import com.google.firebase.analytics.FirebaseAnalytics.Event
        // import com.google.firebase.analytics.FirebaseAnalytics.Param

        /*
        val firebaseAnalytics = FirebaseAnalytics.getInstance(context)
        val bundle = Bundle().apply {
            // Map standard UTM keys to Firebase constants
            putString(FirebaseAnalytics.Param.SOURCE, utm["utm_source"])
            putString(FirebaseAnalytics.Param.MEDIUM, utm["utm_medium"])
            putString(FirebaseAnalytics.Param.CAMPAIGN, utm["utm_campaign"])
            putString(FirebaseAnalytics.Param.TERM, utm["utm_term"])
            putString(FirebaseAnalytics.Param.CONTENT, utm["utm_content"])
            
            // Add custom LinkMe ID
            putString("link_id", payload.linkId)
        }
        
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.CAMPAIGN_DETAILS, bundle)
        Log.d(TAG, "Logged CAMPAIGN_DETAILS to Firebase")
        */
        
        // Mock logging for example app
        Log.i(TAG, "[MOCK] Firebase logEvent(CAMPAIGN_DETAILS): source=${utm["utm_source"]}, medium=${utm["utm_medium"]}, campaign=${utm["utm_campaign"]}")
    }

    private fun logToPostHog(payload: LinkPayload) {
        val utm = payload.utm ?: return

        // Requires: implementation("com.posthog.android:posthog:...")
        // import com.posthog.android.PostHog

        /*
        // Option A: Identify the user with these properties (Attribution)
        PostHog.with(context).identify(
            distinctId = "user_123", // Use your actual user ID
            properties = mapOf(
                "utm_source" to utm["utm_source"],
                "utm_medium" to utm["utm_medium"],
                "utm_campaign" to utm["utm_campaign"],
                "utm_term" to utm["utm_term"],
                "utm_content" to utm["utm_content"]
            )
        )

        // Option B: Track a specific event
        PostHog.with(context).capture(
            event = "Deep Link Opened",
            properties = mapOf(
                "link_id" to payload.linkId,
                "path" to payload.path
            ) + utm // Add all UTM params
        )
        */

        // Mock logging for example app
        Log.i(TAG, "[MOCK] PostHog capture('Deep Link Opened'): $utm")
    }
}
