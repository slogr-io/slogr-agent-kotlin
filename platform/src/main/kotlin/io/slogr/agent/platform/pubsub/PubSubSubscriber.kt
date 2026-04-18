package io.slogr.agent.platform.pubsub

import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.pubsub.v1.stub.GrpcSubscriberStub
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings
import com.google.pubsub.v1.AcknowledgeRequest
import com.google.pubsub.v1.ProjectSubscriptionName
import com.google.pubsub.v1.PullRequest
import io.slogr.agent.contracts.AgentCredential
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

/**
 * Polls the agent's GCP Pub/Sub pull subscription for commands.
 *
 * Auth: GCP Service Account key from [AgentCredential.gcpServiceAccountKey] (base64-encoded JSON).
 * Poll interval: [pollIntervalMs] (default 5 seconds).
 * Max messages per pull: 10.
 *
 * Each message is ACKed after the command handler completes (success or failure).
 */
class PubSubSubscriber(
    private val credential:     AgentCredential,
    private val projectId:      String,
    private val dispatcher:     CommandDispatcher,
    private val pollIntervalMs: Long = 5_000
) {
    private val log  = LoggerFactory.getLogger(PubSubSubscriber::class.java)
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        // Resolve credentials: prefer SA key from registration, else fall back to
        // Application Default Credentials (works on GCP VMs via metadata server).
        val credentials: GoogleCredentials = try {
            val saKeyBase64 = credential.gcpServiceAccountKey
            if (saKeyBase64 != null) {
                log.info("Pub/Sub subscriber using SA key from registration")
                buildCredentialsFromKey(saKeyBase64)
            } else {
                log.info("Pub/Sub subscriber using Application Default Credentials")
                GoogleCredentials.getApplicationDefault()
                    .createScoped(listOf("https://www.googleapis.com/auth/pubsub"))
            }
        } catch (e: Exception) {
            log.warn("Pub/Sub credentials unavailable — subscriber disabled: ${e.message}")
            return
        }

        job = scope.launch {
            val settings = SubscriberStubSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials))
                .build()

            GrpcSubscriberStub.create(settings).use { stub ->
                val subName = ProjectSubscriptionName.format(projectId, credential.pubsubSubscription)
                log.info("Pub/Sub subscriber started on $subName")
                while (isActive) {
                    runCatching {
                        pollAndProcess(stub, subName)
                    }.onFailure { e ->
                        log.warn("Pub/Sub poll error: ${e.message}")
                    }
                    delay(pollIntervalMs)
                }
            }
        }
    }

    fun stop() { job?.cancel() }

    // ── Internal ───────────────────────────────────────────────────────────────

    private suspend fun pollAndProcess(stub: GrpcSubscriberStub, subName: String) =
        withContext(Dispatchers.IO) {
            val pullRequest = PullRequest.newBuilder()
                .setSubscription(subName)
                .setMaxMessages(10)
                .build()
            val response = stub.pullCallable().call(pullRequest)
            val messages = response.receivedMessagesList
            if (messages.isEmpty()) return@withContext

            log.debug("Pub/Sub: received ${messages.size} message(s)")
            val ackIds = mutableListOf<String>()
            for (msg in messages) {
                val body = msg.message.data.toStringUtf8()
                runCatching {
                    dispatcher.dispatch(body)
                }.onFailure { e ->
                    log.warn("Command dispatch failed: ${e.message}")
                }
                ackIds += msg.ackId
            }

            // ACK all messages (success or failure)
            if (ackIds.isNotEmpty()) {
                stub.acknowledgeCallable().call(
                    AcknowledgeRequest.newBuilder()
                        .setSubscription(subName)
                        .addAllAckIds(ackIds)
                        .build()
                )
            }
        }

    private fun buildCredentialsFromKey(saKeyBase64: String): GoogleCredentials {
        val keyJson = java.util.Base64.getDecoder().decode(saKeyBase64)
        return ServiceAccountCredentials.fromStream(ByteArrayInputStream(keyJson))
            .createScoped(listOf("https://www.googleapis.com/auth/pubsub"))
    }
}
