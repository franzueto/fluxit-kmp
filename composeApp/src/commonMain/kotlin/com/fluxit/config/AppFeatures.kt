package com.fluxit.config

/**
 * Per-platform feature switches, bound by each `platformModule()`.
 *
 * Hiding a feature here is a UX decision only. On web, account creation is enforced
 * off by disabling client sign-up in Firebase Authentication, not by this flag.
 */
data class AppFeatures(
    /** Offer the create-account flow on the auth screen. */
    val allowSignUp: Boolean,
    /** Offer the dashboard's sample-data seeder. */
    val allowSampleData: Boolean,
) {
    companion object {
        /** Android and iOS development builds: every flow is available. */
        val Mobile = AppFeatures(allowSignUp = true, allowSampleData = true)

        /** Web: sign in to an existing account only, no debug seeding. */
        val Web = AppFeatures(allowSignUp = false, allowSampleData = false)
    }
}
