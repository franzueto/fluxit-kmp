package dev.franzueto.fluxit.shared.state

expect class Platform() {
    val name: String
}

// Forces SKIE to emit Shared-Swift.h (sealed-class Swift exhaustive switch
// wrappers). Without at least one SKIE-decorated symbol, SKIE's fat-framework
// step crashes during XCFramework assembly. Replaceable once real shared MVI
sealed class PlatformKind {
    data object Android : PlatformKind()

    data object IOS : PlatformKind()
}
