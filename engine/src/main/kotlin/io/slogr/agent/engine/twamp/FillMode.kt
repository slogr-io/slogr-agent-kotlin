package io.slogr.agent.engine.twamp

/** Controls how packet padding bytes are filled. */
enum class FillMode {
    /** All padding bytes are zero. */
    ZERO,
    /** Padding bytes are filled with random data. */
    RANDOM
}
