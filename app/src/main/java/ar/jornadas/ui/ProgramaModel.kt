package ar.jornadas.ui

import android.app.Application
import androidx.lifecycle.*
import ar.jornadas.Servicios
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException

class ProgramaModel(app: Application, private val guardado: SavedStateHandle) : AndroidViewModel(app) {
    private val servicios = Servicios.de(app)
    val mesa = servicios.mesa
    private val repo = servicios.agenda
    val charlas = repo.programa.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hayEdicion = repo.hayEdicion.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Filtros: sobreviven a rotación y a muerte del proceso; una tarea nueva arranca sin ellos.
    val dia: StateFlow<Int> = guardado.getStateFlow("dia", 0)
    val tema: StateFlow<String> = guardado.getStateFlow("tema", "Todos")
    fun elegirDia(valor: Int) { guardado["dia"] = valor }
    fun elegirTema(valor: String) { guardado["tema"] = valor }

    val fallo = MutableStateFlow(false)
    val cargando = MutableStateFlow(false)

    fun cargaAutomatica() {
        if (cargaHechaEnElProceso) return
        cargaHechaEnElProceso = true
        recargar()
    }

    fun recargar() {
        if (cargando.value) return
        cargando.value = true
        fallo.value = false
        viewModelScope.launch {
            try {
                repo.sincronizar()
            } catch (e: IOException) {
                fallo.value = true
            } finally {
                cargando.value = false
            }
        }
    }

    fun marcar(id: String) { viewModelScope.launch { repo.cambiarFavorita(id) } }

    private companion object {
        var cargaHechaEnElProceso = false
    }
}


