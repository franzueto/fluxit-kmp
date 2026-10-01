package com.fluxit.di

/**
 * Historical FB-207 comparator helper, unused by production bindings since FB-702.
 * Retained with its tests for FB-703 cleanup. Picks between the Firebase and Room implementation of a repository interface,
 * behind the `com.fluxit.config.FirebaseDevFlags.USE_FIREBASE_REPOSITORIES` temporary
 * development flag.
 *
 * A plain `if` inlined into each `actual platformModule()` would behave the same way, but
 * pulling the choice out here gives it a single, directly unit-testable definition
 * (`RepositoryBindingTest`, in `commonTest` so it runs on both platforms) that proves,
 * once, the property both platform modules rely on: only the selected branch is ever
 * invoked, so flipping the flag off never even constructs the Firebase side (no Firestore
 * instance, no listener, no uid resolution) and flipping it on never touches Room.
 */
internal inline fun <T> selectRepositoryBinding(
    useFirebaseRepositories: Boolean,
    firebase: () -> T,
    room: () -> T,
): T = if (useFirebaseRepositories) firebase() else room()
