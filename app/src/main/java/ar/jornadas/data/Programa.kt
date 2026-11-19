package ar.jornadas.data

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import retrofit2.http.GET
import kotlinx.coroutines.flow.Flow

data class FichaRemota(val id: String, val title: String, val track: String, val day: Int,
    val time: String, val room: String, val speaker: String, val summary: String)

interface ProgramaApi {
    @GET("programa") suspend fun consultar(): List<FichaRemota>
}

@Entity(tableName = "charlas")
data class Charla(@PrimaryKey val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String,
    val favorita: Boolean = false)

// Lo que publica la organización: todo menos la marca, que es del usuario.
data class ContenidoCharla(val id: String, val titulo: String, val tema: String, val dia: Int,
    val hora: String, val sala: String, val ponente: String, val resumen: String)

// Fila única: existe si alguna vez se guardó una edición completa (aunque tenga cero charlas).
@Entity(tableName = "edicion")
data class Edicion(@PrimaryKey val id: Int = 0)

@Dao
abstract class CarteleraDao {
    @Query("SELECT * FROM charlas ORDER BY dia, hora, sala")
    abstract fun observar(): Flow<List<Charla>>

    @Query("SELECT EXISTS(SELECT 1 FROM edicion)")
    abstract fun observarEdicion(): Flow<Boolean>

    @Query("UPDATE charlas SET favorita = NOT favorita WHERE id = :id")
    abstract suspend fun alternar(id: String)

    @Query("DELETE FROM charlas WHERE id NOT IN (:vigentes)")
    protected abstract suspend fun retirarAusentes(vigentes: List<String>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertarNuevas(fichas: List<Charla>)

    @Update(entity = Charla::class)
    protected abstract suspend fun actualizarContenido(fichas: List<ContenidoCharla>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun registrarEdicion(edicion: Edicion)

    // La instantánea reemplaza al programa: las retiradas se borran (con su marca),
    // las nuevas entran sin marcar y las vigentes actualizan su contenido sin tocar la
    // marca, que se lee en el momento de escribir y no al iniciar la petición.
    @Transaction
    open suspend fun aplicarEdicion(fichas: List<ContenidoCharla>) {
        retirarAusentes(fichas.map { it.id })
        insertarNuevas(fichas.map {
            Charla(it.id, it.titulo, it.tema, it.dia, it.hora, it.sala, it.ponente, it.resumen)
        })
        actualizarContenido(fichas)
        registrarEdicion(Edicion())
    }
}

@Database(entities = [Charla::class, Edicion::class], version = 2, exportSchema = false)
abstract class Archivo : RoomDatabase() {
    abstract fun cartelera(): CarteleraDao
}

// Las instalaciones de la versión 1 con charlas guardadas ya tenían una edición descargada.
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `edicion` (`id` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("INSERT INTO edicion (id) SELECT 0 WHERE EXISTS (SELECT 1 FROM charlas)")
    }
}
