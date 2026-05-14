plugins {
    id("fluxit.android.application")
}

dependencies {
    implementation(project(":shared:state"))
    implementation(project(":core:core-designsystem"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)
}
