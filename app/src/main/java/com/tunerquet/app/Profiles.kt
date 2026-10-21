package com.tunerquet.app

val Profile.displayName: String get() = when (this) {
    Profile.CONSERVATIVE -> "Conservative"
    Profile.BALANCED -> "Balanced"
    Profile.AGGRESSIVE -> "Aggressive"
    Profile.ESSENTIALS_ONLY -> "Essentials only"
    Profile.CUSTOM -> "Custom"
}

val Profile.description: String get() = when (this) {
    Profile.CONSERVATIVE -> "High-confidence optional packages only. A good starting point once device inventory is available."
    Profile.BALANCED -> "Additional verified optional components, with individual review before any change."
    Profile.AGGRESSIVE -> "Broader proposals with greater potential feature loss. Protected components remain locked."
    Profile.ESSENTIALS_ONLY -> "High risk: retain only verified essential TV features. Unknown dependencies must remain enabled."
    Profile.CUSTOM -> "Your own selection of verified optional packages. Core services and device dependencies remain locked. Saving and export are planned."
}
