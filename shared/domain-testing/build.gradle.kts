plugins {
    id("fluxit.kmp.library")
}

android {
    namespace = "dev.franzueto.fluxit.shared.domaintesting"
}

// Shared test-fixtures module (Phase 05 Slice 4). Holds the in-memory repository
// fakes + port fakes (`FakeListsRepository`, `FakeItemsRepository`,
// `FakeRemindersRepository`, `FakePhotosRepository`, `FakeClock`,
// `FakeReminderScheduler`, `FakePhotoCapture`, `FakePhotoStorage`) that were
// originally authored in `:shared:domain` commonTest. They live in `commonMain`
// here — not a test source set — so they can be consumed as a plain dependency
// from BOTH `:shared:domain` commonTest (where they started) and `:shared:state`
// commonTest (where `ListsDashboardStore` tests need the full Lists/Items surface).
// KMP has no first-class cross-module test-fixtures sharing, so a dedicated module
// is the idiomatic answer. The fakes keep their original package paths
// (`…domain.repository`, `…domain.port`) so domain's own tests need no import
// changes; the matching `Fake*Test.kt` exercising them stay in domain commonTest.
kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api`: the fakes implement domain repository/port interfaces and
            // expose domain model types on their surface, so consumers must see
            // domain transitively.
            api(project(":shared:domain"))
            // `api`: the fake repositories' public constructors take `IdGenerator`
            // (a core-utils type the domain port aliases), so consumers building the
            // fakes need core-utils on their compile classpath.
            api(project(":core:core-utils"))
            implementation(libs.bundles.coroutines)
            implementation(libs.kotlinx.datetime)
        }
    }
}
