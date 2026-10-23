package com.music.spotui.data.update

internal data class UpdatePackageIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>, val minSdk: Int)

internal object UpdatePackagePolicy {
    fun error(installed: UpdatePackageIdentity, archive: UpdatePackageIdentity?, expectedCode: Long, deviceSdk: Int): String? = when {
        archive == null -> "הקובץ שהורד אינו קובץ התקנה תקין. נסו להוריד שוב."
        archive.packageName != installed.packageName -> "קובץ העדכון אינו מתאים לאפליקציה המותקנת."
        archive.versionCode != expectedCode || archive.versionCode <= installed.versionCode -> "גרסת הקובץ אינה תואמת לעדכון שפורסם."
        archive.signers.isEmpty() || installed.signers.isEmpty() || archive.signers != installed.signers -> "חתימת העדכון אינה תואמת לאפליקציה המותקנת."
        archive.minSdk > deviceSdk -> "העדכון דורש גרסת Android חדשה יותר מזו שבמכשיר."
        else -> null
    }
}
