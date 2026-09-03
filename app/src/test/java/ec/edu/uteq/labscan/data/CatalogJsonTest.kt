package ec.edu.uteq.labscan.data

import ec.edu.uteq.labscan.data.remote.dto.EquipmentDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifica que `assets/catalog.json` encaja con el contrato.
 *
 * Corre en la JVM leyendo el archivo directamente del modulo, sin dispositivo. Su valor es
 * que rompe la compilacion si alguien edita el catalogo a mano y se equivoca en un nombre de
 * campo: en la app ese error seria silencioso, porque `ignoreUnknownKeys` haria que el campo
 * mal escrito simplemente se ignorara y la seccion apareciera vacia.
 *
 * `catalog.json` contiene respuestas de `GET /api/equipment/{classId}` guardadas en el APK,
 * asi que si esta prueba pasa, el mismo JSON valdria como respuesta del backend de F5.
 */
class CatalogJsonTest {

    private val catalog: List<EquipmentDto> by lazy {
        val file = File("src/main/assets/catalog.json")
        assertTrue("No se encontro ${file.absolutePath}", file.exists())
        // Sin ignoreUnknownKeys a proposito: aqui SI queremos que un campo desconocido falle.
        Json.decodeFromString<List<EquipmentDto>>(file.readText())
    }

    @Test
    fun `el catalogo se deserializa con los DTO del contrato`() {
        assertTrue("El catalogo esta vacio", catalog.isNotEmpty())
    }

    @Test
    fun `cada ficha tiene los campos minimos para ser util`() {
        catalog.forEach { equipment ->
            assertTrue("classId vacio en $equipment", equipment.classId.isNotBlank())
            assertTrue("displayName vacio en ${equipment.classId}", equipment.displayName.isNotBlank())
            assertTrue("Sin descripcion: ${equipment.classId}", equipment.shortDescription.isNotBlank())
            assertTrue("Sin procedimiento: ${equipment.classId}", equipment.basicProcedure.isNotEmpty())
            assertTrue("Sin riesgos: ${equipment.classId}", equipment.risks.isNotEmpty())
            assertTrue("Sin fuentes: ${equipment.classId}", equipment.sources.isNotEmpty())
        }
    }

    @Test
    fun `los classId son unicos`() {
        val ids = catalog.map { it.classId }
        assertEquals("Hay classId repetidos", ids.size, ids.toSet().size)
    }

    /**
     * Ninguna ficha puede referirse a una clase que el modelo no detecta.
     *
     * Esta es la direccion de la comprobacion que importa, y hasta 2026-09-03 estaba al
     * reves: se exigia que **toda** clase de `labels.txt` tuviera ficha. Esa version paso a
     * ser imposible de cumplir en cuanto llego el modelo real —4 fichas contra 50 clases— y
     * dejo la suite en rojo. Ver D-029.
     *
     * La cobertura completa del catalogo NO es un invariante del proyecto: `catalog.json` es
     * un respaldo **sin conexion** y la app ya resuelve la clase sin ficha con la ficha
     * minima ("Ficha no disponible"). Quien tiene que responder de las 50 clases es el
     * backend RAG, con los manuales reales y citando la fuente.
     *
     * Lo que si es un defecto silencioso es una ficha huerfana: nunca se mostrara, porque el
     * modelo no puede emitir esa clase, y nadie se entera. Asi murieron `incubadora` y
     * `vortex`, que sobrevivieron al cambio de `labels.txt` sin que ninguna prueba chistara.
     */
    @Test
    fun `ninguna ficha apunta a una clase que el modelo no puede detectar`() {
        val labels = File("src/main/assets/labels.txt").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        val huerfanas = catalog.map { it.classId } - labels
        assertTrue(
            "Fichas de catalog.json que no corresponden a ninguna clase de labels.txt " +
                "(nunca se mostraran): $huerfanas",
            huerfanas.isEmpty()
        )
    }

    @Test
    fun `las fuentes citan documento y titulo`() {
        catalog.flatMap { it.sources }.forEach { source ->
            assertTrue("Fuente sin titulo: $source", source.title.isNotBlank())
            assertTrue("Fuente sin documentId: $source", source.documentId.isNotBlank())
        }
    }
}
