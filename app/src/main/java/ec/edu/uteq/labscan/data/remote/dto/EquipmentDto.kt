package ec.edu.uteq.labscan.data.remote.dto

import kotlinx.serialization.Serializable

/**
 * Fuente citada por el backend o por el catalogo local.
 *
 * CLAUDE.md, regla 6: toda respuesta del asistente muestra su fuente. La ficha tecnica
 * sigue la misma norma.
 *
 * Espeja el objeto `sources[]` de `GET /api/equipment/{classId}` y de `POST /api/chat`
 * (docs/CONTRATO_API.md).
 */
@Serializable
data class SourceDto(
    val title: String = "",
    val page: Int? = null,
    val documentId: String = "",
    /** Solo lo devuelve `/api/chat`. En la ficha y en el catalogo local no viene. */
    val snippet: String? = null
)

/**
 * Ficha tecnica de un equipo.
 *
 * Espeja **campo a campo** la respuesta de `GET /api/equipment/{classId}` de
 * docs/CONTRATO_API.md. El mismo tipo se usa para dos origenes distintos:
 *
 * - `assets/catalog.json`, que es lo que lee [ec.edu.uteq.labscan.data.local.EquipmentCatalog],
 * - la respuesta del backend, que llega por [ec.edu.uteq.labscan.data.remote.LabScanApi].
 *
 * Por eso no hay dos modelos ni conversion entre ellos: el catalogo local es, literalmente,
 * respuestas del contrato guardadas en el APK. Eso es lo que hace que la ficha se vea igual
 * con red y sin ella.
 *
 * Todos los campos llevan valor por defecto porque el contrato permite que vengan vacios, y
 * la interfaz oculta sola las secciones sin contenido.
 *
 * Ningun campo necesita `@SerialName`: los nombres del contrato ya son camelCase y coinciden
 * con el nombre idiomatico en Kotlin. Ver docs/DECISIONES.md D-011.
 */
@Serializable
data class EquipmentDto(
    val classId: String = "",
    val displayName: String = "",
    val shortDescription: String = "",
    val function: String = "",
    val components: List<String> = emptyList(),
    val basicProcedure: List<String> = emptyList(),
    val ppe: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val relatedPractices: List<String> = emptyList(),
    val sources: List<SourceDto> = emptyList()
)
