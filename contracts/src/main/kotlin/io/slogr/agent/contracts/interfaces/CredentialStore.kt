package io.slogr.agent.contracts.interfaces

import io.slogr.agent.contracts.AgentCredential

interface CredentialStore {
    fun load(): AgentCredential?
    fun store(credential: AgentCredential)
    fun delete()
    fun isConnected(): Boolean
}
