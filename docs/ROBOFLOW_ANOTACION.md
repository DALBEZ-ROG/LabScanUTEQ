# Crear el proyecto de detección y anotar las 746 fotos

Guía para Mario, 2026-09-09. El proyecto anterior se creó como **Classification** y sus fotos
no tienen cuadros delimitadores, así que no sirve para entrenar un detector. Ver la celda 4b
de `docs/ENTRENAMIENTO_COLAB.md`.

Un proyecto de clasificación **no se puede convertir** en uno de detección. Hay que crear otro.

---

## 1. Crear el proyecto

**Create New Project** y en el tipo elige **Object Detection**. Es lo único de esta página
que no tiene vuelta atrás.

Deja el resto como venga. El nombre del proyecto da igual, no entra en ninguna parte del
código.

## 2. Subir las fotos

Arrastra las 54 carpetas de `ProyectoMobil/roboflow_upload/`. No subas los tres archivos que
empiezan por guion bajo (`_lista_clases.txt`, `_mapeo_clases.csv`, `_faltan_fotos.csv`), que
no son fotos.

Roboflow lee el nombre de la carpeta y lo propone como clase. **Eso ahorra la mitad del
trabajo de anotación**: al dibujar la caja la clase ya viene puesta y no hay que buscarla en
una lista de 54 cada vez.

Comprueba que las clases quedaron con el nombre exacto de la carpeta. Tienen que ser estas 54,
en `snake_case`, sin tildes y sin espacios:

```
agitador_calentador_thermo_scientific_cimarec
agitador_calentador_thermo_scientific_cimarec_plus
agitador_de_laboratorio_orbital_ms_nor_30_series
agitador_orbital_elmi_sky_line_dos_20l
analizador_dbo_velp_scientifica_bod_sensor
autoclave_gemmy_sturdy_sa_252f
balanza_analitica_ohaus_pioneer
balanza_de_laboratorio_ohaus_modelo_trooper
balanza_electronica_acculab_vicon
bano_maria_memmert_wb7
cabina_de_bioseguridad_labconco_purifier_clase_ii
cabina_de_flujo_laminar_vertical_c4_min
cabina_extractora_c4_cex_120
camara_de_aislamiento
camara_de_electroforesis_cleaver_maxfill_csu33
camara_de_electroforesis_owl_easycast_b1_bp
camara_de_electroforesis_owl_easycast_b2
centrifuga_ohaus_frontier_5718r
destilador_de_agua_metalico_ac_l4
escaner_de_cama_plana_flatbed_hp_scanjet_2400
espectrofotometro_thermo_nanodrop_lite_plus
espectrofotometro_visible_digital_unico_1205
esterilizador_a_presion_all_american_25x
esterilizador_a_vapor_sanyo_mls_3751l
esterilizador_binder_redline_ri_53
estufa_de_secado_memmert
fluorometro_invitrogen_qubit_q32857
fuente_de_poder_bio_rad_powerpac_3000
fuente_de_poder_labnet_enduro_300v
gps_portatil_de_mano_garmin_gpsmap_78
horno_de_secado_biobase_bov_d149
incubadora_agitadora_benchmark_incu_shaker_mini
luxometro_sper_scientific_840022
medidor_multiparametro_ohaus_aquasearcher
microcentrifuga_heal_force_neofuge_13r
microcentrifuga_labnet_prism_r
microcentrifuga_labnet_spectrafuge_24d
microondas_innova_in_mic_07bl
micropipeta_corning_lambda_plus
microscopio_compuesto_binocular_amscope_b120
microscopio_trinocular_xsp_500sm
ph_metro_electronico_digital_oakton_ph_510_series
ph_metro_ohaus_starter_3100
purificador_osmosis_inversa_do2_6_tapas
refractometro_manual_atago_atc_1e
refrigerador_vertical_ecasa_glc_175
refrigeradora_domestica_innova_modelo_everest_1800_nf
refrigeradora_indurama
refrigeradora_side_by_side_indurama_ri_780
secuenciador_de_adn_li_cor_4300
termociclador_applied_biosystems_miniamp_plus
termociclador_techne_tc_412
termociclador_tr_mgl48g
ultracongelador_haier_ult_freezer
```

Son las mismas de `docs/labels_v2.txt` y las mismas con las que **ya está indexado el backend
RAG**. Un nombre distinto aquí significa que el estudiante toque ese equipo en la app y el
asistente conteste que no tiene información, sin ningún error visible.

## 3. Anotar

### Usa Label Assist con tu modelo v1

Es lo único que acorta esto de verdad. En la pantalla de anotación, **Label Assist** te deja
elegir un modelo que prediga las cajas. Escoge tu detector v1 ya entrenado: reconoce casi los
mismos aparatos y va a predibujar la caja en la mayoría de las fotos.

Tu trabajo pasa de dibujar 746 cajas a revisar 746 cajas. Corriges la caja donde salga torcida
y la clase donde el nombre haya cambiado, porque el v1 tenía otros 50 nombres.

### Cómo dibujar la caja, y por qué la consistencia importa más que la precisión

