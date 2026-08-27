// Build de nivel raiz. Los plugins se declaran aqui sin aplicar y cada modulo los activa.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
