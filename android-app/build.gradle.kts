plugins {
    id("fluxit.android.application")
}

dependencies {
    implementation(project(":shared:state"))
    implementation(project(":core:core-designsystem"))
    // Phase 06 Slice 7: MainActivity wires the §7 host-holder
    // (ActivityResultRegistryProvider) so AndroidPhotoCapture can present the
    // camera / picker against the resumed Activity's ActivityResultRegistry.
    implementation(project(":platform:platform-photo"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
}
