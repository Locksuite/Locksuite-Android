package androidx.work

/**
 * Stub mínimo (7/11/2026, B.112). En WorkManager 2.9+ `Configuration.Provider` es una interfaz
 * de Kotlin con una PROPIEDAD (`workManagerConfiguration`), por eso este stub va en Kotlin y no
 * en Java: un getter de Java no se puede sobreescribir con `override val`.
 */
class Configuration private constructor() {
    interface Provider {
        val workManagerConfiguration: Configuration
    }

    class Builder {
        fun setMinimumLoggingLevel(loggingLevel: Int): Builder = this
        fun build(): Configuration = Configuration()
    }
}
