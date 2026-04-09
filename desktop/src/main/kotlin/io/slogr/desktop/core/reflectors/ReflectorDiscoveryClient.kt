package io.slogr.desktop.core.reflectors

import io.slogr.agent.platform.config.AgentState
import io.slogr.desktop.core.state.DesktopStateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Discovers Slogr reflectors via `GET /v1/reflectors` (or mock data in dev mode).
 *
 * Orchestrates: cache check → API call → nearest selection → state update.
 */
class ReflectorDiscoveryClient(
    private val cache: ReflectorCache,
    private val stateManager: DesktopStateManager,
) {

    companion object {
        /** Set to true when GET /v1/reflectors is deployed on L3 BFF. */
        var USE_REAL_API = false

        private val MOCK_REFLECTORS = listOf(
            Reflector(
                id = "00000000-0000-0000-0000-000000000001",
                region = "us-east",
                cloud = "aws",
                host = "127.0.0.1",
                port = 862,
                latitude = 39.0438,
                longitude = -77.4874,
                tier = "free",
            ),
            Reflector(
                id = "00000000-0000-0000-0000-000000000002",
                region = "eu-west",
                cloud = "aws",
                host = "127.0.0.1",
                port = 862,
                latitude = 53.3498,
                longitude = -6.2603,
                tier = "free",
            ),
            Reflector(
                id = "00000000-0000-0000-0000-000000000003",
                region = "ap-southeast",
                cloud = "aws",
                host = "127.0.0.1",
                port = 862,
                latitude = 1.3521,
                longitude = 103.8198,
                tier = "free",
            ),
            Reflector(
                id = "00000000-0000-0000-0000-000000000010",
                region = "us-west",
                cloud = "aws",
                host = "127.0.0.1",
                port = 862,
                latitude = 45.5231,
                longitude = -122.6765,
                tier = "paid",
            ),
            Reflector(
                id = "00000000-0000-0000-0000-000000000011",
                region = "me-south",
                cloud = "aws",
                host = "127.0.0.1",
                port = 862,
                latitude = 25.276987,
                longitude = 55.296249,
                tier = "paid",
            ),
        )

        private val MOCK_RESPONSE = ReflectorDiscoveryResponse(
            reflectors = MOCK_REFLECTORS,
            yourRegion = "pk-sindh",
            yourIp = "203.0.113.45",
        )
    }

    private val _reflectors = MutableStateFlow<List<Reflector>>(emptyList())
    val reflectors: StateFlow<List<Reflector>> = _reflectors.asStateFlow()

    private val _userRegion = MutableStateFlow<String?>(null)
    val userRegion: StateFlow<String?> = _userRegion.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Discover reflectors: use cache if valid, otherwise call API (or mock).
     */
    suspend fun discover() {
        _isLoading.value = true
        _error.value = null

        try {
            val cached = cache.load()
            if (cached != null && !cache.isExpired()) {
                applyResponse(cached.reflectors, cached.yourRegion)
                return
            }

            val response = fetchReflectors()
            cache.save(response)
            applyResponse(response.reflectors, response.yourRegion)
        } catch (e: Exception) {
            // Fallback to cache on error
            val cached = cache.load()
            if (cached != null) {
                applyResponse(cached.reflectors, cached.yourRegion)
            } else {
                _error.value = "Unable to discover measurement endpoints. Check your internet connection."
            }
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Force refresh — ignores cache TTL.
     */
    suspend fun refresh() {
        _isLoading.value = true
        _error.value = null

        try {
            val response = fetchReflectors()
            cache.save(response)
            applyResponse(response.reflectors, response.yourRegion)
        } catch (e: Exception) {
            _error.value = "Failed to refresh reflector list: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    /** Filter reflectors by the user's tier access. */
    fun filterByTier(reflectors: List<Reflector>): List<Reflector> {
        val isPaid = stateManager.state.value == AgentState.CONNECTED
        return if (isPaid) reflectors else reflectors.filter { it.tier == "free" }
    }

    private fun applyResponse(reflectors: List<Reflector>, yourRegion: String?) {
        _reflectors.value = reflectors
        _userRegion.value = yourRegion
    }

    private suspend fun fetchReflectors(): ReflectorDiscoveryResponse {
        if (!USE_REAL_API) return MOCK_RESPONSE

        // TODO: Replace with real HTTP call when GET /v1/reflectors is deployed
        // val response = httpClient.get("https://api.slogr.io/v1/reflectors")
        // return response.body<ReflectorDiscoveryResponse>()
        throw UnsupportedOperationException("Real API not implemented yet")
    }
}
