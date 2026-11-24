package app.chudmusic.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/**
 * Loads something once per page (kept while the page is in the back stack, so going back
 * does not fetch YouTube again), with [reload] for "Réessayer" / "Nouveau mix".
 */
class Loader<T>(private val block: suspend () -> T) : ViewModel() {
    var state by mutableStateOf<Load<T>>(Load.Loading)
        private set
    private var job: Job? = null

    init { reload() }

    fun reload() {
        job?.cancel()
        state = Load.Loading
        job = viewModelScope.launch {
            state = try {
                Load.Ok(withContext(Dispatchers.IO) { block() })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Err(e.message ?: e.javaClass.simpleName)
            }
        }
    }
}

@Composable
fun <T> rememberLoader(key: String, block: suspend () -> T): Loader<T> = viewModel(key = key) { Loader(block) }
