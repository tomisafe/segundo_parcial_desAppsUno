# Cambios realizados · Jornadas del Sur

Resolución de los pedidos 41 a 44 del README.

---

## 41 · Programa sin señal

**Problema.** Ante una falla, `Catalogo` reemplazaba la lista por el aviso, aunque hubiera datos guardados. Además, la app no podía distinguir "nunca se descargó" de "se descargó una edición vacía" (v4), porque en ambos casos la tabla queda vacía.

**Solución.**

1. **Nueva tabla `edicion` (fila única).** La fila existe si alguna vez se guardó una edición completa, aunque tenga cero charlas. Se escribe dentro de la misma transacción que las charlas.

   ```kotlin
   @Entity(tableName = "edicion")
   data class Edicion(@PrimaryKey val id: Int = 0)

   @Query("SELECT EXISTS(SELECT 1 FROM edicion)")
   abstract fun observarEdicion(): Flow<Boolean>
   ```

2. **Migración 1 → 2 sin pérdida de datos.** La versión instalada ya tiene usuarios con charlas guardadas. Por eso se sube `version = 2` y se registra la migración en el builder. La migración crea la tabla y, si el usuario ya tenía charlas, la marca como edición descargada.

   ```kotlin
   val MIGRACION_1_2 = object : Migration(1, 2) {
       override fun migrate(db: SupportSQLiteDatabase) {
           db.execSQL("CREATE TABLE IF NOT EXISTS `edicion` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
           db.execSQL("INSERT INTO edicion (id) SELECT 0 WHERE EXISTS (SELECT 1 FROM charlas)")
       }
   }
   ```

3. **Estados de la pantalla.** El ViewModel expone `fallo` (la última petición falló) y `hayEdicion` (hay una edición guardada), en lugar del texto `aviso`.

   | Situación | Qué se muestra |
   | --- | --- |
   | Falla **sin** edición previa | «No se pudo descargar el programa» + **Reintentar** |
   | Falla **con** edición previa | La lista normal + «Sin conexión: se están usando los datos guardados» |
   | Edición válida sin charlas (v4) | «Sin charlas programadas» (también al reabrir sin señal) |

   La ficha de detalle y Mi agenda leen de la base, así que se conservan ante una falla.

---

## 42 · Agenda personal

**Problema.** `guardar` usaba `@Insert(onConflict = REPLACE)` con `favorita = false` por defecto. Cada sincronización borraba todas las marcas. Las charlas retiradas del programa tampoco se eliminaban.

**Solución.** Se reemplazó por `aplicarEdicion`, una sola `@Transaction` que trata la respuesta como instantánea completa:

```kotlin
@Transaction
open suspend fun aplicarEdicion(fichas: List<ContenidoCharla>) {
    retirarAusentes(fichas.map { it.id })   // DELETE ... WHERE id NOT IN (:vigentes)
    insertarNuevas(fichas.map { Charla(...) }) // @Insert(onConflict = IGNORE), sin marcar
    actualizarContenido(fichas)             // @Update(entity = Charla::class) parcial
    registrarEdicion(Edicion())
}
```

- **Charlas retiradas** (la 11 en v3): se borran junto con su marca. Si regresan, regresan sin marcar.
- **Charlas nuevas** (la 90 en v3): entran sin marcar.
- **Charlas vigentes** (la 11 en v2): se actualiza solo su contenido. `ContenidoCharla` no tiene el campo `favorita`, así que el `@Update` parcial nunca la pisa.
- **Marca cambiada durante una petición:** la marca se lee al escribir, no al iniciar la petición, y Room serializa las escrituras. Por eso un toque en "Agregar/Quitar de mi agenda" durante la espera se respeta cuando llega la respuesta.
- **Respuesta vacía (v4):** se acepta como válida y deja el programa sin charlas.

`CarteleraDao` pasó de `interface` a `abstract class` para poder declarar el método `@Transaction` con cuerpo.

---

## 43 · Volver al punto de lectura

**Problema.**
- El filtro de tema era un `remember` dentro de `Catalogo`. Al abrir una ficha, `Catalogo` sale de la composición y el filtro se perdía.
- El filtro de día vivía en un `MutableStateFlow` del ViewModel. Sobrevivía a la rotación pero no a la muerte del proceso.

**Solución.** Ambos filtros pasan a `SavedStateHandle` en el ViewModel:

```kotlin
class ProgramaModel(app: Application, private val guardado: SavedStateHandle) : AndroidViewModel(app) {
    val dia: StateFlow<Int> = guardado.getStateFlow("dia", 0)
    val tema: StateFlow<String> = guardado.getStateFlow("tema", "Todos")
    fun elegirDia(valor: Int) { guardado["dia"] = valor }
    fun elegirTema(valor: String) { guardado["tema"] = valor }
}
```

- **Rotación, abrir la ficha, cambiar de pestaña:** los filtros se mantienen.
- **Proceso matado y tarea recuperada desde Recientes:** se restauran la ficha (ya estaba en `rememberSaveable` en `Portada`) y ambos filtros.
- **Tarea nueva:** arranca sin filtros, porque el estado guardado no se transfiere.

La UI ya no modifica el estado directamente (`model.dia.value = ...`): llama a `elegirDia` y `elegirTema`.

---

## 44 · Control de descargas

**Problema.**
- `MainActivity.onStart()` llamaba a `recargar()` en cada inicio, rotación y regreso desde segundo plano.
- `Catalogo` llamaba a `recargar()` en un `LaunchedEffect(Unit)` cada vez que entraba en composición: al rotar, al volver de una ficha o al cambiar de pestaña.
- Dos toques durante una petición lanzaban dos peticiones.
- `Servicios` se creaba una vez por ViewModel, así que el contador y la base no eran de la sesión (proceso).

**Solución.**

1. Se eliminaron la llamada en `onStart()` y el `LaunchedEffect` de `Catalogo`.
2. **Una carga automática por proceso**, controlada por una bandera en el `companion object` del ViewModel:

   ```kotlin
   fun cargaAutomatica() {
       if (cargaHechaEnElProceso) return
       cargaHechaEnElProceso = true
       recargar()
   }
   ```

   Se invoca en `MainActivity.onCreate` **después** de aplicar el extra `servicio_disponible`, para que la primera petición ya vea ese valor.

3. **Guardia contra el doble toque:**

   ```kotlin
   fun recargar() {
       if (cargando.value) return
       cargando.value = true
       ...
   }
   ```

   `finally` vuelve `cargando` a `false`, así que tras éxito o falla se puede volver a pedir. Los filtros siguen editables durante la espera.

4. **`Servicios` con una sola instancia por proceso** (`Servicios.de(context)`, con `applicationContext`). Así el contador de la Mesa, la base Room y el cliente Retrofit pertenecen a la sesión, y no a cada ViewModel.
