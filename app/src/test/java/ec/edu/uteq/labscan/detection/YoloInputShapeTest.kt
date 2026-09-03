package ec.edu.uteq.labscan.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cubre la validacion de la forma del tensor de entrada.
 *
 * Escrita a raiz de un fallo real del 2026-09-03: el primer modelo entrenado para la UTEQ
 * llego exportado en NCHW y la app se lo trago sin decir nada, quedandose sin detectar
 * durante toda una sesion. Ver D-029.
 */
class YoloInputShapeTest {

    @Test
    fun `una entrada NHWC cuadrada devuelve el lado`() {
        assertEquals(640, YoloTfliteDetector.validateInputShape(intArrayOf(1, 640, 640, 3)))
        assertEquals(416, YoloTfliteDetector.validateInputShape(intArrayOf(1, 416, 416, 3)))
    }

    @Test
    fun `una entrada NCHW se rechaza en lugar de interpretarse mal`() {
        // El caso real: [1, 3, 640, 640]. Antes de esta validacion, inputSize valia 3.
        val error = assertThrows(ModelMismatchException::class.java) {
            YoloTfliteDetector.validateInputShape(intArrayOf(1, 3, 640, 640))
        }
        val mensaje = error.message.orEmpty()
        assertTrue("El mensaje deberia citar la forma real: $mensaje", mensaje.contains("1x3x640x640"))
        assertTrue("El mensaje deberia nombrar NCHW: $mensaje", mensaje.contains("NCHW"))
        assertTrue("El mensaje deberia decir como arreglarlo: $mensaje", mensaje.contains("onnx2tf"))
    }

    @Test
    fun `el numero equivocado de dimensiones se rechaza`() {
        assertThrows(ModelMismatchException::class.java) {
            YoloTfliteDetector.validateInputShape(intArrayOf(1, 640, 640))
        }
        assertThrows(ModelMismatchException::class.java) {
            YoloTfliteDetector.validateInputShape(intArrayOf(1, 640, 640, 3, 1))
        }
    }

    @Test
    fun `una entrada no cuadrada se rechaza porque el letterbox produce cuadrados`() {
        val error = assertThrows(ModelMismatchException::class.java) {
            YoloTfliteDetector.validateInputShape(intArrayOf(1, 640, 480, 3))
        }
        assertTrue(error.message.orEmpty().contains("cuadrada"))
    }

    @Test
    fun `un numero de canales distinto de 3 se rechaza sin sugerir NCHW`() {
        // Escala de grises: es un error, pero NO es el caso NCHW, asi que la pista sobre
        // onnx2tf seria ruido que manda a Mario a reexportar por el motivo equivocado.
        val error = assertThrows(ModelMismatchException::class.java) {
            YoloTfliteDetector.validateInputShape(intArrayOf(1, 640, 640, 1))
        }
        assertTrue(
            "No deberia sugerir NCHW en un modelo de 1 canal: ${error.message}",
            !error.message.orEmpty().contains("NCHW")
        )
    }
}
