package ec.edu.uteq.labscan.ui.scanner

/**
 * Decide si la ficha se abre sola, y de cual de los equipos que hay en pantalla.
 *
 * ### Por que vive fuera del ViewModel
 *
 * Aqui no entra nada de Android. `Detection` lleva un `RectF`, y las clases de
 * `android.graphics` no funcionan en pruebas unitarias de JVM sin mas andamiaje, asi que
 * mientras esta logica estuvo dentro del ViewModel no se pudo cubrir con pruebas. Se trabaja
 * con [Candidata], que son cuatro numeros y una cadena, y el ViewModel hace la traduccion.
 *
 * Es la logica mas sutil de la pantalla y la que mas se corrigio contra el telefono real, asi
 * que es justo la que no puede quedarse sin red de seguridad.
 *
 * ### Las tres reglas, y de donde salio cada una
 *
 * **CUAL: la mas cercana al centro de la pantalla.** Con dos aparatos en el encuadre, elegir
 * el de mayor confianza es decidir por el estudiante y acierta la mitad de las veces. Apuntar
 * es como se elige con una camara en la mano. Se probo primero exigir que la caja CONTUVIERA
 * el centro y no servia: medido en el laboratorio con el telefono en horizontal, el centro
 * cayo en el hueco entre los dos aparatos y no se abria nada.
 *
 * **CUANDO: la misma clase varios fotogramas, y que en alguno haya llegado al umbral.** No se
 * exigen fotogramas consecutivos por encima del umbral. Medido en un SM-A566E, el mismo
 * aparato sin moverse dio 87 %, 65 % y 47 % en tres fotogramas seguidos; con esa exigencia la
 * ficha no se abria nunca.
 *
 * **QUE NO: lo que el estudiante acaba de cerrar.** Sin el veto, cerrar la ficha de un equipo
 * que sigue delante de la camara la reabre en el fotograma siguiente y la app queda
 * inservible. El veto se levanta cuando ese equipo desaparece de la vista, para que volver a
 * apuntarlo si la abra.
 *
 * @param umbral confianza minima para abrir. Mas alta que la de dibujar a proposito: dibujar
 *   un cuadro de mas solo estorba, abrir una ficha de mas interrumpe.
 * @param framesNecesarios fotogramas seguidos con la misma clase antes de abrir.
 */
internal class AutoOpenDecider(
    private val umbral: Float,
    private val framesNecesarios: Int
) {

    /**
     * Un equipo detectado, reducido a lo que hace falta para decidir.
     *
     * @param centerX centro de la caja en el eje X, normalizado 0..1 sobre el cuadrado del
     *   modelo. El relleno del letterbox es simetrico, asi que 0,5 es el centro del fotograma.
     * @param centerY lo mismo en el eje Y.
     */
    data class Candidata(
        val classId: String,
        val score: Float,
        val centerX: Float,
        val centerY: Float
    )

    /**
     * Mejor confianza de la racha que provoco la ultima apertura.
     *
     * Existe solo para el registro. Sin esto se anotaba la confianza del fotograma que
     * disparo, que puede estar por DEBAJO del umbral: la ficha se abre por la mejor de la
     * racha, no por la ultima. Se vio en el telefono un "Apertura automatica ... 0.62" con el
     * umbral en 0,70, y parece un fallo sin serlo.
     */
    var confianzaDeLaApertura: Float = 0f
        private set

    private var vetada: String? = null
    private var claseCandidata: String? = null
    private var frames = 0
    private var mejorScore = 0f

    /**
     * Devuelve el **indice** de la candidata cuya ficha hay que abrir, o `null`.
     *
     * Se devuelve el indice y no la candidata para que quien llama recupere su `Detection`
     * original, que es lo que necesita para pedir la ficha y resaltar la caja.
     */
    fun decidir(candidatas: List<Candidata>): Int? {
        val indice = indiceMasCentrado(candidatas)

        if (indice == null) {
            olvidarCandidata()
            // Nada en pantalla: se levanta el veto. Volver a apuntar el mismo equipo debe
            // abrir su ficha otra vez.
            vetada = null
            return null
        }

        val candidata = candidatas[indice]
        if (candidata.classId == vetada) return null

        if (candidata.classId == claseCandidata) {
            frames++
            mejorScore = maxOf(mejorScore, candidata.score)
        } else {
            claseCandidata = candidata.classId
            frames = 1
            mejorScore = candidata.score
        }

        if (frames >= framesNecesarios && mejorScore >= umbral) {
            confianzaDeLaApertura = mejorScore
            olvidarCandidata()
            return indice
        }
        return null
    }

    /**
     * El estudiante cerro la ficha. Esa clase queda vetada hasta que salga de la vista.
     *
     * @param classId la clase que estaba abierta, o `null` si se cerro sin equipo.
     */
    fun alCerrarFicha(classId: String?) {
        vetada = classId
        olvidarCandidata()
    }

    /** Olvida todo. Lo usa el cambio de camara: las cajas anteriores ya no valen. */
    fun reiniciar() {
        vetada = null
        olvidarCandidata()
    }

    private fun olvidarCandidata() {
        claseCandidata = null
        frames = 0
        mejorScore = 0f
    }

    /**
     * La candidata mas cercana al centro, **sin filtrar por umbral**.
     *
     * Es deliberado y es la parte que mas cuesta ver. Si aqui se descartaran las que no
     * llegan al umbral, el fotograma en que el aparato baja de 87 % a 65 % lo dejaria fuera,
     * la racha se reiniciaria y la ficha no se abriria nunca. El umbral se aplica una sola
     * vez, al final, sobre la MEJOR confianza vista mientras esa clase siguio siendo la
     * candidata.
     *
     * El precio es que una caja floja pero mejor centrada puede quitarle la candidatura a una
     * buena peor centrada. Se acepta: con la camara en la mano, lo que esta en el centro es
     * lo que el estudiante quiere mirar, y una caja floja no llega al umbral por si sola.
     */
    private fun indiceMasCentrado(candidatas: List<Candidata>): Int? {
        var mejor = -1
        var mejorDistancia = Float.MAX_VALUE
        for ((i, c) in candidatas.withIndex()) {
            val dx = c.centerX - 0.5f
            val dy = c.centerY - 0.5f
            val distancia = dx * dx + dy * dy
            if (distancia < mejorDistancia) {
                mejorDistancia = distancia
                mejor = i
            }
        }
        return if (mejor >= 0) mejor else null
    }
}
