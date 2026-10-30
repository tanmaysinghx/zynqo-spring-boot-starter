/*
 * Copyright 2026 the Zynqo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.tanmaysinghx.zynqo.core;

/**
 * Bounded set of request outcomes. Used as the {@code outcome} metric tag; never add values that
 * depend on request data.
 */
public enum Outcome {

  /** The operation executed and its response was stored. */
  EXECUTED,

  /** The operation executed after taking over an expired lease and its response was stored. */
  RECOVERED,

  /** A stored response was replayed; the operation did not execute. */
  REPLAYED,

  /** Rejected because another request with the same key was executing. */
  IN_PROGRESS,

  /** Rejected because the key was used with a different payload. */
  FINGERPRINT_MISMATCH,

  /** Rejected because a previous attempt is recorded as failed. */
  PREVIOUSLY_FAILED,

  /** Rejected because the store was unavailable before a claim could be established. */
  STORAGE_UNAVAILABLE,

  /** The operation threw; the record was released or marked failed per the classifier. */
  EXECUTION_FAILED,

  /** The operation executed but its result could not be stored; the record was marked failed. */
  NOT_STORED,

  /** The operation executed but ownership had been taken over; the result was not stored. */
  COMPLETION_REJECTED,

  /** The operation executed but the store failed while recording completion. */
  COMPLETION_ERROR,

  /** A stored response could not be decoded. */
  REPLAY_FAILED
}
