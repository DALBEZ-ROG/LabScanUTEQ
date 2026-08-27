################################################################################
# Reglas de ofuscacion de LabScan UTEQ
#
# Regla general del archivo: cada bloque dice QUE conserva y POR QUE. Una regla
# sin motivo es una regla que nadie se atrevera a borrar cuando sobre.
################################################################################

# --- TensorFlow Lite -----------------------------------------------------------
#
# El interprete carga clases desde codigo nativo por su nombre completo. R8 no ve
# esas referencias, asi que sin estas reglas las renombra y la carga del modelo
# falla en tiempo de ejecucion con un ClassNotFoundException, ya empaquetado y
# firmado, que es el peor momento posible para enterarse.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# El delegado GPU es opcional: si el artefacto no esta, no hay que avisar.
-dontwarn org.tensorflow.lite.gpu.GpuDelegateFactory$Options

# --- kotlinx.serialization -----------------------------------------------------
#
# Los serializadores se generan en tiempo de compilacion y se buscan por reflexion
# a traves del companion object. Si R8 renombra las clases o borra los campos que
# nadie lee directamente, el JSON del backend deja de deserializarse: los campos
# llegan vacios y las secciones de la ficha aparecen en blanco, sin ningun error.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Los DTO del contrato y la configuracion del modelo, con sus serializadores.
-keep,includedescriptorclasses class ec.edu.uteq.labscan.data.remote.dto.**{ *; }
-keep,includedescriptorclasses class ec.edu.uteq.labscan.detection.ModelConfig { *; }
-keepclassmembers class ec.edu.uteq.labscan.data.remote.dto.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class ec.edu.uteq.labscan.detection.ModelConfig {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Retrofit y OkHttp ---------------------------------------------------------
#
# Retrofit construye la implementacion de LabScanApi por reflexion sobre las
# anotaciones y las firmas genericas de los metodos.
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations
-keep,allowobfuscation interface ec.edu.uteq.labscan.data.remote.LabScanApi
-keepclassmembers,allowshrinking,allowobfuscation interface ec.edu.uteq.labscan.data.remote.LabScanApi {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
# Retrofit referencia clases de la JVM de escritorio que en Android no existen.
-dontwarn java.lang.invoke.*
-dontwarn javax.annotation.**

# --- Coroutines ----------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# --- Diagnostico ---------------------------------------------------------------
#
# La pantalla de Diagnostico y el registro de errores muestran nombres de archivo y
# numeros de linea. Sin esto, un informe de fallo de la app publicada seria
# ilegible. Se renombra el archivo fuente a un token unico para no filtrar la
# estructura del proyecto, que es lo que recomienda R8.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
