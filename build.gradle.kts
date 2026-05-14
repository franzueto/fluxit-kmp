// Root project. Module-specific build logic lives in build-logic convention
// plugins; this file only carries repo-wide concerns that don't fit cleanly
// inside a single module.

plugins {
    alias(libs.plugins.spotless)
}

// Repo-wide Markdown formatting via Spotless + flexmark (Java-based, no Node).
// Picked over prettier — which the original Phase 01 §8.3 spec suggested —
// because we don't want to require a Node/npm toolchain just to run a
// quality gate.
spotless {
    format("markdown") {
        target("**/*.md")
        targetExclude(
            "**/build/**",
            "**/node_modules/**",
            ".gradle/**",
            "**/.idea/**",
        )
        endWithNewline()
        trimTrailingWhitespace()
    }
}
