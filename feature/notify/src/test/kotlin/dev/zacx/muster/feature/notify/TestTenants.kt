package dev.zacx.muster.feature.notify

/** The migrated tenant as the notifier sees it: legacy channel ids, namespaced tags, a label. */
val LEGACY = NotifyTenant("default", "m.example.test", 0xFF7C5CFF.toInt(), legacy = true)