La caja va **ceñida al equipo**, tocando sus bordes. Dentro va todo el aparato y nada más: ni
la mesa, ni la pared, ni el equipo de al lado.

Lo que de verdad hunde el mAP no es tener la caja dos píxeles corrida, es **cambiar de
criterio a mitad del dataset**. Decide una vez y no lo cambies:

- ¿La caja incluye el cable de alimentación? Recomendado: **no**.
- ¿Incluye el soporte, la base o el carro sobre el que está montado? Recomendado: **no**,
  salvo que sea parte del aparato, como el soporte de las micropipetas.
- ¿Y la tapa cuando está abierta? Recomendado: **sí**, es parte del equipo.
- Si en la foto salen dos equipos, **dibuja una caja por cada uno**, con su clase. Eso no es
  trabajo extra tirado: es exactamente lo que la app tiene que hacer en el laboratorio.

Ese último punto es el más valioso. Tus fotos son casi todas de un solo aparato llenando el
cuadro, y por eso el modelo v1 falla cuando apuntas a una mesa con tres equipos. Cada foto en
la que anotes dos o tres aparatos vale por muchas fotos de uno solo.

## 4. Generar la versión

### Preprocesado

- **Auto-Orient: activado.** Sin esto, las fotos tomadas con el teléfono en vertical entran
  giradas, porque la rotación vive en los metadatos EXIF y no en los píxeles.
- **Resize: 640×640, modo "Fit within"**, el que rellena los bordes. No uses "Stretch".

  La razón: la app **no** deforma la imagen, le pone relleno gris para hacerla cuadrada
  (`Letterbox.kt`). Si entrenas con fotos estiradas y luego el teléfono le pasa fotos con
  relleno, el modelo ve algo distinto de lo que aprendió. Ese desajuste es gratis de evitar
  y aquí importa, porque el problema conocido de este modelo es justamente que generaliza mal.

### Aumentos

Sí a estos, que imitan lo que va a pasar en el laboratorio con un teléfono en la mano:

- **Brillo ±25 %.** La iluminación del laboratorio cambia mucho entre mesas.
- **Rotación ±15 %.** Nadie sostiene el teléfono recto.
- **Desenfoque leve, hasta 1 o 2 px.** El enfoque automático no siempre llega.
- **Exposición ±10 %** si está disponible.

No a estos:

- **Volteo vertical.** Ningún equipo se va a ver del revés. Es ruido puro.
- **Volteo horizontal.** Es discutible, pero yo lo dejaría apagado: los paneles de control
  tienen texto y una disposición de botones concreta, y espejarlos enseña una geometría que
  no existe.

Multiplicador **3x**. Con las clases que tienen pocas fotos, más aumento ayuda.

### División

70 / 15 / 15 entre train, valid y test.

**Y aquí un aviso sobre el número que vas a poner en el informe.** Roboflow reparte al azar,
y tus fotos de un mismo equipo se parecen mucho entre sí. Eso hace que fotos casi idénticas
caigan una en train y otra en test, así que el mAP mide **memorización, más que
generalización**. Es lo que pasó con el 0,837 de la versión anterior, que luego en el
laboratorio no se sostuvo. Ver D-033.

La prueba honesta es apuntar el teléfono a un equipo real. Conviene decirlo así en el informe
en vez de citar el mAP a secas.

## 5. Exportar

**Export → YOLOv8 → show download code → Jupyter.** Copia el fragmento y sigue con
`docs/ENTRENAMIENTO_COLAB.md` desde la celda 3.

La celda 4b comprueba que esta vez sí hay cuadros. Tiene que imprimir miles de archivos de
etiquetas y una ruta de `data.yaml`.

---

## Ya que vas a pasar por cada foto: el reparto está muy desigual

Del paquete que subiste, contando fotos originales sin aumentar:

| Clase | Fotos |
|---|---|
| `analizador_dbo_velp_scientifica_bod_sensor` | 3 |
| `ph_metro_electronico_digital_oakton_ph_510_series` | 3 |
| `balanza_de_laboratorio_ohaus_modelo_trooper` | 4 |
| `balanza_electronica_acculab_vicon` | 4 |
| `cabina_de_bioseguridad_labconco_purifier_clase_ii` | 4 |
| `incubadora_agitadora_benchmark_incu_shaker_mini` | 4 |
| `luxometro_sper_scientific_840022` | 4 |
| `refrigerador_vertical_ecasa_glc_175` | 4 |
| `refrigeradora_indurama` | 4 |
| … | … |
| `centrifuga_ohaus_frontier_5718r` | 37 |
| `purificador_osmosis_inversa_do2_6_tapas` | 37 |

Con 3 o 4 fotos una clase no se aprende, se memoriza. Esas nueve van a salir mal casi seguro,
y la lista de las 15 peores clases de la celda 6 te lo va a confirmar con números.

Mientras anotas es buen momento para decidir si vale la pena volver al laboratorio con el
teléfono para las flacas. Con 15 o 20 fotos por clase, tomadas desde ángulos y distancias
distintos, cambia el resultado.
