package ec.edu.uteq.labscan.data.remote

import ec.edu.uteq.labscan.data.remote.dto.ChatRequestDto
import ec.edu.uteq.labscan.data.remote.dto.ChatResponseDto
import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import ec.edu.uteq.labscan.data.remote.dto.HealthDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Los tres endpoints del backend RAG, tal como los fija docs/CONTRATO_API.md.
 *
 * Las rutas van **sin barra inicial** para que Retrofit las resuelva relativas a la
 * `BASE_URL` de la variante (`http://10.0.2.2:8000/` en depuracion). Con barra inicial,
 * Retrofit descartaria cualquier prefijo de ruta que tuviera la URL base.
 *
 * Nadie llama a esta interfaz directamente: [RagRepository] es el unico cliente, y es quien
 * convierte las excepciones de red en [RagError]. Las funciones son `suspend`, asi que
 * Retrofit las ejecuta en su propio hilo y devuelven el cuerpo ya deserializado; un codigo
 * fuera de 2xx llega como `retrofit2.HttpException`.
 */
interface LabScanApi {

    /** Disponibilidad del backend. La app la consulta al arrancar y al recuperar la red. */
    @GET("api/health")
    suspend fun health(): HealthDto

    /**
     * Ficha tecnica de un equipo.
     *
     * @param classId exactamente una linea de `labels.txt`, por ejemplo
     *   `microscopio_binocular`. Un `classId` desconocido responde 404.
     */
    @GET("api/equipment/{classId}")
    suspend fun equipment(@Path("classId") classId: String): EquipmentDto

    /**
     * Pregunta al asistente.
     *
     * El cuerpo lleva solo texto y el `classId` (CLAUDE.md, regla 5). El backend hace el
     * RAG sobre los manuales y devuelve la respuesta con sus fuentes.
     */
    @POST("api/chat")
    suspend fun chat(@Body request: ChatRequestDto): ChatResponseDto
}
