package ar.jornadas.data

class AgendaRepository(private val api: ProgramaApi, private val dao: CarteleraDao) {
    val programa = dao.observar()
    val hayEdicion = dao.observarEdicion()

    suspend fun sincronizar() {
        val fichas = api.consultar().map {
            ContenidoCharla(it.id, it.title, it.track, it.day, it.time, it.room, it.speaker, it.summary)
        }
        dao.aplicarEdicion(fichas)
    }

    suspend fun cambiarFavorita(id: String) = dao.alternar(id)
}
